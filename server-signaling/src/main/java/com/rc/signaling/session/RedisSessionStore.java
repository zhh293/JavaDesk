package com.rc.signaling.session;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Redis same-slot session metadata CAS implementation. */
@Component
@Profile("prod")
public final class RedisSessionStore implements SessionStore {
    private static final long ENDED_RETENTION_MS = 30 * 60 * 1000L;
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              if redis.call('HGET', KEYS[1], 'requestId') == ARGV[1] then return 0 end
              if redis.call('HGET', KEYS[1], 'controllerDeviceId') == ARGV[5] and
                 redis.call('HGET', KEYS[1], 'agentDeviceId') == ARGV[6] then return 0 end
              return -1
            end
            redis.call('HSET', KEYS[1], 'requestId', ARGV[1], 'sessionId', ARGV[2],
              'version', ARGV[3], 'state', ARGV[4], 'controllerDeviceId', ARGV[5],
              'agentDeviceId', ARGV[6], 'controllerConnectionEpoch', ARGV[7],
              'agentConnectionEpoch', ARGV[8], 'coordinatorNodeId', ARGV[9],
              'routeEpoch', ARGV[10], 'createdAt', ARGV[11], 'updatedAt', ARGV[12],
              'expiresAt', ARGV[13], 'endReason', '', 'endCode', '0')
            redis.call('PEXPIRE', KEYS[1], ARGV[14])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> TRANSITION = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'controllerDeviceId') ~= ARGV[1] and
               redis.call('HGET', KEYS[1], 'agentDeviceId') ~= ARGV[1] then return -2 end
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[2] or
               redis.call('HGET', KEYS[1], 'state') ~= ARGV[3] then return -3 end
            redis.call('HSET', KEYS[1], 'version', ARGV[4], 'state', ARGV[5], 'updatedAt', ARGV[6])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> UPDATE_EPOCH = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'state') == 'ENDED' then return -2 end
            if redis.call('HGET', KEYS[1], 'routeEpoch') ~= ARGV[1] then return -3 end
            redis.call('HSET', KEYS[1], 'routeEpoch', ARGV[2], 'version',
              tostring(tonumber(redis.call('HGET', KEYS[1], 'version')) + 1),
              'state', 'ACTIVE', 'updatedAt', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> END = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            if redis.call('HGET', KEYS[1], 'controllerDeviceId') ~= ARGV[1] and
               redis.call('HGET', KEYS[1], 'agentDeviceId') ~= ARGV[1] then return -2 end
            if redis.call('HGET', KEYS[1], 'state') == 'ENDED' then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'ENDED', 'version',
              tostring(tonumber(redis.call('HGET', KEYS[1], 'version')) + 1),
              'updatedAt', ARGV[2], 'endReason', ARGV[3], 'endCode', ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REFRESH_CONNECTION = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            local field
            if redis.call('HGET', KEYS[1], 'controllerDeviceId') == ARGV[1] then
              field = 'controllerConnectionEpoch'
            elseif redis.call('HGET', KEYS[1], 'agentDeviceId') == ARGV[1] then
              field = 'agentConnectionEpoch'
            else return -2 end
            if tonumber(redis.call('HGET', KEYS[1], field)) > tonumber(ARGV[2]) then return -3 end
            if redis.call('HGET', KEYS[1], field) == ARGV[2] then return 0 end
            redis.call('HSET', KEYS[1], field, ARGV[2], 'version',
              tostring(tonumber(redis.call('HGET', KEYS[1], 'version')) + 1), 'updatedAt', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public SessionMetadata create(SessionMetadata session, String requestId) {
        requireId(requestId);
        long ttl = Math.max(60_000, session.expiresAt() - System.currentTimeMillis());
        Long result = redis.execute(CREATE, List.of(key(session.sessionId())), requestId,
                Long.toString(session.sessionId()), Long.toString(session.version()), session.state().name(),
                Long.toString(session.controllerDeviceId()), Long.toString(session.agentDeviceId()),
                Long.toString(session.controllerConnectionEpoch()), Long.toString(session.agentConnectionEpoch()),
                session.coordinatorNodeId(), Long.toString(session.routeEpoch()),
                Long.toString(session.createdAt()), Long.toString(session.updatedAt()),
                Long.toString(session.expiresAt()), Long.toString(ttl));
        if (result == null || result < 0) throw new IllegalStateException("session create conflict");
        return find(session.sessionId()).orElseThrow();
    }

    @Override
    public Optional<SessionMetadata> find(long sessionId) {
        Map<Object, Object> h = redis.opsForHash().entries(key(sessionId));
        if (h.isEmpty()) return Optional.empty();
        return Optional.of(fromHash(h));
    }

    @Override
    public SessionMetadata transition(long sessionId, long expectedVersion, SessionState expectedState,
                                      SessionState nextState, long actorDeviceId) {
        if (!allowed(expectedState, nextState)) throw new IllegalStateException("illegal session transition");
        long now = System.currentTimeMillis();
        Long result = redis.execute(TRANSITION, List.of(key(sessionId)), Long.toString(actorDeviceId),
                Long.toString(expectedVersion), expectedState.name(), Long.toString(expectedVersion + 1),
                nextState.name(), Long.toString(now));
        check(result, "session transition");
        return find(sessionId).orElseThrow();
    }

    @Override
    public SessionMetadata updateRouteEpoch(long sessionId, long expectedRouteEpoch, long nextRouteEpoch) {
        if (nextRouteEpoch != expectedRouteEpoch + 1) throw new IllegalArgumentException("epoch must increment once");
        Long result = redis.execute(UPDATE_EPOCH, List.of(key(sessionId)),
                Long.toString(expectedRouteEpoch), Long.toString(nextRouteEpoch),
                Long.toString(System.currentTimeMillis()));
        check(result, "route epoch update");
        return find(sessionId).orElseThrow();
    }

    @Override
    public SessionMetadata refreshConnectionEpoch(long sessionId, long deviceId, long connectionEpoch) {
        Long result = redis.execute(REFRESH_CONNECTION, List.of(key(sessionId)), Long.toString(deviceId),
                Long.toString(connectionEpoch), Long.toString(System.currentTimeMillis()));
        check(result, "connection epoch refresh");
        return find(sessionId).orElseThrow();
    }

    @Override
    public SessionMetadata end(long sessionId, long actorDeviceId, String reason, int code) {
        Long result = redis.execute(END, List.of(key(sessionId)), Long.toString(actorDeviceId),
                Long.toString(System.currentTimeMillis()), reason == null ? "" : reason,
                Integer.toString(code), Long.toString(ENDED_RETENTION_MS));
        check(result, "session end");
        return find(sessionId).orElseThrow();
    }

    private static SessionMetadata fromHash(Map<Object, Object> h) {
        return new SessionMetadata(number(h, "sessionId"), number(h, "version"),
                SessionState.valueOf(text(h, "state")), number(h, "controllerDeviceId"),
                number(h, "agentDeviceId"), number(h, "controllerConnectionEpoch"),
                number(h, "agentConnectionEpoch"), text(h, "coordinatorNodeId"),
                number(h, "routeEpoch"), number(h, "createdAt"), number(h, "updatedAt"),
                number(h, "expiresAt"), emptyToNull(text(h, "endReason")),
                (int) number(h, "endCode"));
    }

    private static String key(long id) { return "rc:v2:session:{" + id + "}:meta"; }
    private static void check(Long result, String operation) {
        if (result == null || result < 0) throw new IllegalStateException(operation + " CAS failed: " + result);
    }
    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 128) throw new IllegalArgumentException("invalid request id");
    }
    private static String text(Map<Object, Object> h, String f) {
        Object value = h.get(f); return value == null ? "" : value.toString();
    }
    private static long number(Map<Object, Object> h, String f) { return Long.parseLong(text(h, f)); }
    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }
    private static boolean allowed(SessionState from, SessionState to) {
        return switch (from) {
            case INVITING -> to == SessionState.ACCEPTED || to == SessionState.ENDING || to == SessionState.ENDED;
            case ACCEPTED -> to == SessionState.NEGOTIATING || to == SessionState.ENDING || to == SessionState.ENDED;
            case NEGOTIATING -> to == SessionState.ACTIVE || to == SessionState.ENDING || to == SessionState.ENDED;
            case ACTIVE -> to == SessionState.ENDING || to == SessionState.ENDED;
            case ENDING -> to == SessionState.ENDED;
            case ENDED -> false;
        };
    }
}
