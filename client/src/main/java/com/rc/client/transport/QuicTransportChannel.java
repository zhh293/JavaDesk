package com.rc.client.transport;

import com.rc.common.codec.DataFrame;
import com.rc.common.codec.DataFrameCodec;
import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameFlags;
import com.rc.common.constant.FrameType;
import com.rc.common.constant.ProtocolConstants;
import com.rc.common.constant.Thresholds;
import com.rc.common.model.ChannelInfo;
import com.rc.common.protocol.PathType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2P QUIC 数据面：在打洞产出的 socket 之上建立 QUIC 连接，按通道分 stream/datagram
 * 多路复用（设计文档 §3.5「通道划分」），实现 {@link TransportChannel} 接口不变。
 *
 * <p>通道映射：</p>
 * <ul>
 *   <li><b>可靠通道</b>（CONTROL / FILE / CLIPBOARD）→ 单条双向 QUIC stream，按
 *       {@link DataFrameCodec} 帧头里的 channel 字节再分通道，保证强可靠与顺序。</li>
 *   <li><b>实时通道</b>（VIDEO / AUDIO）→ QUIC datagram（部分可靠，优先实时性，允许少量丢帧）。</li>
 * </ul>
 *
 * <p><b>kwik API 约定</b>：本类依赖 kwik 0.9.x 的 {@link QuicConnection#openStream()}、
 * {@link QuicStream#getInputStream()}/{@link QuicStream#getOutputStream()}，以及 datagram
 * 收发（见 {@link #sendDatagram} / {@link #receiveDatagram}）。这些方法签名在未装
 * Maven/JDK17 的环境下无法编译核对，接入时需对照 kwik 实际 javadoc 校正（datagram
 * 可能经 {@code QuicDatagramSocket} 而非连接直连方法暴露）。会话密钥（AES-256-GCM）
 * 暂存于此，与 {@link UdpTransportChannel} 一致，待 E2EE 数据面统一启用。</p>
 */
public final class QuicTransportChannel implements TransportChannel {

    private static final Logger log = LoggerFactory.getLogger(QuicTransportChannel.class);

    private final QuicConnection connection;
    private final byte[] sessionKey;
    private final QuicStream reliableStream;
    private final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final ChannelInfo info = new ChannelInfo(PathType.P2P, 0, 0, 0);

    private final ByteBuf streamBuffer = Unpooled.buffer();
    private final ScheduledExecutorService keepalive;
    private final Thread streamReader;
    private final Thread datagramReader;
    private volatile boolean closed;

    /**
     * @param connection 已在打洞 socket 上完成握手的 QUIC 连接（见 {@code QuicTransportEndpoint}）。
     * @param sessionKey E2EE 会话密钥（暂存，数据面加密启用时使用）。
     */
    public QuicTransportChannel(QuicConnection connection, byte[] sessionKey) throws IOException {
        this.connection = connection;
        this.sessionKey = sessionKey;
        this.reliableStream = connection.openStream();
        this.keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-quic-keepalive");
            t.setDaemon(true);
            return t;
        });
        this.streamReader = new Thread(this::streamReadLoop, "rc-quic-stream");
        this.streamReader.setDaemon(true);
        this.datagramReader = new Thread(this::datagramReadLoop, "rc-quic-dgram");
        this.datagramReader.setDaemon(true);
        this.streamReader.start();
        this.datagramReader.start();
        startKeepalive();
    }

    @Override
    public void send(ChannelType ch, byte[] payload) {
        if (closed) {
            return;
        }
        DataFrame frame = new DataFrame(ch, FrameType.DATA, FrameFlags.NONE,
                seq.getAndIncrement(), System.currentTimeMillis(), payload);
        ByteBuf buf = Unpooled.buffer(ProtocolConstants.DATA_FRAME_HEADER_SIZE + payload.length);
        try {
            DataFrameCodec.encode(frame, buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            if (isReliable(ch)) {
                OutputStream out = reliableStream.getOutputStream();
                out.write(bytes);
                out.flush();
            } else {
                sendDatagram(bytes);
            }
        } catch (IOException e) {
            log.warn("quic send failed", e);
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }

    @Override
    public void addListener(TransportListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(TransportListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ChannelInfo info() {
        return info;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        keepalive.shutdownNow();
        try {
            reliableStream.close();
        } catch (Exception e) {
            log.debug("reliable stream close failed", e);
        }
        connection.close();
        streamReader.interrupt();
        datagramReader.interrupt();
        for (TransportListener listener : listeners) {
            try {
                listener.onClosed(null);
            } catch (Exception e) {
                log.warn("listener onClosed failed", e);
            }
        }
    }

    // ---------- 通道 → stream/datagram 路由 ----------

    private static boolean isReliable(ChannelType ch) {
        return ch == ChannelType.CONTROL || ch == ChannelType.FILE || ch == ChannelType.CLIPBOARD;
    }

    // ---------- 入站读取 ----------

    private void streamReadLoop() {
        InputStream in;
        try {
            in = reliableStream.getInputStream();
        } catch (Exception e) {
            log.warn("reliable stream input unavailable", e);
            return;
        }
        byte[] tmp = new byte[16384];
        try {
            int n;
            while (!closed && (n = in.read(tmp)) != -1) {
                streamBuffer.writeBytes(tmp, 0, n);
                dispatchFrames(streamBuffer);
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("reliable stream read failed", e);
            }
        }
    }

    private void datagramReadLoop() {
        try {
            while (!closed) {
                byte[] data = receiveDatagram();
                if (data == null) {
                    continue;
                }
                ByteBuf buf = Unpooled.wrappedBuffer(data);
                try {
                    DataFrame frame = DataFrameCodec.decode(buf);
                    if (frame != null) {
                        dispatch(frame);
                    }
                } catch (RuntimeException e) {
                    log.debug("malformed quic datagram, ignored");
                } finally {
                    ReferenceCountUtil.release(buf);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("datagram receive failed", e);
            }
        }
    }

    private void dispatchFrames(ByteBuf buf) {
        DataFrame frame;
        while ((frame = DataFrameCodec.decode(buf)) != null) {
            dispatch(frame);
        }
        buf.discardReadBytes();
    }

    private void dispatch(DataFrame frame) {
        for (TransportListener listener : listeners) {
            try {
                listener.onData(frame);
            } catch (Exception e) {
                log.warn("listener onData failed", e);
            }
        }
    }

    // ---------- 保活 ----------

    private void startKeepalive() {
        keepalive.scheduleAtFixedRate(() -> {
            if (closed) {
                return;
            }
            DataFrame heartbeat = new DataFrame(ChannelType.CONTROL, FrameType.HEARTBEAT,
                    FrameFlags.NONE, seq.getAndIncrement(), System.currentTimeMillis(), new byte[0]);
            ByteBuf buf = Unpooled.buffer(ProtocolConstants.DATA_FRAME_HEADER_SIZE);
            try {
                DataFrameCodec.encode(heartbeat, buf);
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                reliableStream.getOutputStream().write(bytes);
                reliableStream.getOutputStream().flush();
            } catch (IOException e) {
                log.warn("quic keepalive failed", e);
            } finally {
                ReferenceCountUtil.release(buf);
            }
        }, Thresholds.KEEPALIVE_HOME_MS, Thresholds.KEEPALIVE_HOME_MS, TimeUnit.MILLISECONDS);
    }

    // ---------- kwik datagram 收发的集中封装（API 待对照 kwik javadoc 校正） ----------

    /**
     * 经 QUIC datagram 帧发送一帧。kwik 若经 {@code QuicDatagramSocket}（而非连接直连方法）
     * 暴露 datagram，此处为唯一需调整的发送点。
     */
    private void sendDatagram(byte[] data) throws IOException {
        connection.sendDatagram(data);
    }

    /** 阻塞读取一个 QUIC datagram 帧（无数据时返回 {@code null} 或阻塞，取决于 kwik 语义）。 */
    private byte[] receiveDatagram() throws IOException {
        return connection.receiveDatagram();
    }
}
