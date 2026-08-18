package com.rc.common.model;

import com.rc.common.protocol.PathType;

/**
 * 中继节点实体（对应 MySQL {@code relay_node} 表）。
 *
 * <p>一个节点同时暴露 UDP / TCP(TLS) / WS 三个数据面端口（降级阶梯共用同一节点），
 * 信令服务器按客户端期望 {@code region} 与节点 {@code loadRatio} 做就近调度。
 * {@code lastHeartbeatAt} 为最近心跳时间戳（epoch 毫秒），超过 TTL 判定离线。</p>
 */
public class RelayNode {

    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_ONLINE = 1;

    private Long id;
    private String nodeId;
    private String host;
    private String region;
    private int udpPort;
    private int tcpPort;
    private int wsPort;
    private boolean tls;
    private double loadRatio;
    private int status;
    private long lastHeartbeatAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public int getWsPort() {
        return wsPort;
    }

    public void setWsPort(int wsPort) {
        this.wsPort = wsPort;
    }

    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean tls) {
        this.tls = tls;
    }

    public double getLoadRatio() {
        return loadRatio;
    }

    public void setLoadRatio(double loadRatio) {
        this.loadRatio = loadRatio;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(long lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    /** 按传输路径取端口（RELAY_UDP/默认→udp，RELAY_TCP→tcp，RELAY_WS→ws）。 */
    public int portFor(PathType pathType) {
        return switch (pathType) {
            case RELAY_TCP -> tcpPort;
            case RELAY_WS -> wsPort;
            default -> udpPort;
        };
    }

    /** 按传输路径取数据面端点。 */
    public Endpoint endpointFor(PathType pathType) {
        return new Endpoint(host, portFor(pathType));
    }
}
