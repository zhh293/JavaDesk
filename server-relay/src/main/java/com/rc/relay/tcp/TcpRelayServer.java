package com.rc.relay.tcp;

import com.rc.common.codec.RelayJoinPayloadV2;
import com.rc.common.codec.RelayPacketCodecV2;
import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.relay.config.RelayConfig;
import com.rc.relay.security.RelayTicketKeyProvider;
import com.rc.relay.session.RelaySessionKey;
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
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 中继 TCP（可选 TLS）服务器：每条连接独占一个会话席位，按会话 ID 把一端的数据
 * 密文透传给另一端。数据帧走「4 字节长度前缀 + {@link RelayPacketCodecV2} 裸包」，
 * 令牌校验仅发生在 JOIN 阶段，DATA 阶段纯转发。</p>
 */
public final class TcpRelayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpRelayServer.class);
    private static final int MAX_FRAME_SIZE = 4 << 20; // 4 MiB

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;
    private final StreamRelaySessionRegistryV2 registry;

    public TcpRelayServer(RelayConfig config, SslContext sslContext, RelayTicketKeyProvider keys) {
        this.registry = new StreamRelaySessionRegistryV2(config.sessionTtlSeconds() * 1000);
        QosMetrics.gauge(QosMetricNames.RELAY_SESSIONS_ACTIVE, registry::size, "protocol", "tcp");
        this.bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-relay-tcp-boss", true));
        this.workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("rc-relay-tcp-worker", true));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslContext != null) {
                            ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc()));
                        }
                        ch.pipeline().addLast("frameDecoder",
                                new LengthFieldBasedFrameDecoder(MAX_FRAME_SIZE, 0, 4, 0, 4));
                        ch.pipeline().addLast("framePrepender", new LengthFieldPrepender(4));
                        ch.pipeline().addLast("relay", new TcpRelayHandler(registry, config, keys));
                    }
                });
        this.serverChannel = bootstrap.bind(config.host(), config.tcpPort()).syncUninterruptibly().channel();
        log.info("Relay TCP server listening on {}:{} (tls={})", config.host(), config.tcpPort(), sslContext != null);
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

    private static final class TcpRelayHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private final StreamRelaySessionRegistryV2 registry;
        private final RelayConfig config;
        private final RelayTicketKeyProvider keys;

        TcpRelayHandler(StreamRelaySessionRegistryV2 registry, RelayConfig config, RelayTicketKeyProvider keys) {
            this.registry = registry;
            this.config = config;
            this.keys = keys;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            RelayPacketCodecV2.Packet pkt;
            try { pkt = RelayPacketCodecV2.decode(data); } catch (IllegalArgumentException malformed) { return; }
            if (pkt.type() == RelayPacketCodecV2.Type.JOIN) {
                handleJoin(ctx, pkt);
            } else if (pkt.type() == RelayPacketCodecV2.Type.DATA) {
                handleData(ctx, pkt, data);
            } else if (pkt.type() == RelayPacketCodecV2.Type.PING) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(RelayPacketCodecV2.encode(response(pkt,
                        RelayPacketCodecV2.Type.PONG))));
            }
        }

        private void handleJoin(ChannelHandlerContext ctx, RelayPacketCodecV2.Packet pkt) {
            try {
                RelayJoinPayloadV2 join = RelayJoinPayloadV2.decode(pkt.payload());
                RelayTicketV2 ticket = keys.verify(join.ticket());
                requirePacketMatchesTicket(pkt, ticket);
                registry.join(ticket, config.nodeId(), com.rc.common.protocol.PathType.RELAY_TCP,
                        join.connectionNonce(), ctx.channel(), System.currentTimeMillis());
                QosMetrics.increment(QosMetricNames.RELAY_JOIN_TOTAL, "protocol", "tcp");
                ctx.writeAndFlush(Unpooled.wrappedBuffer(RelayPacketCodecV2.encode(response(pkt,
                        RelayPacketCodecV2.Type.JOIN_ACCEPTED))));
            } catch (RuntimeException e) {
                log.debug("tcp relay join rejected from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
                ctx.writeAndFlush(Unpooled.wrappedBuffer(RelayPacketCodecV2.encode(response(pkt,
                        RelayPacketCodecV2.Type.JOIN_REJECTED))));
                ctx.close();
            }
        }

        private void handleData(ChannelHandlerContext ctx, RelayPacketCodecV2.Packet pkt, byte[] raw) {
            RelaySessionKey key = new RelaySessionKey(pkt.sessionId(), pkt.routeEpoch(), pkt.pathType());
            Channel peer = registry.peerFor(key, pkt.role(), ctx.channel(), pkt.sequence(), System.currentTimeMillis());
            if (peer == null) {
                log.debug("tcp relay data dropped (peer not ready): session={}", pkt.sessionId());
                return;
            }
            QosMetrics.increment(QosMetricNames.RELAY_DATA_TOTAL, "protocol", "tcp");
            QosMetrics.increment(QosMetricNames.BYTES_TX_TOTAL, raw.length, "protocol", "tcp");
            peer.writeAndFlush(Unpooled.wrappedBuffer(raw));
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

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            registry.remove(ctx.channel());
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("tcp relay exception", cause);
            ctx.close();
        }
    }
}
