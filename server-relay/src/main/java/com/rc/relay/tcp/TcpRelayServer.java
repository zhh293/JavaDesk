package com.rc.relay.tcp;

import com.rc.common.codec.RelayPacketCodec;
import com.rc.common.crypto.CryptoException;
import com.rc.common.crypto.RelayToken;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.relay.config.RelayConfig;
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
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 中继 TCP（可选 TLS）服务器：每条连接独占一个会话席位，按会话 ID 把一端的数据
 * 密文透传给另一端。数据帧走「4 字节长度前缀 + {@link RelayPacketCodec} 裸包」，
 * 令牌校验仅发生在 JOIN 阶段，DATA 阶段纯转发。</p>
 */
public final class TcpRelayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpRelayServer.class);
    private static final int MAX_FRAME_SIZE = 4 << 20; // 4 MiB

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;
    private final StreamRelaySessionRegistry registry;
    private final byte[] secret;

    public TcpRelayServer(RelayConfig config, SslContext sslContext) {
        this.secret = config.secret();
        this.registry = new StreamRelaySessionRegistry(config.sessionTtlSeconds());
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
                        ch.pipeline().addLast("relay", new TcpRelayHandler(registry, secret));
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

        private final StreamRelaySessionRegistry registry;
        private final byte[] secret;

        TcpRelayHandler(StreamRelaySessionRegistry registry, byte[] secret) {
            this.registry = registry;
            this.secret = secret;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            ReferenceCountUtil.release(msg);

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
                log.debug("tcp relay join rejected from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
                return;
            }
            if (sessionId != pkt.sessionId()) {
                log.debug("tcp relay join session mismatch from {}", ctx.channel().remoteAddress());
                return;
            }
            if (registry.join(sessionId, ctx.channel())) {
                QosMetrics.increment(QosMetricNames.RELAY_JOIN_TOTAL, "protocol", "tcp");
                log.info("tcp relay join accepted: session={} channel={}", sessionId, ctx.channel().remoteAddress());
                ctx.writeAndFlush(Unpooled.wrappedBuffer(RelayPacketCodec.joinAck(sessionId)));
            } else {
                log.warn("tcp relay join rejected (session full): session={}", sessionId);
                ctx.close();
            }
        }

        private void handleData(ChannelHandlerContext ctx, RelayPacketCodec.Packet pkt, byte[] raw) {
            Channel peer = registry.peerOf(pkt.sessionId(), ctx.channel());
            if (peer == null) {
                log.debug("tcp relay data dropped (peer not ready): session={}", pkt.sessionId());
                return;
            }
            QosMetrics.increment(QosMetricNames.RELAY_DATA_TOTAL, "protocol", "tcp");
            QosMetrics.increment(QosMetricNames.BYTES_TX_TOTAL, raw.length, "protocol", "tcp");
            peer.writeAndFlush(Unpooled.wrappedBuffer(raw));
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
