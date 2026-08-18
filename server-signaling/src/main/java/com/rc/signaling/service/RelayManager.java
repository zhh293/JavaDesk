package com.rc.signaling.service;

import com.rc.common.model.RelayNode;
import com.rc.signaling.dao.RelayNodeMapper;
import com.rc.signaling.session.RelayNodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    private final RelayNodeRegistry registry;

    public RelayManager(RelayNodeMapper mapper, RelayNodeRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    /** 节点心跳：内存注册（TTL 续期）并幂等落库。 */
    @Transactional
    public void heartbeat(RelayNode node) {
        node.setStatus(RelayNode.STATUS_ONLINE);
        registry.heartbeat(node);
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
        return registry.bestFor(region);
    }

    /**
     * 记录一次中继分配的结果（会话结束/路径迁移时回填），驱动节点质量评分：
     * 成功提升、失败降低该节点的复合调度分，配合上报 {@code load_ratio} 做智能择优。
     */
    public void recordAllocResult(String nodeId, boolean success) {
        registry.recordAllocResult(nodeId, success);
    }

    /** 在线节点快照（内部运维 / 调试接口用）。 */
    public List<RelayNode> onlineNodes() {
        return registry.onlineNodes();
    }
}
