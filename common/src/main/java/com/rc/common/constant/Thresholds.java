package com.rc.common.constant;

/**
 * 工程参数门限（超时 / 重试 / 降级 / 回切），对应设计文档 §3.13 与 §7.3。
 *
 * <p>单位：时间 ms，比例按百分比（0~100）。关键阈值应支持服务端热更新，
 * 这里仅给出默认基线，实际以配置中心下发的值为准。</p>
 */
public final class Thresholds {

    // ---------- 探测超时 ----------
    /** 宽带探测基线 */
    public static final long PROBE_BASE_BROADBAND_MS = 800L;
    /** 移动网络探测基线 */
    public static final long PROBE_BASE_MOBILE_MS = 1200L;
    /** 探测超时下界 */
    public static final long PROBE_TIMEOUT_MIN_MS = 600L;
    /** 探测超时上界 */
    public static final long PROBE_TIMEOUT_MAX_MS = 2000L;
    /** 首次建链总预算（交互型远控建议 ≤ 4s） */
    public static final long CONNECT_BUDGET_MS = 4000L;
    /** 候选收集窗口 */
    public static final long CANDIDATE_GATHER_WINDOW_MS = 1000L;

    // ---------- 重试 ----------
    /** 打洞重试：每候选对次数 */
    public static final int PUNCH_RETRY_COUNT = 8;
    /** 打洞重试：间隔 */
    public static final long PUNCH_RETRY_INTERVAL_MS = 100L;
    /** 连通性检查：每候选对次数 */
    public static final int CHECK_RETRY_COUNT = 3;
    /** 信令重发次数（幂等消息 ID） */
    public static final int SIGNAL_RESEND_COUNT = 2;
    /** 重连退避上限 */
    public static final long RECONNECT_BACKOFF_MAX_MS = 8000L;
    /** 重连总预算 */
    public static final long RECONNECT_BUDGET_MS = 30000L;

    // ---------- 心跳 / 保活 ----------
    /** 信令心跳周期 */
    public static final long HEARTBEAT_INTERVAL_MS = 15000L;
    /** 连续心跳丢失判定断线 */
    public static final int HEARTBEAT_LOST_LIMIT = 3;
    /** NAT 保活间隔（家庭 / 公网） */
    public static final long KEEPALIVE_HOME_MS = 20000L;
    /** NAT 保活间隔（企业网） */
    public static final long KEEPALIVE_ENTERPRISE_MS = 15000L;
    /** NAT 保活间隔（移动网络） */
    public static final long KEEPALIVE_MOBILE_MS = 10000L;
    /** 保活间隔随机抖动比例（±20%，防保活风暴） */
    public static final double KEEPALIVE_JITTER_RATIO = 0.20;

    // ---------- 降级硬门限（立即降级） ----------
    /** 连续保活周期无响应 */
    public static final int KEEPALIVE_LOST_HARD_LIMIT = 3;

    // ---------- 降级软门限（持续劣化才降级） ----------
    /** 丢包率阈值（%） */
    public static final double LOSS_RATE_THRESHOLD = 15.0;
    /** 丢包率持续时长 */
    public static final long LOSS_RATE_DURATION_MS = 3000L;
    /** RTT 阈值 */
    public static final long RTT_THRESHOLD_MS = 350L;
    /** RTT 持续时长 */
    public static final long RTT_DURATION_MS = 5000L;
    /** 卡顿率（冻结帧占比）阈值（%） */
    public static final double FREEZE_RATE_THRESHOLD = 20.0;
    /** 卡顿率持续时长 */
    public static final long FREEZE_DURATION_MS = 3000L;

    // ---------- QoS 智能阈值（EWMA 基线 + k·σ 动态门限） ----------
    /** QoS 探测（ping）周期：心跳 echo 测量 RTT / 丢包的节奏 */
    public static final long QOS_PROBE_INTERVAL_MS = 5000L;
    /** 单次 ping 超时判定丢失 */
    public static final long QOS_PROBE_TIMEOUT_MS = 5000L;
    /** 滑动窗口样本数（RTT / 丢包 / 静默间隔） */
    public static final int QOS_WINDOW_SIZE = 32;
    /** EWMA 平滑系数（基线学习，越小越稳） */
    public static final double QOS_EWMA_ALPHA = 0.2;
    /** 动态门限偏离倍数 k（threshold = baseline + k·σ） */
    public static final double QOS_SIGMA_K = 3.0;

    // ---------- 回切（Relay → P2P） ----------
    /** 中继最短驻留（防抖期） */
    public static final long RELAY_DWELL_MS = 30000L;
    /** 后台 P2P 探测连续成功次数 */
    public static final int P2P_PROBE_SUCCESS_LIMIT = 3;
    /** 回切质量提升阈值（时延下降比例，%） */
    public static final double P2P_IMPROVEMENT_RATIO = 20.0;

    // ---------- 令牌 ----------
    /** 会话令牌 TTL（秒，一次性使用） */
    public static final long SESSION_TOKEN_TTL_SECONDS = 60L;

    private Thresholds() {
    }
}
