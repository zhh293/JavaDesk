package com.rc.relay.ws;

import com.rc.common.codec.RelayJoinPayloadV2;
import com.rc.common.codec.RelayPacketCodecV2;
import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.relay.config.RelayConfig;
import com.rc.relay.security.RelayTicketKeyProvider;
import com.rc.relay.session.RelaySessionKey;
import com.rc.relay.tcp.StreamRelaySessionRegistryV2;
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
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 中继 WebSocket 服务器（伪 443 出口兜底）：每个二进制 WS 帧承载一个
 * {@link RelayPacketCodecV2} 裸包，按 session/epoch/path/role 密文透传。连接即角色席位。</p>
 */
public final class WsRelayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WsRelayServer.class);

    private static final String WS_PATH = "/rc-relay";

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;
    private final StreamRelaySessionRegistryV2 registry;

    public WsRelayServer(RelayConfig config, SslContext sslContext, RelayTicketKeyProvider keys) {
        this.registry = new StreamRelaySessionRegistryV2(config.sessionTtlSeconds() * 1000);
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
                        ch.pipeline().addLast("relay", new WsRelayHandler(registry, config, keys));
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

        private final StreamRelaySessionRegistryV2 registry;
        private final RelayConfig config;
        private final RelayTicketKeyProvider keys;

        WsRelayHandler(StreamRelaySessionRegistryV2 registry, RelayConfig config, RelayTicketKeyProvider keys) {
            this.registry = registry;
            this.config = config;
            this.keys = keys;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
            ByteBuf content = frame.content();
            byte[] data = new byte[content.readableBytes()];
            content.readBytes(data);
            RelayPacketCodecV2.Packet pkt;
            try { pkt = RelayPacketCodecV2.decode(data); } catch (IllegalArgumentException malformed) { return; }
            if (pkt.type() == RelayPacketCodecV2.Type.JOIN) {
                handleJoin(ctx, pkt);
            } else if (pkt.type() == RelayPacketCodecV2.Type.DATA) {
                handleData(ctx, pkt, data);
            } else if (pkt.type() == RelayPacketCodecV2.Type.PING) {
                write(ctx, response(pkt, RelayPacketCodecV2.Type.PONG));
            }
        }

        private void handleJoin(ChannelHandlerContext ctx, RelayPacketCodecV2.Packet pkt) {
            try {
                RelayJoinPayloadV2 join = RelayJoinPayloadV2.decode(pkt.payload());
                RelayTicketV2 ticket = keys.verify(join.ticket());
                requirePacketMatchesTicket(pkt, ticket);
                registry.join(ticket, config.nodeId(), com.rc.common.protocol.PathType.RELAY_WS,
                        join.connectionNonce(), ctx.channel(), System.currentTimeMillis());
                QosMetrics.increment(QosMetricNames.RELAY_JOIN_TOTAL, "protocol", "ws");
                write(ctx, response(pkt, RelayPacketCodecV2.Type.JOIN_ACCEPTED));
            } catch (RuntimeException e) {
                log.debug("ws relay join rejected from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
                write(ctx, response(pkt, RelayPacketCodecV2.Type.JOIN_REJECTED));
                ctx.close();
            }
        }

        private void handleData(ChannelHandlerContext ctx, RelayPacketCodecV2.Packet pkt, byte[] raw) {
            RelaySessionKey key = new RelaySessionKey(pkt.sessionId(), pkt.routeEpoch(), pkt.pathType());
            Channel peer = registry.peerFor(key, pkt.role(), ctx.channel(), pkt.sequence(), System.currentTimeMillis());
            if (peer == null) {
                log.debug("ws relay data dropped (peer not ready): session={}", pkt.sessionId());
                return;
            }
            QosMetrics.increment(QosMetricNames.RELAY_DATA_TOTAL, "protocol", "ws");
            QosMetrics.increment(QosMetricNames.BYTES_TX_TOTAL, raw.length, "protocol", "ws");
            peer.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(raw)));
        }

        private static void requirePacketMatchesTicket(RelayPacketCodecV2.Packet pkt, RelayTicketV2 ticket) {
            if (ticket.sessionId() != pkt.sessionId() || ticket.routeEpoch() != pkt.routeEpoch()
                    || ticket.pathType() != pkt.pathType() || ticket.role() != pkt.role()) {
                throw new SecurityException("relay ticket does not match packet envelope");
            }
        }

        private static RelayPacketCodecV2.Packet response(RelayPacketCodecV2.Packet request,
                                                           RelayPacketCodecV2.Type type) {
            return new RelayPacketCodecV2.Packet(type, 0, request.sessionId(), request.routeEpoch(),
                    request.pathType(), request.role(), request.sequence(), new byte[0]);
        }

        private static void write(ChannelHandlerContext ctx, RelayPacketCodecV2.Packet packet) {
            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(RelayPacketCodecV2.encode(packet))));
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
