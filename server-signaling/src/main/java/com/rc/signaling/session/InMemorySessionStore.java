package com.rc.signaling.session;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Deterministic CAS implementation; production can replace it with a same-slot Redis Lua store. */
@Component
@Profile("!prod")
public final class InMemorySessionStore implements SessionStore {
    private final Map<Long, SessionMetadata> sessions = new HashMap<>();
    private final Map<String, Long> createDedupe = new HashMap<>();

    @Override
    public synchronized SessionMetadata create(SessionMetadata session, String requestId) {
        requireRequestId(requestId);
        Long priorId = createDedupe.get(requestId);
        if (priorId != null) {
            return sessions.get(priorId);
        }
        SessionMetadata existing = sessions.get(session.sessionId());
        if (existing != null) {
            if (existing.controllerDeviceId() != session.controllerDeviceId()
                    || existing.agentDeviceId() != session.agentDeviceId()) {
                throw new IllegalStateException("session id is already bound to other members");
            }
            return existing;
        }
        sessions.put(session.sessionId(), session);
        createDedupe.put(requestId, session.sessionId());
        return session;
    }

    @Override
    public synchronized Optional<SessionMetadata> find(long sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public synchronized SessionMetadata transition(long sessionId, long expectedVersion,
                                                   SessionState expectedState, SessionState nextState,
                                                   long actorDeviceId) {
        SessionMetadata current = required(sessionId);
        authorize(current, actorDeviceId);
        if (current.version() != expectedVersion || current.state() != expectedState) {
            throw new IllegalStateException("stale session version or state");
        }
        if (!allowed(expectedState, nextState)) {
            throw new IllegalStateException("illegal session transition");
        }
        return replace(current, nextState, current.routeEpoch(), null, 0);
    }

    @Override
    public synchronized SessionMetadata updateRouteEpoch(long sessionId, long expectedRouteEpoch,
                                                         long nextRouteEpoch) {
        SessionMetadata current = required(sessionId);
        if (current.state().terminal() || current.routeEpoch() != expectedRouteEpoch
                || nextRouteEpoch != expectedRouteEpoch + 1) {
            throw new IllegalStateException("stale or invalid route epoch");
        }
        return replace(current, SessionState.ACTIVE, nextRouteEpoch, null, 0);
    }

    @Override
    public synchronized SessionMetadata refreshConnectionEpoch(long sessionId, long deviceId, long connectionEpoch) {
        SessionMetadata current = required(sessionId);
        authorize(current, deviceId);
        long existing = current.isController(deviceId)
                ? current.controllerConnectionEpoch() : current.agentConnectionEpoch();
        if (connectionEpoch < existing) throw new SecurityException("stale connection epoch");
        if (connectionEpoch == existing) return current;
        long now = System.currentTimeMillis();
        SessionMetadata next = new SessionMetadata(current.sessionId(), current.version() + 1, current.state(),
                current.controllerDeviceId(), current.agentDeviceId(),
                current.isController(deviceId) ? connectionEpoch : current.controllerConnectionEpoch(),
                current.isController(deviceId) ? current.agentConnectionEpoch() : connectionEpoch,
                current.coordinatorNodeId(), current.routeEpoch(), current.createdAt(), now,
                current.expiresAt(), current.endReason(), current.endCode());
        sessions.put(sessionId, next);
        return next;
    }

    @Override
    public synchronized SessionMetadata end(long sessionId, long actorDeviceId, String reason, int code) {
        SessionMetadata current = required(sessionId);
        authorize(current, actorDeviceId);
        if (current.state() == SessionState.ENDED) {
            return current;
        }
        return replace(current, SessionState.ENDED, current.routeEpoch(), reason, code);
    }

    private SessionMetadata replace(SessionMetadata current, SessionState state, long routeEpoch,
                                    String endReason, int endCode) {
        long now = System.currentTimeMillis();
        SessionMetadata next = new SessionMetadata(current.sessionId(), current.version() + 1, state,
                current.controllerDeviceId(), current.agentDeviceId(),
                current.controllerConnectionEpoch(), current.agentConnectionEpoch(),
                current.coordinatorNodeId(), routeEpoch, current.createdAt(), now, current.expiresAt(),
                endReason, endCode);
        sessions.put(next.sessionId(), next);
        return next;
    }

    private SessionMetadata required(long sessionId) {
        SessionMetadata session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("session not found");
        }
        return session;
    }

    private static void authorize(SessionMetadata session, long deviceId) {
        if (!session.isMember(deviceId)) {
            throw new SecurityException("actor is not a session member");
        }
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("invalid request id");
        }
    }

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
