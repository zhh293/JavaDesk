package com.rc.common.metrics;

/**
 * QoS 指标名常量（Prometheus 命名约定：小写 + 下划线，累积量以 {@code _total} 结尾）。
 *
 * <p>统一在此声明，避免 client / relay / signaling 三端各自拼字符串导致指标口径漂移，
 * 大盘（Grafana）按这些名字建立告警与面板。</p>
 */
public final class QosMetricNames {

    private QosMetricNames() {
    }

    // ---------- 会话 / 设备 / 路径 ----------

    /** 当前活跃会话数（gauge）。 */
    public static final String SESSIONS_ACTIVE = "rc_sessions_active";
    /** 累计会话总数（counter）。 */
    public static final String SESSIONS_TOTAL = "rc_sessions_total";
    /** 当前在线设备数（gauge）。 */
    public static final String DEVICES_ONLINE = "rc_devices_online";
    /** 当前数据面路径分布（gauge，tag {@code path}=P2P/RELAY_UDP/RELAY_TCP/RELAY_WS）。 */
    public static final String PATHS_CURRENT = "rc_paths_current";

    // ---------- 数据面 QoS ----------

    /** 累计接收字节（counter）。 */
    public static final String BYTES_RX_TOTAL = "rc_bytes_rx_total";
    /** 累计发送字节（counter）。 */
    public static final String BYTES_TX_TOTAL = "rc_bytes_tx_total";
    /** 累计接收帧数（counter）。 */
    public static final String PACKETS_RX_TOTAL = "rc_packets_rx_total";
    /** 累计发送帧数（counter）。 */
    public static final String PACKETS_TX_TOTAL = "rc_packets_tx_total";
    /** 数据面往返时延（gauge，ms，EWMA 当前值）。 */
    public static final String RTT_MS = "rc_rtt_ms";
    /** 数据面 RTT 动态基线（gauge，ms，EWMA 均值）。 */
    public static final String RTT_BASELINE_MS = "rc_rtt_baseline_ms";
    /** 数据面 RTT 波动（gauge，ms，滑动窗口 σ）。 */
    public static final String RTT_SIGMA_MS = "rc_rtt_sigma_ms";
    /** 数据面丢包率（gauge，0~1，滑动窗口）。 */
    public static final String PACKET_LOSS_RATIO = "rc_packet_loss_ratio";
    /** 静默间隔当前值（gauge，ms，距上次健康应答）。 */
    public static final String SILENCE_CURRENT_MS = "rc_silence_current_ms";
    /** 静默动态门限（gauge，ms，baseline + k·σ 截断到 [floor, ceiling]）。 */
    public static final String SILENCE_THRESHOLD_MS = "rc_silence_threshold_ms";

    // ---------- 打洞 / 降级 / 中继 ----------

    /** 累计打洞尝试次数（counter）。 */
    public static final String PUNCH_ATTEMPTS_TOTAL = "rc_punch_attempts_total";
    /** 累计打洞成功次数（counter）。 */
    public static final String PUNCH_SUCCESS_TOTAL = "rc_punch_success_total";
    /** 累计降级事件次数（counter，tag {@code from} → {@code to}）。 */
    public static final String DEGRADE_EVENTS_TOTAL = "rc_degrade_events_total";
    /** 累计保活丢失次数（counter）。 */
    public static final String KEEPALIVE_LOST_TOTAL = "rc_keepalive_lost_total";
    /** 累计中继 JOIN 次数（counter）。 */
    public static final String RELAY_JOIN_TOTAL = "rc_relay_join_total";
    /** 累计中继 DATA 转发次数（counter）。 */
    public static final String RELAY_DATA_TOTAL = "rc_relay_data_total";
    /** 中继当前活跃会话数（gauge）。 */
    public static final String RELAY_SESSIONS_ACTIVE = "rc_relay_sessions_active";
    /** 累计中继分配次数（counter，tag {@code path}）。 */
    public static final String RELAY_ALLOC_TOTAL = "rc_relay_alloc_total";
    /** 累计回切（Relay→P2P make-before-break）成功提交次数（counter）。 */
    public static final String SWITCHBACK_TOTAL = "rc_switchback_total";
    /** 累计回切探测中止次数（counter）。 */
    public static final String SWITCHBACK_ABORT_TOTAL = "rc_switchback_abort_total";
}
