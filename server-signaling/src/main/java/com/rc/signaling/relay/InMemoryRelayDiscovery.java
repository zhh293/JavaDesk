package com.rc.signaling.relay;

import com.rc.common.model.RelayNode;
import com.rc.signaling.config.SignalingProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic single-node discovery used by dev and tests. */
@Component
@Profile("!prod")
public final class InMemoryRelayDiscovery implements RelayDiscovery {
    private final ConcurrentHashMap<String, RelayNode> nodes = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public InMemoryRelayDiscovery(SignalingProperties properties) {
        ttlMillis = Math.multiplyExact(properties.getDeviceTtlSeconds(), 1_000L);
    }

    @Override
    public void acceptHeartbeat(RelayNode node) {
        node.setLastHeartbeatAt(System.currentTimeMillis());
        node.setStatus(RelayNode.STATUS_ONLINE);
        nodes.put(node.getNodeId(), copy(node));
    }

    @Override
    public void remove(String nodeId) { nodes.remove(nodeId); }

    @Override
    public List<RelayNode> healthyNodes() {
        long now = System.currentTimeMillis();
        return nodes.values().stream()
                .filter(node -> node.getStatus() == RelayNode.STATUS_ONLINE)
                .filter(node -> now - node.getLastHeartbeatAt() <= ttlMillis)
                .map(InMemoryRelayDiscovery::copy)
                .toList();
    }

    static RelayNode copy(RelayNode source) {
        RelayNode node = new RelayNode();
        node.setId(source.getId()); node.setNodeId(source.getNodeId()); node.setHost(source.getHost());
        node.setRegion(source.getRegion()); node.setUdpPort(source.getUdpPort());
        node.setTcpPort(source.getTcpPort()); node.setWsPort(source.getWsPort());
        node.setTls(source.isTls()); node.setLoadRatio(source.getLoadRatio());
        node.setStatus(source.getStatus()); node.setLastHeartbeatAt(source.getLastHeartbeatAt());
        return node;
    }
}
