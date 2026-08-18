package com.rc.relay.metrics;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.nio.charset.StandardCharsets;

/**
 * 中继节点的 Prometheus 抓取端点（plain main，无 Spring）。
 *
 * <p>创建独立 {@link PrometheusMeterRegistry} 并注册进 Micrometer 全局注册表，使
 * {@code com.rc.common.metrics.QosMetrics} 上报的计数/计时/即时值落到同一抓取源；
 * 经 Netty HTTP 暴露 {@code GET /metrics}（Prometheus 文本格式 0.0.4），
 * 供 Prometheus 定期抓取，聚合进全局 QoS 大盘。</p>
 */
public final class MetricsExporter implements AutoCloseable {

    private final PrometheusMeterRegistry registry;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;

    public MetricsExporter(int port) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        Metrics.addRegistry(registry);
        this.bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("rc-metrics-boss", true));
        this.workerGroup = new NioEventLoopGroup(new DefaultThreadFactory("rc-metrics-worker", true));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("http", new HttpServerCodec());
                        ch.pipeline().addLast("aggregator", new HttpObjectAggregator(65536));
                        ch.pipeline().addLast("handler", new MetricsHandler());
                    }
                });
        this.serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
    }

    /** Prometheus 文本格式的指标快照。 */
    public String scrape() {
        return registry.scrape();
    }

    @Override
    public void close() {
        serverChannel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        Metrics.removeRegistry(registry);
        registry.close();
    }

    private final class MetricsHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            FullHttpResponse resp;
            if ("/metrics".equals(req.uri())) {
                byte[] body = scrape().getBytes(StandardCharsets.UTF_8);
                resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(body));
                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            } else {
                resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            }
            ctx.writeAndFlush(resp);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
