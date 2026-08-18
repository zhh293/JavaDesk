package com.rc.client.ice;

import com.rc.common.codec.DataFrame;
import com.rc.common.codec.DataFrameCodec;
import com.rc.common.constant.ProtocolConstants;
import com.rc.common.model.Endpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 贯穿「候选发现 → 打洞探测 → 连通性检查 → 数据面」的单一 UDP socket。
 *
 * <p>复用一个 {@link DatagramChannel} 以保证 NAT 映射一致（文档 §1.4「同一 UDP Socket」）。
 * 入站包按 STUN magic cookie 与数据帧首字节解复用：STUN 绑定请求自动应答（打洞），
 * 绑定响应回填映射；非 STUN 视为 {@link DataFrame} 交给数据监听器。</p>
 *
 * <p>底层弃用 Netty 的 {@code NioDatagramChannel}，改用 JDK NIO 的 {@link DatagramChannel}
 * + {@link Selector}：数据面本质是单 socket 的 STUN/数据帧解复用，无需 Netty 事件循环；
 * 更关键的是，NIO channel 可直接经 {@link #punchedDatagramSocket()} 把<b>同一个已打洞
 * socket</b> 交给 kwik（QUIC）独占，避免「Netty 与 kwik 双读者争抢同一 fd」的交接矛盾。</p>
 */
public final class UdpSocket implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UdpSocket.class);
    private static final HexFormat HEX = HexFormat.of();

    /** UDP 单包最大承载（IPv4 65535 - IP 头 20 - UDP 头 8）。 */
    private static final int MAX_DATAGRAM_SIZE = 65507;

    private final DatagramChannel channel;
    private final Selector selector;
    private final ScheduledExecutorService scheduler;
    private final Thread reader;

    private final Map<String, CompletableFuture<Endpoint>> pending = new ConcurrentHashMap<>();

    private volatile Consumer<Endpoint> contactListener;   // 打洞阶段：对端 STUN 触点
    private volatile Consumer<DataFrame> dataListener;     // 数据阶段：DataFrame 回调
    private volatile boolean closed;
    private volatile boolean handedOff;                    // 已交棒给 kwik，close 不再接管读循环

    public UdpSocket() {
        try {
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.socket().setReuseAddress(true);
            channel.bind(new InetSocketAddress(0));
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            throw new IllegalStateException("udp socket init failed", e);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-udp-timer");
            t.setDaemon(true);
            return t;
        });
        reader = new Thread(this::selectLoop, "rc-udp-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public Endpoint localEndpoint() {
        InetSocketAddress addr = (InetSocketAddress) channel.socket().getLocalSocketAddress();
        return new Endpoint(addr.getAddress().getHostAddress(), addr.getPort());
    }

    public void send(Endpoint target, byte[] data) {
        if (closed || handedOff) {
            return;
        }
        sendTo(new InetSocketAddress(target.ip(), target.port()), data);
    }

    public void sendData(Endpoint target, DataFrame frame) {
        ByteBuf buf = Unpooled.buffer(ProtocolConstants.DATA_FRAME_HEADER_SIZE + frame.payloadLength());
        try {
            DataFrameCodec.encode(frame, buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            send(target, data);
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }

    /** 向 STUN 服务器发绑定请求，异步取回本端公网映射地址（候选发现用）。 */
    public CompletableFuture<Endpoint> stunRequest(Endpoint server, String username, long timeoutMs) {
        byte[] txId = StunCodec.newTransactionId();
        String key = HEX.formatHex(txId);
        CompletableFuture<Endpoint> future = new CompletableFuture<>();
        pending.put(key, future);
        send(server, StunCodec.bindingRequest(txId, username));
        scheduler.schedule(() -> {
            if (pending.remove(key) != null) {
                future.completeExceptionally(new TimeoutException("STUN request timeout to " + server));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        return future;
    }

    /**
     * 把当前打洞 socket 交棒给 kwik（QUIC）独占：停止内部读循环、channel 转阻塞，
     * 返回绑定在同一端口的 {@link DatagramSocket}（同一 fd，NAT 映射不变）。
     *
     * <p>幂等：重复调用返回同一 socket。交棒后 {@link #send} 静默失效，{@link #close}
     * 仍会关闭底层 channel（会话结束 / QUIC 失败时的统一清理）。</p>
     */
    public DatagramSocket punchedDatagramSocket() {
        if (handedOff) {
            return channel.socket();
        }
        handedOff = true;
        try {
            SelectionKey key = channel.keyFor(selector);
            if (key != null) {
                key.cancel();
            }
            selector.wakeup();
            channel.configureBlocking(true);
            return channel.socket();
        } catch (IOException e) {
            throw new IllegalStateException("udp socket handoff to quic failed", e);
        }
    }

    public void setContactListener(Consumer<Endpoint> contactListener) {
        this.contactListener = contactListener;
    }

    public void setDataListener(Consumer<DataFrame> dataListener) {
        this.dataListener = dataListener;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdownNow();
        try {
            selector.close();
        } catch (IOException ignored) {
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    // ---------- 读循环 ----------

    private void selectLoop() {
        ByteBuffer buf = ByteBuffer.allocateDirect(MAX_DATAGRAM_SIZE);
        while (!closed && !handedOff) {
            try {
                if (selector.select(200) == 0) {
                    continue;
                }
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid() || !key.isReadable()) {
                        continue;
                    }
                    DatagramChannel ch = (DatagramChannel) key.channel();
                    buf.clear();
                    InetSocketAddress sender;
                    while ((sender = (InetSocketAddress) ch.receive(buf)) != null) {
                        buf.flip();
                        byte[] data = new byte[buf.remaining()];
                        buf.get(data);
                        dispatch(sender, data);
                        buf.clear();
                    }
                }
            } catch (ClosedSelectorException | ClosedChannelException e) {
                break;
            } catch (IOException e) {
                if (!closed) {
                    log.warn("udp read loop failed", e);
                }
                break;
            }
        }
    }

    // ---------- 解复用 ----------

    private void dispatch(InetSocketAddress sender, byte[] data) {
        if (StunCodec.isStun(data)) {
            handleStun(sender, data);
        } else {
            handleDataFrame(data);
        }
    }

    private void handleStun(InetSocketAddress sender, byte[] data) {
        StunCodec.Decoded decoded;
        try {
            decoded = StunCodec.decode(data);
        } catch (RuntimeException e) {
            log.debug("malformed STUN packet from {}", sender);
            return;
        }
        Endpoint peer = endpointOf(sender);
        if (decoded.type() == StunCodec.BINDING_REQUEST) {
            sendTo(sender, StunCodec.bindingSuccessResponse(decoded.transactionId(), peer));
            Consumer<Endpoint> listener = contactListener;
            if (listener != null) {
                listener.accept(peer);
            }
        } else if (decoded.type() == StunCodec.BINDING_SUCCESS) {
            CompletableFuture<Endpoint> future = pending.remove(HEX.formatHex(decoded.transactionId()));
            if (future != null) {
                future.complete(decoded.mapped() != null ? decoded.mapped() : peer);
            } else {
                Consumer<Endpoint> listener = contactListener;
                if (listener != null) {
                    listener.accept(peer);
                }
            }
        }
    }

    private void handleDataFrame(byte[] data) {
        Consumer<DataFrame> listener = dataListener;
        if (listener == null) {
            return;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            DataFrame frame = DataFrameCodec.decode(buf);
            if (frame != null) {
                listener.accept(frame);
            }
        } catch (RuntimeException e) {
            log.debug("malformed data frame, ignored");
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }

    private void sendTo(InetSocketAddress target, byte[] data) {
        try {
            channel.send(ByteBuffer.wrap(data), target);
        } catch (IOException e) {
            if (!closed) {
                log.warn("udp send to {} failed", target);
            }
        }
    }

    private static Endpoint endpointOf(InetSocketAddress addr) {
        return new Endpoint(addr.getAddress().getHostAddress(), addr.getPort());
    }
}
