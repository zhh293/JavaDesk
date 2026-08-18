package com.rc.relay.config;

import java.nio.charset.StandardCharsets;

/**
 * 中继服务器配置（plain main，无 Spring）。从系统属性 / 环境变量读取，缺省 dev 值。
 *
 * <p>同一节点可同时暴露 UDP / TCP(TLS) / WS 三个数据面端口，对应降级阶梯
 * {@code Relay-UDP → Relay-TCP/TLS → Relay-WS}。与信令服务器共享的 {@code secret}
 * 必须一致，否则令牌校验失败（生产经环境变量注入）。</p>
 *
 * <p>节点身份（{@code nodeId}/{@code region}/{@code advertiseHost}）用于向信令服务器
 * 心跳注册，供多地域就近调度；{@code signalingUrl} 为信令内部心跳接口。</p>
 */
public final class RelayConfig {

    private final String host;
    private final int udpPort;
    private final int tcpPort;
    private final int wsPort;
    private final int metricsPort;
    private final boolean tls;
    private final String certFile;
    private final String certPassword;
    private final byte[] secret;
    private final long sessionTtlSeconds;

    private final String nodeId;
    private final String region;
    private final String advertiseHost;
    private final String signalingUrl;
    private final long heartbeatIntervalMs;

    private RelayConfig(String host, int udpPort, int tcpPort, int wsPort, int metricsPort, boolean tls,
                        String certFile, String certPassword, byte[] secret, long sessionTtlSeconds,
                        String nodeId, String region, String advertiseHost, String signalingUrl,
                        long heartbeatIntervalMs) {
        this.host = host;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.wsPort = wsPort;
        this.metricsPort = metricsPort;
        this.tls = tls;
        this.certFile = certFile;
        this.certPassword = certPassword;
        this.secret = secret;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.nodeId = nodeId;
        this.region = region;
        this.advertiseHost = advertiseHost;
        this.signalingUrl = signalingUrl;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public static RelayConfig fromEnv() {
        String host = prop("rc.relay.host", "RC_RELAY_HOST", "0.0.0.0");
        int udpPort = Integer.parseInt(prop("rc.relay.port", "RC_RELAY_PORT", "9090"));
        int tcpPort = Integer.parseInt(prop("rc.relay.tcp-port", "RC_RELAY_TCP_PORT", "9091"));
        int wsPort = Integer.parseInt(prop("rc.relay.ws-port", "RC_RELAY_WS_PORT", "9092"));
        int metricsPort = Integer.parseInt(prop("rc.relay.metrics-port", "RC_RELAY_METRICS_PORT", "9093"));
        boolean tls = Boolean.parseBoolean(prop("rc.relay.tls", "RC_RELAY_TLS", "false"));
        String certFile = prop("rc.relay.cert-file", "RC_RELAY_CERT_FILE", "");
        String certPassword = prop("rc.relay.cert-password", "RC_RELAY_CERT_PASSWORD", "");
        String secret = prop("rc.relay.secret", "RC_RELAY_SECRET", "rc-relay-dev-secret-change-me");
        long ttl = Long.parseLong(prop("rc.relay.session-ttl-seconds", "RC_RELAY_SESSION_TTL_SECONDS", "120"));

        String nodeId = prop("rc.relay.node-id", "RC_RELAY_NODE_ID", "relay-1");
        String region = prop("rc.relay.region", "RC_RELAY_REGION", "cn-east");
        String advertiseHost = prop("rc.relay.advertise-host", "RC_RELAY_ADVERTISE_HOST", "127.0.0.1");
        String signalingUrl = prop("rc.relay.signaling-url", "RC_RELAY_SIGNALING_URL",
                "http://127.0.0.1:8080/internal/relay-nodes/heartbeat");
        long heartbeatMs = Long.parseLong(prop("rc.relay.heartbeat-ms", "RC_RELAY_HEARTBEAT_MS", "10000"));

        return new RelayConfig(host, udpPort, tcpPort, wsPort, metricsPort, tls,
                certFile.isBlank() ? null : certFile,
                certPassword.isBlank() ? null : certPassword,
                secret.getBytes(StandardCharsets.UTF_8), ttl,
                nodeId, region, advertiseHost, signalingUrl, heartbeatMs);
    }

    public String host() {
        return host;
    }

    public int udpPort() {
        return udpPort;
    }

    public int tcpPort() {
        return tcpPort;
    }

    public int wsPort() {
        return wsPort;
    }

    public int metricsPort() {
        return metricsPort;
    }

    public boolean tls() {
        return tls;
    }

    public String certFile() {
        return certFile;
    }

    public String certPassword() {
        return certPassword;
    }

    public byte[] secret() {
        return secret;
    }

    public long sessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public String nodeId() {
        return nodeId;
    }

    public String region() {
        return region;
    }

    public String advertiseHost() {
        return advertiseHost;
    }

    public String signalingUrl() {
        return signalingUrl;
    }

    public long heartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    private static String prop(String sysProp, String env, String def) {
        String value = System.getProperty(sysProp);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return (value == null || value.isBlank()) ? def : value;
    }
}
