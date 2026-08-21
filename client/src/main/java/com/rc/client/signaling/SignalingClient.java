package com.rc.client.signaling;

import com.rc.common.codec.SignalFrameDecoder;
import com.rc.common.codec.SignalFrameEncoder;
import com.rc.common.protocol.Signal;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 信令长连接客户端（Netty）。负责 TCP/TLS 连接、指数退避重连、收发信令帧。
 * 业务编排（注册 / 心跳 / 邀请）由 {@code ClientConnectionManager} 负责。
 */
public final class SignalingClient {

    private static final Logger log = LoggerFactory.getLogger(SignalingClient.class);

    private final SignalingClientConfig config;
    private final SignalingListener listener;
    private final EventLoopGroup group;
    private final ScheduledExecutorService reconnectExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private volatile Channel channel;
    private volatile long backoffMs;

    public SignalingClient(SignalingClientConfig config, SignalingListener listener) {
        this.config = config;
        this.listener = listener;
        this.group = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-signal", true));
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-signal-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running.set(true);
        connect();
    }

    public void stop() {
        running.set(false);
        reconnectExecutor.shutdownNow();
        Channel c = channel;
        if (c != null) {
            c.close();
        }
        group.shutdownGracefully();
    }

    public boolean isConnected() {
        Channel c = channel;
        return c != null && c.isActive();
    }

    /** 发送信令帧；未连接时静默丢弃（业务层自行决定重发/降级）。 */
    public void send(Signal signal) {
        Channel c = channel;
        if (c != null && c.isActive()) {
            c.writeAndFlush(signal);
        }
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        SslContext ssl = config.isTls() ? buildSslContext() : null;
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectTimeoutMs())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (ssl != null) {
                            ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc(), config.getHost(), config.getPort()));
                        }
                        ch.pipeline().addLast("signalDecoder", new SignalFrameDecoder());
                        ch.pipeline().addLast("signalEncoder", new SignalFrameEncoder());
                        ch.pipeline().addLast("handler", new ClientHandler());
                    }
                });
        bootstrap.connect(config.getHost(), config.getPort()).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                channel = f.channel();
                backoffMs = 0;
                log.info("signaling connected to {}:{}", config.getHost(), config.getPort());
                listener.onConnected();
            } else {
                log.warn("signaling connect failed: {}", f.cause() == null ? "unknown" : f.cause().getMessage());
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        backoffMs = backoffMs == 0 ? 1000L : Math.min(backoffMs * 2, config.getReconnectBackoffMaxMs());
        long delay = backoffMs <= 100 ? backoffMs
                : java.util.concurrent.ThreadLocalRandom.current().nextLong(100, backoffMs + 1);
        reconnectExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private SslContext buildSslContext() {
        try {
            if (config.isTrustAll()) {
                return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            }
            return SslContextBuilder.forClient().build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build signaling SSL context", e);
        }
    }

    private final class ClientHandler extends SimpleChannelInboundHandler<Signal> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Signal signal) {
            listener.onSignal(signal);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            channel = null;
            listener.onDisconnected(null);
            scheduleReconnect();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("signaling channel exception", cause);
            ctx.close();
        }
    }
}
