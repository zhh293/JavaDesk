package com.rc.client.signaling;

/**
 * 信令客户端配置。桌面入口会启用 TLS：仅 loopback 开发地址默认信任自签证书，
 * 远程地址默认 {@code trustAll=false} 并校验正式证书。
 */
public class SignalingClientConfig {

    private String host = "127.0.0.1";
    private int port = 8443;
    private boolean tls = false;
    private boolean trustAll = true;
    private long connectTimeoutMs = 5000;
    private long reconnectBackoffMaxMs = 8000;
    /** 期望中继区域（就近调度，空串 = 由服务端按全局负载择优）。 */
    private String region = "";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

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

    public boolean isTrustAll() {
        return trustAll;
    }

    public void setTrustAll(boolean trustAll) {
        this.trustAll = trustAll;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReconnectBackoffMaxMs() {
        return reconnectBackoffMaxMs;
    }

    public void setReconnectBackoffMaxMs(long reconnectBackoffMaxMs) {
        this.reconnectBackoffMaxMs = reconnectBackoffMaxMs;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
