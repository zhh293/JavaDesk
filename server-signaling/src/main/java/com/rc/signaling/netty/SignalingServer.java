package com.rc.signaling.netty;

import com.rc.common.codec.SignalFrameDecoder;
import com.rc.common.codec.SignalFrameEncoder;
import com.rc.signaling.config.SignalingProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 信令长连接服务器（Netty）。随 Spring 容器启动/停止。
 * TLS 缺省用自签证书（dev），配置 {@code rc.signaling.cert-file} 可换正式证书。
 */
@Component
public class SignalingServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SignalingServer.class);

    private final SignalingProperties props;
    private final SignalServerHandler handler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running;

    public SignalingServer(SignalingProperties props, SignalServerHandler handler) {
        this.props = props;
        this.handler = handler;
    }

    @Override
    public void start() {
        SslContext sslContext = props.isTls() ? buildSslContext() : null;
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
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
                            // 读空闲 = 设备 TTL（超过即判心跳超时下线）
                            ch.pipeline().addLast("idle", new IdleStateHandler(
                                    Math.toIntExact(props.getDeviceTtlSeconds()), 0, 0));
                            ch.pipeline().addLast("signalDecoder", new SignalFrameDecoder());
                            ch.pipeline().addLast("signalEncoder", new SignalFrameEncoder());
                            ch.pipeline().addLast("signalHandler", handler);
                        }
                    });
            serverChannel = bootstrap.bind(props.getPort()).syncUninterruptibly().channel();
            running = true;
            log.info("Signaling server listening on port {} (tls={})", props.getPort(), props.isTls());
        } catch (Exception e) {
            shutdown();
            throw new IllegalStateException("signaling server failed to start on port " + props.getPort(), e);
        }
    }

    private SslContext buildSslContext() {
        try {
            if (props.getCertFile() != null && !props.getCertFile().isBlank()) {
                if (props.getKeyFile() == null || props.getKeyFile().isBlank()) {
                    throw new IllegalStateException("rc.signaling.key-file is required with cert-file");
                }
                return SslContextBuilder.forServer(
                        new File(props.getCertFile()),
                        new File(props.getKeyFile()),
                        emptyToNull(props.getCertPassword())).build();
            }
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build SSL context", e);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
