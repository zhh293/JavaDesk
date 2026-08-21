package com.rc.signaling.api.dto;

/**
 * 中继节点心跳上报体（中继服务器 → 信令服务器，内部接口）。
 */
public record RelayHeartbeatRequest(
        String nodeId,
        String host,
        String region,
        int udpPort,
        int tcpPort,
        int wsPort,
        boolean tls,
        double loadRatio,
        int activeSessions,
        int capacity,
        double cpuRatio,
        double bandwidthRatio,
        double directMemoryRatio) {
}
