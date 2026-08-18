package com.rc.signaling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 信令长连接配置（{@code rc.signaling.*}）。
 */
@Component
@ConfigurationProperties(prefix = "rc.signaling")
public class SignalingProperties {

    /** 信令长连接端口。 */
    private int port = 8443;

    /** 是否启用 TLS（dev 无证书时自动自签）。 */
    private boolean tls = true;

    /** PKCS12 证书路径（可选，缺省用 Netty 自签证书）。 */
    private String certFile;

    private String certPassword;

    /** 本信令节点 ID（跨节点路由寻址用，单机部署默认 node-1）。 */
    private String nodeId = "node-1";

    /** 在线设备 Redis/内存 TTL（秒），需大于心跳周期 × 丢失判定次数。 */
    private long deviceTtlSeconds = 60;

    /** 心跳落库（last_online_at）节流间隔（秒），避免高频写库。 */
    private long heartbeatDbFlushSeconds = 30;

    /** 中继服务器公网地址（就近调度占位，Phase 1 单节点静态配置）。 */
    private String relayHost = "127.0.0.1";

    /** 中继 UDP 端口。 */
    private int relayPort = 9090;

    /** 中继 TCP(TLS) 端口。 */
    private int relayTcpPort = 9091;

    /** 中继 WebSocket 端口。 */
    private int relayWsPort = 9092;

    /** 中继端点是否启用 TLS（RELAY_TCP/RELAY_WS 时下发给客户端）。 */
    private boolean relayTls = false;

    /** 中继令牌 HMAC 共享密钥（须与中继服务器一致，生产经环境变量注入）。 */
    private String relaySecret = "rc-relay-dev-secret-change-me";

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean tls) {
        this.tls = tls;
    }

    public String getCertFile() {
        return certFile;
    }

    public void setCertFile(String certFile) {
        this.certFile = certFile;
    }

    public String getCertPassword() {
        return certPassword;
    }

    public void setCertPassword(String certPassword) {
        this.certPassword = certPassword;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public long getDeviceTtlSeconds() {
        return deviceTtlSeconds;
    }

    public void setDeviceTtlSeconds(long deviceTtlSeconds) {
        this.deviceTtlSeconds = deviceTtlSeconds;
    }

    public long getHeartbeatDbFlushSeconds() {
        return heartbeatDbFlushSeconds;
    }

    public void setHeartbeatDbFlushSeconds(long heartbeatDbFlushSeconds) {
        this.heartbeatDbFlushSeconds = heartbeatDbFlushSeconds;
    }

    public String getRelayHost() {
        return relayHost;
    }

    public void setRelayHost(String relayHost) {
        this.relayHost = relayHost;
    }

    public int getRelayPort() {
        return relayPort;
    }

    public void setRelayPort(int relayPort) {
        this.relayPort = relayPort;
    }

    public int getRelayTcpPort() {
        return relayTcpPort;
    }

    public void setRelayTcpPort(int relayTcpPort) {
        this.relayTcpPort = relayTcpPort;
    }

    public int getRelayWsPort() {
        return relayWsPort;
    }

    public void setRelayWsPort(int relayWsPort) {
        this.relayWsPort = relayWsPort;
    }

    public boolean isRelayTls() {
        return relayTls;
    }

    public void setRelayTls(boolean relayTls) {
        this.relayTls = relayTls;
    }

    public String getRelaySecret() {
        return relaySecret;
    }

    public void setRelaySecret(String relaySecret) {
        this.relaySecret = relaySecret;
    }
}
