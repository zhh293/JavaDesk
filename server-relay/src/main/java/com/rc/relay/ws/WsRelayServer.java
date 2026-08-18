package com.rc.relay.ws;

import com.rc.common.codec.RelayPacketCodec;
import com.rc.common.crypto.CryptoException;
import com.rc.common.crypto.RelayToken;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.relay.config.RelayConfig;
import com.rc.relay.tcp.StreamRelaySessionRegistry;
import io.netty.bootstrap.ServerBootstrap;
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
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 中继 WebSocket 服务器（伪 443 出口兜底）：每个二进制 WS 帧承载一个
 * {@link RelayPacketCodec} 裸包，按会话 ID 密文透传。会话表复用
 * {@link StreamRelaySessionRegistry}（连接即席位）。</p>
 */
public final class WsRelayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WsRelayServer.class);

    private static final String WS_PATH = "/rc-relay";

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;
    private final StreamRelaySessionRegistry registry;
    private final byte[] secret;

    public WsRelayServer(RelayConfig config, SslContext sslContext) {
        this.secret = config.secret();
        this.registry = new StreamRelaySessionRegistry(config.sessionTtlSeconds());
        QosMetrics.gauge(QosMetricNames.RELAY_SESSIONS_ACTIVE, registry::size, "protocol", "ws");
        this.bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-relay-ws-boss", true));
        this.workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("rc-relay-ws-worker", true));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslContext != null) {
                            ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc()));
                        }
                        ch.pipeline().addLast("httpCodec", new HttpServerCodec());
                        ch.pipeline().addLast("aggregator", new HttpObjectAggregator(65536));
                        ch.pipeline().addLast("wsProtocol", new WebSocketServerProtocolHandler(WS_PATH));
                        ch.pipeline().addLast("relay", new WsRelayHandler(registry, secret));
                    }
                });
        this.serverChannel = bootstrap.bind(config.host(), config.wsPort()).syncUninterruptibly().channel();
        log.info("Relay WS server listening on {}:{}{} (tls={})",
                config.host(), config.wsPort(), WS_PATH, sslContext != null);
    }

    @Override
    public void close() {
        registry.close();
        serverChannel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    /** 当前活跃会话数（负载指标）。 */
    public int activeSessions() {
        return registry.size();
    }

    private static final class WsRelayHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

        private final StreamRelaySessionRegistry registry;
        private final byte[] secret;

        WsRelayHandler(StreamRelaySessionRegistry registry, byte[] secret) {
            this.registry = registry;
            this.secret = secret;
        }

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
            if (pkt.type() == RelayPacketCodec.TYPE_JOIN) {
                handleJoin(ctx, pkt);
            } else if (pkt.type() == RelayPacketCodec.TYPE_DATA) {
                handleData(ctx, pkt, data);
            }
        }

        private void handleJoin(ChannelHandlerContext ctx, RelayPacketCodec.Packet pkt) {
            String token = new String(pkt.payload(), StandardCharsets.UTF_8);
            long sessionId;
            try {
                sessionId = RelayToken.verify(token, secret);
            } catch (CryptoException e) {
                log.debug("ws relay join rejected from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
                return;
            }
            if (sessionId != pkt.sessionId()) {
                log.debug("ws relay join session mismatch from {}", ctx.channel().remoteAddress());
                return;
            }
            if (registry.join(sessionId, ctx.channel())) {
                QosMetrics.increment(QosMetricNames.RELAY_JOIN_TOTAL, "protocol", "ws");
                log.info("ws relay join accepted: session={} channel={}", sessionId, ctx.channel().remoteAddress());
                ctx.writeAndFlush(new BinaryWebSocketFrame(
                        Unpooled.wrappedBuffer(RelayPacketCodec.joinAck(sessionId))));
            } else {
                log.warn("ws relay join rejected (session full): session={}", sessionId);
                ctx.close();
            }
        }

        private void handleData(ChannelHandlerContext ctx, RelayPacketCodec.Packet pkt, byte[] raw) {
            Channel peer = registry.peerOf(pkt.sessionId(), ctx.channel());
            if (peer == null) {
                log.debug("ws relay data dropped (peer not ready): session={}", pkt.sessionId());
                return;
            }
            QosMetrics.increment(QosMetricNames.RELAY_DATA_TOTAL, "protocol", "ws");
            QosMetrics.increment(QosMetricNames.BYTES_TX_TOTAL, raw.length, "protocol", "ws");
            peer.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(raw)));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            registry.remove(ctx.channel());
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("ws relay exception", cause);
            ctx.close();
        }
    }
}
