package com.rc.client.monitor;

/**
 * QoS 硬门限降级事件回调（由 {@link QosMonitor} 上报给会话编排层）。
 */
public interface QosListener {

    /** 连续保活周期无入站帧（对端失联），应立即降级。 */
    void onKeepaliveLost();

    /** 传输层通道关闭（close / reset），应立即降级。 */
    void onTransportClosed();

    /**
     * 每完成一轮健康探测（PING 得到 PONG）上报一次，{@code consecutive} 为连续健康轮数。
     * 供回切（Relay→P2P make-before-break）验证新路径稳定性，默认空实现。
     */
    default void onProbeHealthy(int consecutive) {
    }
}
