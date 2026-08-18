package com.rc.signaling.session;

import com.rc.common.model.RelayNode;
import com.rc.signaling.config.SignalingProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线中继节点内存注册表（{@code nodeId → RelayNode}），TTL 靠访问时惰性淘汰。
 *
 * <p>中继节点数量级远小于在线设备，单机内存即可支撑就近调度；跨信令节点的节点池
 * 共享（Redis 汇总）留到多信令节点部署阶段。心跳由 {@link com.rc.signaling.service.RelayManager}
 * 写入并同步落库。</p>
 *
 * <p><b>智能动态调度：</b>除节点上报的 {@code load_ratio}（静态负载）外，维护每节点的
 * 质量评分 {@link NodeQuality}——对最近若干次中继分配结果做 EWMA 平滑，得到分配成功率；
 * 调度时按「区域命中 → 复合分升序」择优。复合分 = {@code load * 权重 + 失败率 * 权重}，
 * 分越低越优，从而把「负载最低」升级为「负载低且分配成功率高」，避免反复把会话塞给
 * 一个负载低但数据面已劣化的节点。</p>
 */
@Component
public class RelayNodeRegistry {

    private static final double LOAD_WEIGHT = 0.5;
    private static final double QUALITY_WEIGHT = 0.5;
    private static final double EWMA_ALPHA = 0.3;
    private static final double INITIAL_SUCCESS_RATE = 1.0;

    private final long ttlMillis;
    private final Map<String, RelayNode> online = new ConcurrentHashMap<>();
    private final Map<String, NodeQuality> quality = new ConcurrentHashMap<>();

    public RelayNodeRegistry(SignalingProperties props) {
        // 中继心跳 TTL 复用设备在线 TTL（60s），心跳周期通常远小于该值。
        this.ttlMillis = props.getDeviceTtlSeconds() * 1000L;
    }

    /** 心跳写入 / 续期（带最近心跳时间戳），并把上报负载并入 EWMA。 */
    public void heartbeat(RelayNode node) {
        node.setLastHeartbeatAt(System.currentTimeMillis());
        online.put(node.getNodeId(), node);
        quality.computeIfAbsent(node.getNodeId(), id -> new NodeQuality())
                .observeLoad(node.getLoadRatio());
    }

    public void offline(String nodeId) {
        online.remove(nodeId);
        quality.remove(nodeId);
    }

    /** 记录一次分配结果，更新节点成功率 EWMA。 */
    public void recordAllocResult(String nodeId, boolean success) {
        if (nodeId == null) {
            return;
        }
        quality.computeIfAbsent(nodeId, id -> new NodeQuality()).observeResult(success);
    }

    /** 返回仍在线（TTL 内）且 {@code status==ONLINE} 的节点快照（按复合分升序）。 */
    public List<RelayNode> onlineNodes() {
        long now = System.currentTimeMillis();
        return online.values().stream()
                .filter(n -> now - n.getLastHeartbeatAt() <= ttlMillis)
                .filter(n -> n.getStatus() == RelayNode.STATUS_ONLINE)
                .sorted(Comparator.comparingDouble(this::score))
                .toList();
    }

    /** 就近择优：优先 region 精确命中，其次全节点按复合分升序。无在线节点返回 {@code null}。 */
    public RelayNode bestFor(String region) {
        List<RelayNode> nodes = onlineNodes();
        if (nodes.isEmpty()) {
            return null;
        }
        if (region != null && !region.isBlank()) {
            return nodes.stream()
                    .filter(n -> region.equalsIgnoreCase(n.getRegion()))
                    .findFirst()
                    .orElse(nodes.get(0));
        }
        return nodes.get(0);
    }

    /** 复合调度分：负载与失败率加权，越低越优。 */
    private double score(RelayNode node) {
        NodeQuality q = quality.get(node.getNodeId());
        double load = q != null ? q.loadEwma : node.getLoadRatio();
        double failRate = q != null ? 1.0 - q.successEwma : 0.0;
        return LOAD_WEIGHT * load + QUALITY_WEIGHT * failRate;
    }

    /** 节点质量评分：对负载与分配成功率做 EWMA 平滑，抑制瞬时抖动。 */
    private static final class NodeQuality {
        volatile double loadEwma = 0.0;
        volatile double successEwma = INITIAL_SUCCESS_RATE;
        volatile boolean seeded;

        void observeLoad(double reportedLoad) {
            loadEwma = seeded ? EWMA_ALPHA * reportedLoad + (1 - EWMA_ALPHA) * loadEwma : reportedLoad;
            seeded = true;
        }

        void observeResult(boolean success) {
            double v = success ? 1.0 : 0.0;
            successEwma = EWMA_ALPHA * v + (1 - EWMA_ALPHA) * successEwma;
        }
    }
}
