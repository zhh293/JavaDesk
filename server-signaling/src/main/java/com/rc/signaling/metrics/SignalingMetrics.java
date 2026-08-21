package com.rc.signaling.metrics;

import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.signaling.session.ConnectionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 信令节点指标注册：把本节点在线设备数登记为 gauge，交由 Spring Boot
 * actuator 的 Prometheus 端点（{@code /actuator/prometheus}）抓取，与 client / relay
 * 上报的指标汇入同一全局 QoS 大盘。
 *
 * <p>直接注入 Spring 复合 {@link MeterRegistry}（含 Prometheus），规避全局注册表在
 * {@code @PostConstruct} 时机尚未合并 Prometheus 注册表导致的指标丢失。本地连接数为 O(1)
 * 读取；会话权威数据位于共享 SessionStore，不能再从已删除的本机会话 Map 伪造集群指标。</p>
 */
@Component
public class SignalingMetrics {

    private final ConnectionRegistry connections;
    private final MeterRegistry meterRegistry;

    public SignalingMetrics(ConnectionRegistry connections, MeterRegistry meterRegistry) {
        this.connections = connections;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void register() {
        QosMetrics.gauge(meterRegistry, QosMetricNames.DEVICES_ONLINE, connections::size);
    }
}
