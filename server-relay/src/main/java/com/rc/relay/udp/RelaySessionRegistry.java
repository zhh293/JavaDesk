package com.rc.relay.udp;

import com.rc.common.model.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 中继会话表：{@code sessionId → (peerA, peerB, joinedAt)}。
 *
 * <p>每个会话最多两个端点。JOIN 幂等（同端点重复入会仅刷新时间），
 * 两端点就位后 DATA 报文即可对端透传。过期会话由定时器清理。</p>
 */
public final class RelaySessionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelaySessionRegistry.class);

    private record Session(Endpoint peerA, Endpoint peerB, long joinedAt) {
    }

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final long ttlSeconds;

    public RelaySessionRegistry(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-relay-sweep");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweep, ttlSeconds, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 端点入会。会话未满则登记并返回 {@code true}；已有两个不同端点时拒绝（{@code false}）。
     * 同一端点重复入会视为幂等刷新。
     */
    public boolean join(long sessionId, Endpoint src) {
        long now = System.currentTimeMillis();
        final boolean[] accepted = {false};
        sessions.compute(sessionId, (id, s) -> {
            if (s == null) {
                accepted[0] = true;
                return new Session(src, null, now);
            }
            if (src.equals(s.peerA) || src.equals(s.peerB)) {
                accepted[0] = true;
                return new Session(s.peerA, s.peerB, now);
            }
            if (s.peerB == null) {
                accepted[0] = true;
                return new Session(s.peerA, src, now);
            }
            accepted[0] = false;
            return s;
        });
        return accepted[0];
    }

    /** 返回对端端点；源不在会话中或对端未就位返回 {@code null}。 */
    public Endpoint peerOf(long sessionId, Endpoint src) {
        Session s = sessions.get(sessionId);
        if (s == null) {
            return null;
        }
        if (src.equals(s.peerA)) {
            return s.peerB;
        }
        if (src.equals(s.peerB)) {
            return s.peerA;
        }
        return null;
    }

    public void remove(long sessionId) {
        sessions.remove(sessionId);
    }

    /** 当前活跃会话数（负载指标）。 */
    public int size() {
        return sessions.size();
    }

    private void sweep() {
        long cutoff = System.currentTimeMillis() - ttlSeconds * 1000L;
        Iterator<Map.Entry<Long, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Session> e = it.next();
            if (e.getValue().joinedAt() < cutoff) {
                it.remove();
                log.debug("relay session swept: sessionId={}", e.getKey());
            }
        }
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        sessions.clear();
    }
}
