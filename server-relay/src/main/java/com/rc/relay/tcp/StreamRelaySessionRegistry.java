package com.rc.relay.tcp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 面向连接（TCP / WS）的中继会话表：{@code sessionId → (peerA, peerB, joinedAt)}。
 *
 * <p>与 UDP 版 {@code RelaySessionRegistry} 不同，本表以 {@link Channel} 为端点（每条
 * 连接独占一个会话席位）。维护 {@code channelId → sessionId} 反查，连接断开时自动释放
 * 席位。每个会话最多两个端点，JOIN 幂等，过期会话由定时器清理。</p>
 */
public final class StreamRelaySessionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamRelaySessionRegistry.class);

    private record Session(Channel peerA, Channel peerB, long joinedAt) {
    }

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Map<ChannelId, Long> membership = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final long ttlSeconds;

    public StreamRelaySessionRegistry(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-relay-stream-sweep");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweep, ttlSeconds, ttlSeconds, TimeUnit.SECONDS);
    }

    /** 连接入会。会话未满登记并返回 {@code true}；已有两个不同连接时拒绝。 */
    public boolean join(long sessionId, Channel ch) {
        long now = System.currentTimeMillis();
        boolean[] accepted = {false};
        sessions.compute(sessionId, (id, s) -> {
            if (s == null) {
                accepted[0] = true;
                return new Session(ch, null, now);
            }
            if (ch.equals(s.peerA) || ch.equals(s.peerB)) {
                accepted[0] = true;
                return new Session(s.peerA, s.peerB, now);
            }
            if (s.peerB == null) {
                accepted[0] = true;
                return new Session(s.peerA, ch, now);
            }
            accepted[0] = false;
            return s;
        });
        if (accepted[0]) {
            membership.put(ch.id(), sessionId);
        }
        return accepted[0];
    }

    /** 返回对端连接；源不在会话中或对端未就位返回 {@code null}。 */
    public Channel peerOf(long sessionId, Channel ch) {
        Session s = sessions.get(sessionId);
        if (s == null) {
            return null;
        }
        if (ch.equals(s.peerA)) {
            return s.peerB;
        }
        if (ch.equals(s.peerB)) {
            return s.peerA;
        }
        return null;
    }

    /** 连接断开时释放其席位；两端均断开则移除会话。 */
    public void remove(Channel ch) {
        Long sessionId = membership.remove(ch.id());
        if (sessionId == null) {
            return;
        }
        sessions.computeIfPresent(sessionId, (id, s) -> {
            Channel a = ch.equals(s.peerA) ? null : s.peerA;
            Channel b = ch.equals(s.peerB) ? null : s.peerB;
            if (a == null && b == null) {
                return null;
            }
            return new Session(a, b, s.joinedAt());
        });
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
                log.debug("stream relay session swept: sessionId={}", e.getKey());
            }
        }
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        sessions.clear();
        membership.clear();
    }
}
