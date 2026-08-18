package com.rc.signaling.metrics;

import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.signaling.session.ConnectionRegistry;
import com.rc.signaling.session.SessionManager;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 信令节点指标注册：把本节点在线设备数、活跃会话数登记为 gauge，交由 Spring Boot
 * actuator 的 Prometheus 端点（{@code /actuator/prometheus}）抓取，与 client / relay
 * 上报的指标汇入同一全局 QoS 大盘。
 *
 * <p>直接注入 Spring 复合 {@link MeterRegistry}（含 Prometheus），规避全局注册表在
 * {@code @PostConstruct} 时机尚未合并 Prometheus 注册表导致的指标丢失。两者均为内存 Map
 * 的 O(1) 尺寸读取，无 DB/Redis 往返，避免高频抓取放大开销；集群化后由中心聚合层求和。</p>
 */
@Component
public class SignalingMetrics {

    private final ConnectionRegistry connections;
    private final SessionManager sessionManager;
    private final MeterRegistry meterRegistry;

    public SignalingMetrics(ConnectionRegistry connections, SessionManager sessionManager,
                            MeterRegistry meterRegistry) {
        this.connections = connections;
        this.sessionManager = sessionManager;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void register() {
        QosMetrics.gauge(meterRegistry, QosMetricNames.DEVICES_ONLINE, connections::size);
        QosMetrics.gauge(meterRegistry, QosMetricNames.SESSIONS_ACTIVE, sessionManager::size);
    }
}
