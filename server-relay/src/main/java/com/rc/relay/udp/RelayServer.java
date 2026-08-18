package com.rc.relay.udp;

import com.rc.common.codec.RelayPacketCodec;
import com.rc.common.crypto.CryptoException;
import com.rc.common.crypto.RelayToken;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.common.model.Endpoint;
import com.rc.relay.config.RelayConfig;
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
import java.nio.charset.StandardCharsets;

/**
 * 中继 UDP 服务器：单 socket 承载多会话，按会话 ID 把一端的数据报密文透传给另一端。
 *
 * <p>令牌校验仅发生在 JOIN 阶段（HMAC 签名 + 短 TTL），DATA 阶段按「源地址 → 对端地址」
 * 直接转发原始字节，不解析业务载荷，实现 E2EE 密文透传。</p>
 */
public final class RelayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayServer.class);

    private final EventLoopGroup group;
    private final Channel channel;
    private final RelaySessionRegistry registry;

    public RelayServer(RelayConfig config) {
        this.registry = new RelaySessionRegistry(config.sessionTtlSeconds());
        QosMetrics.gauge(QosMetricNames.RELAY_SESSIONS_ACTIVE, registry::size, "protocol", "udp");
        this.group = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-relay-udp", true));
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new RelayHandler(config.secret()));
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

        private final byte[] secret;

        RelayHandler(byte[] secret) {
            this.secret = secret;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            byte[] data = new byte[packet.content().readableBytes()];
            packet.content().readBytes(data);
            InetSocketAddress sender = packet.sender();
            ReferenceCountUtil.release(packet);

            RelayPacketCodec.Packet pkt = RelayPacketCodec.decode(data);
            if (pkt == null) {
                return;
            }
            if (pkt.type() == RelayPacketCodec.TYPE_JOIN) {
                handleJoin(ctx, sender, pkt);
            } else if (pkt.type() == RelayPacketCodec.TYPE_DATA) {
                handleData(ctx, sender, pkt, data);
            }
        }

        private void handleJoin(ChannelHandlerContext ctx, InetSocketAddress sender, RelayPacketCodec.Packet pkt) {
            String token = new String(pkt.payload(), StandardCharsets.UTF_8);
            long sessionId;
            try {
                sessionId = RelayToken.verify(token, secret);
            } catch (CryptoException e) {
                log.debug("relay join rejected from {}: {}", sender, e.getMessage());
                return;
            }
            if (sessionId != pkt.sessionId()) {
                log.debug("relay join session mismatch from {}", sender);
                return;
            }
            Endpoint src = endpointOf(sender);
            if (registry.join(sessionId, src)) {
                QosMetrics.increment(QosMetricNames.RELAY_JOIN_TOTAL, "protocol", "udp");
                log.info("relay join accepted: session={} peer={}", sessionId, src);
                byte[] ack = RelayPacketCodec.joinAck(sessionId);
                ctx.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(ack), sender));
            } else {
                log.warn("relay join rejected (session full): session={} peer={}", sessionId, src);
            }
        }

        private void handleData(ChannelHandlerContext ctx, InetSocketAddress sender,
                                RelayPacketCodec.Packet pkt, byte[] raw) {
            Endpoint src = endpointOf(sender);
            Endpoint peer = registry.peerOf(pkt.sessionId(), src);
            if (peer == null) {
                log.debug("relay data dropped (peer not ready): session={} from={}", pkt.sessionId(), src);
                return;
            }
            QosMetrics.increment(QosMetricNames.RELAY_DATA_TOTAL, "protocol", "udp");
            QosMetrics.increment(QosMetricNames.BYTES_TX_TOTAL, raw.length, "protocol", "udp");
            ctx.writeAndFlush(new DatagramPacket(
                    Unpooled.wrappedBuffer(raw), new InetSocketAddress(peer.ip(), peer.port())));
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
