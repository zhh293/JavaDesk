package com.rc.signaling.api.dto;

/**
 * 中继节点在线信息（内部运维 / 调试接口返回，不暴露数据库主键）。
 */
public record RelayNodeInfo(
        String nodeId,
        String host,
        String region,
        int udpPort,
        int tcpPort,
        int wsPort,
        boolean tls,
        double loadRatio,
        int status,
        long lastHeartbeatAt) {
}
