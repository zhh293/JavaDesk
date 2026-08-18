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
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
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
 * 中继 WebSocket 数据面（伪 443 出口兜底）：每个二进制 WS 帧承载一个
 * {@link RelayPacketCodec} 裸包。JOIN 待握手完成后由重发定时器驱动，确认后切心跳。
 * 会话密钥（AES-256-GCM）暂存待 E2EE 启用。</p>
 */
public final class RelayWsTransportChannel implements TransportChannel {

    private static final Logger log = LoggerFactory.getLogger(RelayWsTransportChannel.class);
    private static final long JOIN_RETRY_INTERVAL_MS = 500L;
    private static final int MAX_FRAME_SIZE = 4 << 20;

    private final Endpoint relay;
    private final long sessionId;
    private final String token;
    private final byte[] sessionKey;
    private final boolean tls;
    private final boolean trustAll;
    private final EventLoopGroup group;
    private final Channel channel;
    private final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final ScheduledExecutorService timer;
    private final CompletableFuture<Void> joined = new CompletableFuture<>();
    private final ChannelInfo info = new ChannelInfo(PathType.RELAY_WS, 0, 0, 0);
    private volatile boolean closed;
    private volatile boolean handshakeComplete;
    private volatile long lastHeartbeat;

    public RelayWsTransportChannel(Endpoint relay, long sessionId, String token, byte[] sessionKey,
                                   boolean tls, boolean trustAll) {
        this.relay = relay;
        this.sessionId = sessionId;
        this.token = token;
        this.sessionKey = sessionKey;
        this.tls = tls;
        this.trustAll = trustAll;
        this.group = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-relay-ws", true));
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-relay-ws-timer");
            t.setDaemon(true);
            return t;
        });
        String scheme = tls ? "wss" : "ws";
        URI uri = URI.create(scheme + "://" + relay.ip() + ":" + relay.port() + "/rc-relay");
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        SslContext ssl = tls ? buildSslContext() : null;
                        if (ssl != null) {
                            ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc(), relay.ip(), relay.port()));
                        }
                        ch.pipeline().addLast("httpCodec", new HttpClientCodec());
                        ch.pipeline().addLast("aggregator", new HttpObjectAggregator(65536));
                        ch.pipeline().addLast("wsProtocol", new WebSocketClientProtocolHandler(
                                WebSocketClientHandshakerFactory.newHandshaker(
                                        uri, WebSocketVersion.V13, null, true,
                                        EmptyHttpHeaders.INSTANCE, MAX_FRAME_SIZE)));
                        ch.pipeline().addLast("relay", new WsRelayClientHandler());
                    }
                });
        this.channel = bootstrap.connect(new InetSocketAddress(relay.ip(), relay.port()))
                .syncUninterruptibly().channel();
    }

    /** 启动 JOIN 重发 / 保活定时器（JOIN 待握手完成后实际发出）。 */
    public void start() {
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
            throw new TimeoutException("relay ws join failed: " + e.getCause());
        }
    }

    @Override
    public void send(ChannelType ch, byte[] payload) {
        if (closed || !handshakeComplete) {
            return;
        }
        DataFrame frame = new DataFrame(ch, FrameType.DATA, FrameFlags.NONE,
                seq.getAndIncrement(), System.currentTimeMillis(), payload);
        ByteBuf buf = Unpooled.buffer(ProtocolConstants.DATA_FRAME_HEADER_SIZE + payload.length);
        try {
            DataFrameCodec.encode(frame, buf);
            byte[] frameBytes = new byte[buf.readableBytes()];
            buf.readBytes(frameBytes);
            byte[] packet = RelayPacketCodec.data(sessionId, frameBytes);
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(packet)));
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
        if (!handshakeComplete) {
            return;
        }
        channel.writeAndFlush(new BinaryWebSocketFrame(
                Unpooled.wrappedBuffer(RelayPacketCodec.join(sessionId, token))));
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
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(packet)));
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }

    private SslContext buildSslContext() {
        try {
            if (trustAll) {
                return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            }
            return SslContextBuilder.forClient().build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build relay ws ssl context", e);
        }
    }

    private final class WsRelayClientHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
            ByteBuf content = frame.content();
            byte[] data = new byte[content.readableBytes()];
            content.readBytes(data);
            ReferenceCountUtil.release(frame);

            RelayPacketCodec.Packet pkt = RelayPacketCodec.decode(data);
            if (pkt == null) {
                return;
            }
            if (pkt.type() == RelayPacketCodec.TYPE_JOIN_ACK) {
                if (!joined.isDone()) {
                    log.info("relay ws joined, session={}", sessionId);
                    joined.complete(null);
                }
            } else if (pkt.type() == RelayPacketCodec.TYPE_DATA) {
                onData(pkt.payload());
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                handshakeComplete = true;
                log.info("relay ws handshake complete, session={}", sessionId);
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("relay ws exception", cause);
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
            log.debug("malformed relay ws data frame, ignored");
        } finally {
            ReferenceCountUtil.release(buf);
        }
    }
}
