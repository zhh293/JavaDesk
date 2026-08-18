package com.rc.client.transport;

import com.rc.common.codec.DataFrame;
import com.rc.common.codec.DataFrameCodec;
import com.rc.common.codec.RelayPacketCodec;
import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameFlags;
import com.rc.common.constant.FrameType;
import com.rc.common.constant.ProtocolConstants;
import com.rc.common.constant.Thresholds;
import com.rc.common.model.ChannelInfo;
import com.rc.common.model.Endpoint;
import com.rc.common.protocol.PathType;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中继 UDP 数据面：经中继服务器把 DataFrame 密文透传给对端。
 *
 * <p>建链时向中继发 JOIN（携带一次性令牌），中继校验后绑定会话两端，此后数据帧
 * 按「源 → 对端」转发。JOIN 未确认前周期重发，确认后切换为心跳保活。
 * 会话密钥（AES-256-GCM）暂存于此，与 {@link UdpTransportChannel} 一致，待采集/输入
 * 通道落地后统一启用端到端加密。</p>
 */
public final class RelayTransportChannel implements TransportChannel {

    private static final Logger log = LoggerFactory.getLogger(RelayTransportChannel.class);
    private static final long JOIN_RETRY_INTERVAL_MS = 500L;

    private final Endpoint relay;
    private final long sessionId;
    private final String token;
    private final byte[] sessionKey;
    private final EventLoopGroup group;
    private final Channel channel;
    private final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final ScheduledExecutorService timer;
    private final CompletableFuture<Void> joined = new CompletableFuture<>();
    private final ChannelInfo info = new ChannelInfo(PathType.RELAY_UDP, 0, 0, 0);
    private volatile boolean closed;
    private volatile long lastHeartbeat;

    public RelayTransportChannel(Endpoint relay, long sessionId, String token, byte[] sessionKey) {
        this.relay = relay;
        this.sessionId = sessionId;
        this.token = token;
        this.sessionKey = sessionKey;
        this.group = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-relay-udp", true));
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-relay-timer");
            t.setDaemon(true);
            return t;
        });
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new RelayHandler());
        this.channel = bootstrap.bind(0).syncUninterruptibly().channel();
    }

    /** 发起 JOIN 并启动重发/保活定时器。 */
    public void start() {
        sendJoin();
        timer.scheduleAtFixedRate(() -> {
            if (closed) {
                return;
            }
            if (!joined.isDone()) {
                sendJoin();
            } else if (System.currentTimeMillis() - lastHeartbeat >= Thresholds.KEEPALIVE_HOME_MS) {
                sendHeartbeat();
            }
        }, JOIN_RETRY_INTERVAL_MS, JOIN_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 阻塞等待中继 JOIN 确认。 */
    public void awaitJoined(long timeoutMs) throws TimeoutException, InterruptedException {
        try {
            joined.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new TimeoutException("relay join failed: " + e.getCause());
        }
    }

    @Override
    public void send(ChannelType ch, byte[] payload) {
        if (closed) {
            return;
        }
        DataFrame frame = new DataFrame(ch, FrameType.DATA, FrameFlags.NONE,
                seq.getAndIncrement(), System.currentTimeMillis(), payload);
        ByteBuf buf = Unpooled.buffer(ProtocolConstants.DATA_FRAME_HEADER_SIZE + frame.payloadLength());
        try {
            DataFrameCodec.encode(frame, buf);
            byte[] frameBytes = new byte[buf.readableBytes()];
            buf.readBytes(frameBytes);
            byte[] packet = RelayPacketCodec.data(sessionId, frameBytes);
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(packet),
                    new InetSocketAddress(relay.ip(), relay.port())));
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
        timer.shutdownNow();
        channel.close();
        group.shutdownGracefully();
        for (TransportListener listener : listeners) {
            try {
                listener.onClosed(null);
            } catch (Exception e) {
                log.warn("listener onClosed failed", e);
            }
        }
    }

    private void sendJoin() {
        byte[] packet = RelayPacketCodec.join(sessionId, token);
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(packet),
                new InetSocketAddress(relay.ip(), relay.port())));
    }

    private void sendHeartbeat() {
        lastHeartbeat = System.currentTimeMillis();
        DataFrame heartbeat = new DataFrame(ChannelType.CONTROL, FrameType.HEARTBEAT,
                FrameFlags.NONE, seq.getAndIncrement(), System.currentTimeMillis(), new byte[0]);
        ByteBuf buf = Unpooled.buffer(ProtocolConstants.DATA_FRAME_HEADER_SIZE);
        try {
            DataFrameCodec.encode(heartbeat, buf);
            byte[] frameBytes = new byte[buf.readableBytes()];
            buf.readBytes(frameBytes);
            byte[] packet = RelayPacketCodec.data(sessionId, frameBytes);
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(packet),
                    new InetSocketAddress(relay.ip(), relay.port())));
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }

    private final class RelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            byte[] data = new byte[packet.content().readableBytes()];
            packet.content().readBytes(data);
            ReferenceCountUtil.release(packet);

            RelayPacketCodec.Packet pkt = RelayPacketCodec.decode(data);
            if (pkt == null) {
                return;
            }
            if (pkt.type() == RelayPacketCodec.TYPE_JOIN_ACK) {
                if (!joined.isDone()) {
                    log.info("relay joined, session={}", sessionId);
                    joined.complete(null);
                }
            } else if (pkt.type() == RelayPacketCodec.TYPE_DATA) {
                onData(pkt.payload());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("relay udp exception", cause);
            ctx.close();
        }
    }

    private void onData(byte[] frameBytes) {
        if (closed) {
            return;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(frameBytes);
        try {
            DataFrame frame = DataFrameCodec.decode(buf);
            if (frame != null) {
                for (TransportListener listener : listeners) {
                    listener.onData(frame);
                }
            }
        } catch (RuntimeException e) {
            log.debug("malformed relay data frame, ignored");
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }
}
