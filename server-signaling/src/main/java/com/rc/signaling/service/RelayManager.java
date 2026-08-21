package com.rc.signaling.service;

import com.rc.common.model.RelayNode;
import com.rc.signaling.dao.RelayNodeMapper;
import com.rc.signaling.relay.RelayDiscovery;
import com.rc.signaling.relay.RelayHealth;
import com.rc.signaling.relay.RelayHealthStore;
import com.rc.signaling.relay.RelayObservation;
import com.rc.signaling.relay.RelayRuntimeSample;
import com.rc.common.protocol.PathType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Comparator;
import java.util.Set;

/**
 * 中继节点管理：接收节点心跳（内存注册 + 落库），并按客户端期望 region 就近调度。
 *
 * <p>调度策略：region 精确命中 → 该区域负载最低节点；未命中 / 无区域 → 全局负载最低节点。
 * 无任何在线节点时返回 {@code null}，由调用方回退静态单节点配置（dev）。</p>
 */
@Service
public class RelayManager {

    private static final Logger log = LoggerFactory.getLogger(RelayManager.class);

    private final RelayNodeMapper mapper;
    private final RelayDiscovery discovery;
    private final RelayHealthStore healthStore;

    public RelayManager(RelayNodeMapper mapper, RelayDiscovery discovery, RelayHealthStore healthStore) {
        this.mapper = mapper;
        this.discovery = discovery;
        this.healthStore = healthStore;
    }

    /** 节点心跳：内存注册（TTL 续期）并幂等落库。 */
    @Transactional
    public void heartbeat(RelayNode node, int activeSessions, int capacity,
                          double cpuRatio, double bandwidthRatio, double directMemoryRatio) {
        node.setStatus(RelayNode.STATUS_ONLINE);
        node.setLastHeartbeatAt(System.currentTimeMillis());
        discovery.acceptHeartbeat(node);
        int safeCapacity = capacity > 0 ? capacity : Math.max(1, activeSessions);
        healthStore.updateRuntime(new RelayRuntimeSample(node.getNodeId(), Math.max(0, activeSessions),
                safeCapacity, cpuRatio, bandwidthRatio, directMemoryRatio, node.getLastHeartbeatAt()));
        RelayNode existing = mapper.findByNodeId(node.getNodeId());
        if (existing == null) {
            mapper.insert(node);
            log.info("relay node registered: node={} region={} udp={} tcp={} ws={} tls={}",
                    node.getNodeId(), node.getRegion(), node.getUdpPort(),
                    node.getTcpPort(), node.getWsPort(), node.isTls());
        } else {
            node.setId(existing.getId());
            mapper.updateHeartbeat(node);
        }
    }

    /** 就近择优；无在线节点返回 {@code null}。 */
    public RelayNode selectBest(String region) {
        return selectBest(region, "unknown", PathType.RELAY_UDP, Set.of());
    }

    /** Deterministic server-side selection. Discovery supplies candidates; shared health supplies scores. */
    public RelayNode selectBest(String region, String networkProvider, PathType pathType,
                                Set<String> excludedNodes) {
        long now = System.currentTimeMillis();
        Comparator<RelayNode> comparator = Comparator
                .comparingInt((RelayNode node) -> region != null && !region.isBlank()
                        && region.equalsIgnoreCase(node.getRegion()) ? 0 : 1)
                .thenComparingDouble(node -> healthStore.health(node.getNodeId(), region, networkProvider, pathType)
                        .score(now))
                .thenComparing(RelayNode::getNodeId);
        return discovery.healthyNodes().stream()
                .filter(node -> !excludedNodes.contains(node.getNodeId()))
                .filter(node -> node.portFor(pathType) > 0)
                .filter(node -> healthStore.health(node.getNodeId(), region, networkProvider, pathType)
                        .capacityRatio() < 0.98)
                .min(comparator).orElse(null);
    }

    /**
     * 记录一次中继分配的结果（会话结束/路径迁移时回填），驱动节点质量评分：
     * 成功提升、失败降低该节点的复合调度分，配合上报 {@code load_ratio} 做智能择优。
     */
    public void recordAllocResult(String nodeId, boolean success) {
        recordObservation(new RelayObservation(nodeId, "unknown", "unknown", PathType.RELAY_UDP,
                success, 0, success ? 0 : 1, success ? "" : "session_end", System.currentTimeMillis()));
    }

    public void recordObservation(RelayObservation observation) {
        healthStore.record(observation);
    }

    /** 在线节点快照（内部运维 / 调试接口用）。 */
    public List<RelayNode> onlineNodes() {
        return discovery.healthyNodes();
    }

    public RelayHealth health(String nodeId, String region, String provider, PathType pathType) {
        return healthStore.health(nodeId, region, provider, pathType);
    }
}
