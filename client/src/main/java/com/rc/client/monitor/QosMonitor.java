package com.rc.client.monitor;

import com.rc.client.transport.TransportChannel;
import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import com.rc.common.constant.ProtocolConstants;
import com.rc.common.constant.Thresholds;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据面 QoS 监测器（智能阈值版）。
 *
 * <p>以 {@link TransportListener} 身份挂在 {@link TransportChannel} 上，通过在 CONTROL 通道
 * 发送 <b>PING/PONG 心跳 echo</b> 真实测量往返时延（RTT）与丢包率，而非被动等待业务流量。
 * 测量结果经 {@link QosMetrics} 上报 Prometheus（RTT 当前值/基线/波动、丢包率、静默间隔），
 * 供全局大盘区分「真劣化」与「正常波动」。</p>
 *
 * <p><b>降级判定（把绝对红线换成相对基线的离群判断）：</b></p>
 * <ol>
 *   <li><b>静默硬门限（动态）</b>：对「健康应答（PONG）间隔」建立 EWMA 基线 + 滑动窗口 σ，
 *       门限 = {@code baseline + k·σ}，截断到 {@code [KEEPALIVE_HOME_MS, KEEPALIVE_LOST_HARD_LIMIT×KEEPALIVE_HOME_MS]}。
 *       链路正常时基线≈探测周期，劣化抖动时门限随 σ 自适应上浮；硬上限兜底防基线漂移掩盖断链。</li>
 *   <li><b>软门限</b>：丢包率滑动窗口持续超过 {@link Thresholds#LOSS_RATE_THRESHOLD} 达
 *       {@link Thresholds#LOSS_RATE_DURATION_MS}，判定持续劣化，同样触发降级。</li>
 *   <li><b>传输层关闭</b>：通道 close/reset 立即降级。</li>
 * </ol>
 */
public final class QosMonitor implements TransportListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QosMonitor.class);

    // ---------- PING/PONG echo 协议（CONTROL 通道 payload 内嵌 magic 前缀） ----------
    private static final byte MAGIC_0 = 0x52; // 'R'
    private static final byte MAGIC_1 = 0x51; // 'Q'
    private static final byte TYPE_PING = 0x01;
    private static final byte TYPE_PONG = 0x02;
    private static final int ECHO_LEN = 15;   // magic(2) + type(1) + seq(4) + ts(8)

    private record Echo(byte type, int seq, long ts) {
    }

    private final long sessionId;
    private final TransportChannel channel;
    private final QosListener listener;
    private final String path;
    private final String[] gaugeTags;
    private final ScheduledExecutorService timer;
    private final AtomicInteger seqGen = new AtomicInteger();
    private final Map<Integer, Long> pendingPings = new ConcurrentHashMap<>();
    private final AtomicInteger consecutiveHealthy = new AtomicInteger();

    private final Baseline rttBaseline = new Baseline(Thresholds.QOS_WINDOW_SIZE, Thresholds.QOS_EWMA_ALPHA);
    private final Baseline gapBaseline = new Baseline(Thresholds.QOS_WINDOW_SIZE, Thresholds.QOS_EWMA_ALPHA);
    private final ArrayDeque<Boolean> lossWindow = new ArrayDeque<>();

    private volatile long lastPongTime = System.currentTimeMillis();
    private volatile double rttMs;
    private volatile double lossRatio;
    private volatile double silenceCurrentMs;
    private volatile double silenceThresholdMs;
    private volatile long lossAboveSinceMs;
    private volatile boolean closed;

    public QosMonitor(long sessionId, TransportChannel channel, QosListener listener) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.listener = listener;
        this.path = channel.info() == null ? "UNKNOWN" : channel.info().getPathType().name();
        this.gaugeTags = new String[]{"path", path, "session", String.valueOf(sessionId)};
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-qos");
            t.setDaemon(true);
            return t;
        });
        channel.addListener(this);
        registerMetrics();
        timer.scheduleAtFixedRate(this::tick,
                Thresholds.QOS_PROBE_INTERVAL_MS, Thresholds.QOS_PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void registerMetrics() {
        QosMetrics.gauge(QosMetricNames.RTT_MS, () -> rttMs, gaugeTags);
        QosMetrics.gauge(QosMetricNames.RTT_BASELINE_MS, () -> rttBaseline.mean(), gaugeTags);
        QosMetrics.gauge(QosMetricNames.RTT_SIGMA_MS, () -> rttBaseline.sigma(), gaugeTags);
        QosMetrics.gauge(QosMetricNames.PACKET_LOSS_RATIO, () -> lossRatio, gaugeTags);
        QosMetrics.gauge(QosMetricNames.SILENCE_CURRENT_MS, () -> silenceCurrentMs, gaugeTags);
        QosMetrics.gauge(QosMetricNames.SILENCE_THRESHOLD_MS, () -> silenceThresholdMs, gaugeTags);
    }

    @Override
    public void onData(DataFrame frame) {
        long now = System.currentTimeMillis();
        QosMetrics.increment(QosMetricNames.PACKETS_RX_TOTAL, "path", path);
        QosMetrics.increment(QosMetricNames.BYTES_RX_TOTAL,
                ProtocolConstants.DATA_FRAME_HEADER_SIZE + frame.payloadLength(), "path", path);
        if (frame.channel() != ChannelType.CONTROL) {
            return;
        }
        Echo echo = decodeEcho(frame.payload());
        if (echo == null) {
            return;
        }
        if (echo.type() == TYPE_PING) {
            channel.send(ChannelType.CONTROL, encodeEcho(TYPE_PONG, echo.seq(), echo.ts()));
        } else if (echo.type() == TYPE_PONG) {
            onPong(echo, now);
        }
    }

    private void onPong(Echo echo, long now) {
        Long sent = pendingPings.remove(echo.seq());
        if (sent == null) {
            return;
        }
        long rtt = now - sent;
        rttMs = rtt;
        rttBaseline.sample(rtt);
        recordLoss(false);
        long ceiling = Thresholds.KEEPALIVE_LOST_HARD_LIMIT * Thresholds.KEEPALIVE_HOME_MS;
        long gap = now - lastPongTime;
        if (gap > 0 && gap < ceiling) {
            // 仅在健康窗口内学习基线，避免断链恢复后的长间隔污染「正常」基线
            gapBaseline.sample(gap);
        }
        lastPongTime = now;
        listener.onProbeHealthy(consecutiveHealthy.incrementAndGet());
    }

    @Override
    public void onClosed(Throwable cause) {
        if (!closed) {
            closed = true;
            log.warn("transport closed, triggering degrade");
            listener.onTransportClosed();
        }
    }

    /** 周期 tick：发 ping → 清扫超时 ping 计丢包 → 动态静默门限 + 软门限判定。 */
    private void tick() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        int seq = seqGen.incrementAndGet();
        pendingPings.put(seq, now);
        channel.send(ChannelType.CONTROL, encodeEcho(TYPE_PING, seq, now));

        Iterator<Map.Entry<Integer, Long>> it = pendingPings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (now - e.getValue() > Thresholds.QOS_PROBE_TIMEOUT_MS) {
                it.remove();
                recordLoss(true);
                consecutiveHealthy.set(0);
            }
        }
        lossRatio = computeLoss();
        checkSoftLoss(now);
        checkSilence(now);
    }

    /** 软门限：丢包率持续超过阈值达时长 → 降级。 */
    private void checkSoftLoss(long now) {
        double threshold = Thresholds.LOSS_RATE_THRESHOLD / 100.0;
        if (lossRatio > threshold) {
            if (lossAboveSinceMs == 0) {
                lossAboveSinceMs = now;
            } else if (now - lossAboveSinceMs >= Thresholds.LOSS_RATE_DURATION_MS) {
                closed = true;
                QosMetrics.increment(QosMetricNames.KEEPALIVE_LOST_TOTAL, "path", path);
                log.warn("sustained packet loss {}% triggers degrade", String.format("%.2f", lossRatio * 100));
                listener.onKeepaliveLost();
            }
        } else {
            lossAboveSinceMs = 0;
        }
    }

    /** 静默硬门限（动态）：无健康应答超过 baseline + k·σ（截断到 [floor, ceiling]）→ 降级。 */
    private void checkSilence(long now) {
        long floor = Thresholds.KEEPALIVE_HOME_MS;
        long ceiling = Thresholds.KEEPALIVE_LOST_HARD_LIMIT * Thresholds.KEEPALIVE_HOME_MS;
        double dynamic = gapBaseline.mean() + Thresholds.QOS_SIGMA_K * gapBaseline.sigma();
        long threshold = (long) Math.min(ceiling, Math.max(floor, dynamic));
        long gap = now - lastPongTime;
        silenceCurrentMs = gap;
        silenceThresholdMs = threshold;
        if (gap > threshold) {
            closed = true;
            QosMetrics.increment(QosMetricNames.KEEPALIVE_LOST_TOTAL, "path", path);
            log.warn("silence {}ms exceeds dynamic threshold {}ms (baseline={} sigma={}), degrading",
                    gap, threshold, gapBaseline.mean(), gapBaseline.sigma());
            listener.onKeepaliveLost();
        }
    }

    private void recordLoss(boolean lost) {
        lossWindow.addLast(lost);
        if (lossWindow.size() > Thresholds.QOS_WINDOW_SIZE) {
            lossWindow.removeFirst();
        }
    }

    private double computeLoss() {
        if (lossWindow.isEmpty()) {
            return 0.0;
        }
        int lost = 0;
        for (boolean l : lossWindow) {
            if (l) {
                lost++;
            }
        }
        return (double) lost / lossWindow.size();
    }

    /** 最近一次成功探测的 RTT（ms），供回切质量对比使用。 */
    public long currentRttMs() {
        return (long) rttMs;
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
        channel.removeListener(this);
        pendingPings.clear();
    }

    // ---------- echo 编解码 ----------

    private static byte[] encodeEcho(byte type, int seq, long ts) {
        ByteBuffer b = ByteBuffer.allocate(ECHO_LEN);
        b.put(MAGIC_0).put(MAGIC_1).put(type).putInt(seq).putLong(ts);
        return b.array();
    }

    private static Echo decodeEcho(byte[] payload) {
        if (payload == null || payload.length != ECHO_LEN) {
            return null;
        }
        if (payload[0] != MAGIC_0 || payload[1] != MAGIC_1) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(payload);
        b.get();
        b.get();
        byte type = b.get();
        if (type != TYPE_PING && type != TYPE_PONG) {
            return null;
        }
        return new Echo(type, b.getInt(), b.getLong());
    }

    /**
     * 动态基线：EWMA 均值（长期「正常」水平）+ 滑动窗口 σ（近期波动）。
     * {@code threshold = mean + k·σ}，k 见 {@link Thresholds#QOS_SIGMA_K}。
     */
    private static final class Baseline {
        private final int capacity;
        private final double alpha;
        private final ArrayDeque<Double> window = new ArrayDeque<>();
        private double ewma;
        private boolean initialized;

        Baseline(int capacity, double alpha) {
            this.capacity = capacity;
            this.alpha = alpha;
        }

        void sample(double v) {
            if (!initialized) {
                ewma = v;
                initialized = true;
            } else {
                ewma = alpha * v + (1 - alpha) * ewma;
            }
            window.addLast(v);
            if (window.size() > capacity) {
                window.removeFirst();
            }
        }

        double mean() {
            return ewma;
        }

        double sigma() {
            if (window.size() < 2) {
                return 0.0;
            }
            double m = 0.0;
            for (double v : window) {
                m += v;
            }
            m /= window.size();
            double acc = 0.0;
            for (double v : window) {
                double d = v - m;
                acc += d * d;
            }
            return Math.sqrt(acc / window.size());
        }
    }
}
