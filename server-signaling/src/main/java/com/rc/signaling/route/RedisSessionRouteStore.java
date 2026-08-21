package com.rc.signaling.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.common.crypto.RelayTicketV2.PeerRole;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Redis Lua route state machine; one session hash keeps all CAS fields in one cluster slot. */
@Component
@Profile("prod")
public final class RedisSessionRouteStore implements SessionRouteStore {
    private static final long ROUTE_TTL_MS = 30 * 60 * 1000L;
    private static final DefaultRedisScript<String> PREPARE = new DefaultRedisScript<>("""
            local dedupe = redis.call('HGET', KEYS[1], 'switch:' .. ARGV[1])
            if dedupe then return 'O|' .. dedupe end
            local preparing = redis.call('HGET', KEYS[1], 'preparing')
            if preparing then return 'M|' .. preparing end
            local currentEpoch = redis.call('HGET', KEYS[1], 'committedEpoch') or '0'
            if currentEpoch ~= ARGV[2] then
              return 'S|' .. (redis.call('HGET', KEYS[1], 'committed') or '')
            end
            redis.call('HSET', KEYS[1], 'preparing', ARGV[3], 'preparingEpoch', ARGV[4],
              'preparingAssignment', ARGV[5], 'preparingRequest', ARGV[1],
              'controllerReady', '0', 'agentReady', '0')
            redis.call('HSET', KEYS[1], 'switch:' .. ARGV[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            return 'O|' .. ARGV[3]
            """, String.class);
    private static final DefaultRedisScript<String> READY = new DefaultRedisScript<>("""
            local prior = redis.call('HGET', KEYS[1], 'ready:' .. ARGV[3])
            if prior then
              if redis.call('HGET', KEYS[1], 'committedEpoch') == ARGV[1] and
                 redis.call('HGET', KEYS[1], 'committedAssignment') == ARGV[2] then return 'C' end
              return prior
            end
            if redis.call('HGET', KEYS[1], 'preparingEpoch') ~= ARGV[1] or
               redis.call('HGET', KEYS[1], 'preparingAssignment') ~= ARGV[2] then return 'S' end
            if ARGV[4] == 'CONTROLLER' then redis.call('HSET', KEYS[1], 'controllerReady', '1')
            else redis.call('HSET', KEYS[1], 'agentReady', '1') end
            local result = 'P'
            if redis.call('HGET', KEYS[1], 'controllerReady') == '1' and
               redis.call('HGET', KEYS[1], 'agentReady') == '1' then
              local route = redis.call('HGET', KEYS[1], 'preparing')
              redis.call('HSET', KEYS[1], 'committed', route, 'committedEpoch', ARGV[1],
                'committedAssignment', ARGV[2], 'commitAt', ARGV[5])
              redis.call('HDEL', KEYS[1], 'preparing', 'preparingEpoch', 'preparingAssignment',
                'preparingRequest', 'controllerReady', 'agentReady')
              result = 'C'
            end
            redis.call('HSET', KEYS[1], 'ready:' .. ARGV[3], result)
            return result
            """, String.class);
    private static final DefaultRedisScript<String> ABORT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'preparingEpoch') ~= ARGV[1] or
               redis.call('HGET', KEYS[1], 'preparingAssignment') ~= ARGV[2] then return 'S|' end
            local route = redis.call('HGET', KEYS[1], 'preparing')
            local request = redis.call('HGET', KEYS[1], 'preparingRequest')
            redis.call('HDEL', KEYS[1], 'preparing', 'preparingEpoch', 'preparingAssignment',
              'preparingRequest', 'controllerReady', 'agentReady')
            redis.call('HSET', KEYS[1], 'abortedReason', ARGV[3])
            if request then redis.call('HSET', KEYS[1], 'switch:' .. request, route) end
            return 'O|' .. route
            """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisSessionRouteStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public SessionRoute prepare(SessionRoute proposal) {
        if (proposal.state() != RouteState.PREPARING || proposal.prepareDeadlineAt() <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("invalid preparing route");
        }
        String result = redis.execute(PREPARE, List.of(key(proposal.sessionId())),
                proposal.switchRequestId(), Long.toString(proposal.baseEpoch()), json(proposal),
                Long.toString(proposal.routeEpoch()), proposal.assignmentId(),
                Long.toString(Math.max(ROUTE_TTL_MS,
                        proposal.prepareDeadlineAt() - System.currentTimeMillis() + 60_000)));
        if (result == null || result.length() < 2) throw new IllegalStateException("Redis route prepare failed");
        String payload = result.substring(2);
        return switch (result.charAt(0)) {
            case 'O' -> parse(payload);
            case 'M' -> throw new RouteConflictException(RouteConflictException.Reason.MIGRATION_IN_PROGRESS, parse(payload));
            case 'S' -> throw new RouteConflictException(RouteConflictException.Reason.STALE_EPOCH,
                    payload.isEmpty() ? null : committed(parse(payload), 0));
            default -> throw new IllegalStateException("unknown Redis route response");
        };
    }

    @Override
    public SessionRoute markReady(long sessionId, long routeEpoch, String assignmentId,
                                  String requestId, PeerRole role) {
        String result = redis.execute(READY, List.of(key(sessionId)), Long.toString(routeEpoch),
                assignmentId, requestId, role.name(), Long.toString(System.currentTimeMillis()));
        if ("S".equals(result)) {
            throw new RouteConflictException(RouteConflictException.Reason.STALE_EPOCH,
                    snapshot(sessionId).committed());
        }
        SessionRouteSnapshot snapshot = snapshot(sessionId);
        SessionRoute route = "C".equals(result) ? snapshot.committed() : snapshot.preparing();
        if (route == null) throw new IllegalStateException("Redis ready result has no route");
        return route;
    }

    @Override
    public SessionRoute abort(long sessionId, long routeEpoch, String assignmentId, String reason) {
        String result = redis.execute(ABORT, List.of(key(sessionId)), Long.toString(routeEpoch),
                assignmentId, reason == null ? "aborted" : reason);
        if (result == null || result.startsWith("S|")) {
            throw new RouteConflictException(RouteConflictException.Reason.STALE_EPOCH,
                    snapshot(sessionId).committed());
        }
        SessionRoute route = parse(result.substring(2));
        return new SessionRoute(route.sessionId(), route.routeEpoch(), RouteState.ABORTED, route.pathType(),
                route.relayNodeId(), route.relayHost(), route.relayPort(), route.tls(),
                route.switchRequestId(), route.assignmentId(), route.baseEpoch(), route.controllerReady(),
                route.agentReady(), route.prepareDeadlineAt(), 0, reason, route.excludedRelayNodeIds());
    }

    @Override
    public Optional<SessionRoute> expirePreparation(long sessionId, long nowMillis) {
        SessionRoute route = snapshot(sessionId).preparing();
        if (route == null || route.prepareDeadlineAt() > nowMillis) return Optional.empty();
        try {
            return Optional.of(abort(sessionId, route.routeEpoch(), route.assignmentId(), "prepare timeout"));
        } catch (RouteConflictException raced) {
            return Optional.empty();
        }
    }

    @Override
    public SessionRouteSnapshot snapshot(long sessionId) {
        Map<Object, Object> h = redis.opsForHash().entries(key(sessionId));
        SessionRoute committed = parseNullable(h.get("committed"));
        if (committed != null) committed = committed(committed, number(h.get("commitAt")));
        SessionRoute preparing = parseNullable(h.get("preparing"));
        if (preparing != null) {
            preparing = new SessionRoute(preparing.sessionId(), preparing.routeEpoch(), RouteState.PREPARING,
                    preparing.pathType(), preparing.relayNodeId(), preparing.relayHost(), preparing.relayPort(),
                    preparing.tls(), preparing.switchRequestId(), preparing.assignmentId(), preparing.baseEpoch(),
                    "1".equals(string(h.get("controllerReady"))), "1".equals(string(h.get("agentReady"))),
                    preparing.prepareDeadlineAt(), 0, null, preparing.excludedRelayNodeIds());
        }
        return new SessionRouteSnapshot(committed, preparing);
    }

    private SessionRoute committed(SessionRoute route, long commitAt) {
        return new SessionRoute(route.sessionId(), route.routeEpoch(), RouteState.COMMITTED, route.pathType(),
                route.relayNodeId(), route.relayHost(), route.relayPort(), route.tls(), route.switchRequestId(),
                route.assignmentId(), route.baseEpoch(), true, true, route.prepareDeadlineAt(), commitAt,
                null, route.excludedRelayNodeIds());
    }

    private String json(SessionRoute route) {
        try { return mapper.writeValueAsString(route); }
        catch (JsonProcessingException e) { throw new IllegalStateException("route serialization failed", e); }
    }
    private SessionRoute parse(String json) {
        try { return mapper.readValue(json, SessionRoute.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("route data is corrupt", e); }
    }
    private SessionRoute parseNullable(Object value) {
        String text = string(value); return text.isEmpty() ? null : parse(text);
    }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static long number(Object value) { String s = string(value); return s.isEmpty() ? 0 : Long.parseLong(s); }
    private static String key(long id) { return "rc:v2:session:{" + id + "}:routes"; }
}
