package com.rc.relay;

import com.rc.relay.config.RelayConfig;
import com.rc.relay.metrics.MetricsExporter;
import com.rc.relay.tcp.TcpRelayServer;
import com.rc.relay.udp.RelayServer;
import com.rc.relay.ws.WsRelayServer;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 中继服务器入口：同时暴露 UDP / TCP(TLS) / WS 三个数据面端口，对应降级阶梯
 * {@code Relay-UDP → Relay-TCP/TLS → Relay-WS}。令牌校验共享同一 HMAC secret。
 */
public final class RelayApplication {

    private static final Logger log = LoggerFactory.getLogger(RelayApplication.class);

    /** 单节点默认会话容量（负载比分母，可按部署规格调整）。 */
    private static final int DEFAULT_CAPACITY = 1000;

    private RelayApplication() {
    }

    public static void main(String[] args) {
        RelayConfig config = RelayConfig.fromEnv();
        SslContext sslContext = config.tls() ? buildSslContext(config) : null;

        // 先挂 Prometheus 注册表到全局，再启动各数据面端口，确保构造期注册的 gauge 落到可抓取源
        MetricsExporter metrics = new MetricsExporter(config.metricsPort());
        RelayServer udp = new RelayServer(config);
        TcpRelayServer tcp = new TcpRelayServer(config, sslContext);
        WsRelayServer ws = new WsRelayServer(config, sslContext);

        RelayHeartbeatReporter reporter = new RelayHeartbeatReporter(
                config, () -> udp.activeSessions() + tcp.activeSessions() + ws.activeSessions(),
                DEFAULT_CAPACITY);
        reporter.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("relay server stopping");
            reporter.close();
            metrics.close();
            udp.close();
            tcp.close();
            ws.close();
        }));
        log.info("Relay server started: node={} region={} udp={} tcp={} ws={} metrics={} (tls={}, ttl={}s)",
                config.nodeId(), config.region(), config.udpPort(), config.tcpPort(),
                config.wsPort(), config.metricsPort(), config.tls(), config.sessionTtlSeconds());
    }

    private static SslContext buildSslContext(RelayConfig config) {
        try {
            if (config.certFile() != null) {
                if (config.certPassword() != null) {
                    return SslContextBuilder.forServer(new File(config.certFile()), config.certPassword()).build();
                }
                return SslContextBuilder.forServer(new File(config.certFile())).build();
            }
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build relay SSL context", e);
        }
    }
}
