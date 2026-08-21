package com.rc.relay.udp;

import com.rc.common.codec.RelayJoinPayloadV2;
import com.rc.common.codec.RelayPacketCodecV2;
import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.common.model.Endpoint;
import com.rc.relay.config.RelayConfig;
import com.rc.relay.security.RelayTicketKeyProvider;
import com.rc.relay.session.RelaySessionKey;
import com.rc.relay.session.RelaySessionRegistryV2;
import io.netty.bootstrap.Bootstrap;
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

/**
 * 中继 UDP 服务器：单 socket 承载多会话，按会话 ID 把一端的数据报密文透传给另一端。
 *
 * <p>JOIN 使用 Ed25519 assignment ticket 并校验 node/epoch/path/role；DATA 还要求来源席位
 * 与单调 sequence 匹配。业务载荷保持 E2EE 密文，Relay 不解析。</p>
 */
public final class RelayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayServer.class);

    private final EventLoopGroup group;
    private final Channel channel;
    private final RelaySessionRegistryV2 registry;

    public RelayServer(RelayConfig config, RelayTicketKeyProvider keys) {
        this.registry = new RelaySessionRegistryV2(config.sessionTtlSeconds() * 1000);
        QosMetrics.gauge(QosMetricNames.RELAY_SESSIONS_ACTIVE, registry::size, "protocol", "udp");
        this.group = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-relay-udp", true));
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new RelayHandler(config, keys));
        this.channel = bootstrap.bind(config.host(), config.udpPort()).syncUninterruptibly().channel();
        log.info("Relay UDP server listening on {}:{}", config.host(), config.udpPort());
    }

    @Override
    public void close() {
        registry.close();
        channel.close();
        group.shutdownGracefully();
    }

    /** 当前活跃会话数（负载指标）。 */
    public int activeSessions() {
        return registry.size();
    }

    private final class RelayHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        private final RelayConfig config;
        private final RelayTicketKeyProvider keys;

        RelayHandler(RelayConfig config, RelayTicketKeyProvider keys) {
            this.config = config;
            this.keys = keys;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            byte[] data = new byte[packet.content().readableBytes()];
            packet.content().readBytes(data);
            InetSocketAddress sender = packet.sender();
            ReferenceCountUtil.release(packet);

            RelayPacketCodecV2.Packet pkt;
            try { pkt = RelayPacketCodecV2.decode(data); } catch (IllegalArgumentException malformed) { return; }
            if (pkt.type() == RelayPacketCodecV2.Type.JOIN) {
                handleJoin(ctx, sender, pkt);
            } else if (pkt.type() == RelayPacketCodecV2.Type.DATA) {
                handleData(ctx, sender, pkt, data);
            } else if (pkt.type() == RelayPacketCodecV2.Type.PING) {
                write(ctx, sender, new RelayPacketCodecV2.Packet(RelayPacketCodecV2.Type.PONG, 0,
                        pkt.sessionId(), pkt.routeEpoch(), pkt.pathType(), pkt.role(), pkt.sequence(), new byte[0]));
            }
        }

        private void handleJoin(ChannelHandlerContext ctx, InetSocketAddress sender, RelayPacketCodecV2.Packet pkt) {
            try {
                RelayJoinPayloadV2 join = RelayJoinPayloadV2.decode(pkt.payload());
                RelayTicketV2 ticket = keys.verify(join.ticket());
                requirePacketMatchesTicket(pkt, ticket);
                registry.join(ticket, config.nodeId(), com.rc.common.protocol.PathType.RELAY_UDP,
                        join.connectionNonce(), endpointOf(sender), System.currentTimeMillis());
                QosMetrics.increment(QosMetricNames.RELAY_JOIN_TOTAL, "protocol", "udp");
                write(ctx, sender, response(pkt, RelayPacketCodecV2.Type.JOIN_ACCEPTED));
            } catch (RuntimeException e) {
                log.debug("relay join rejected from {}: {}", sender, e.getMessage());
                write(ctx, sender, response(pkt, RelayPacketCodecV2.Type.JOIN_REJECTED));
            }
        }

        private void handleData(ChannelHandlerContext ctx, InetSocketAddress sender,
                                RelayPacketCodecV2.Packet pkt, byte[] raw) {
            Endpoint src = endpointOf(sender);
            RelaySessionKey key = new RelaySessionKey(pkt.sessionId(), pkt.routeEpoch(), pkt.pathType());
            Endpoint peer = registry.peerFor(key, pkt.role(), src, pkt.sequence(), System.currentTimeMillis());
            if (peer == null) {
                log.debug("relay data dropped (peer not ready): session={} from={}", pkt.sessionId(), src);
                return;
            }
            QosMetrics.increment(QosMetricNames.RELAY_DATA_TOTAL, "protocol", "udp");
            QosMetrics.increment(QosMetricNames.BYTES_TX_TOTAL, raw.length, "protocol", "udp");
            ctx.writeAndFlush(new DatagramPacket(
                    Unpooled.wrappedBuffer(raw), new InetSocketAddress(peer.ip(), peer.port())));
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

        private static void write(ChannelHandlerContext ctx, InetSocketAddress recipient,
                                  RelayPacketCodecV2.Packet packet) {
            ctx.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(RelayPacketCodecV2.encode(packet)), recipient));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("relay udp exception", cause);
            ctx.close();
        }
    }

    private static Endpoint endpointOf(InetSocketAddress addr) {
        return new Endpoint(addr.getAddress().getHostAddress(), addr.getPort());
    }
}
