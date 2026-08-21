package com.rc.signaling.route;

import com.rc.common.crypto.RelayTicketV2.PeerRole;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Linearizable route state machine mirroring the Redis Lua CAS contract. */
@Component
@Profile("!prod")
public final class InMemorySessionRouteStore implements SessionRouteStore {
    private final Map<Long, SessionRoute> committed = new HashMap<>();
    private final Map<Long, SessionRoute> preparing = new HashMap<>();
    private final Map<String, SessionRoute> requestDedupe = new HashMap<>();
    private final Map<String, SessionRoute> readyDedupe = new HashMap<>();

    @Override
    public synchronized SessionRoute prepare(SessionRoute proposal) {
        requirePreparing(proposal);
        String dedupeKey = proposal.sessionId() + ":" + proposal.switchRequestId();
        SessionRoute prior = requestDedupe.get(dedupeKey);
        if (prior != null) {
            return currentEquivalent(prior);
        }
        SessionRoute inProgress = preparing.get(proposal.sessionId());
        if (inProgress != null) {
            throw new RouteConflictException(RouteConflictException.Reason.MIGRATION_IN_PROGRESS, inProgress);
        }
        SessionRoute current = committed.get(proposal.sessionId());
        long currentEpoch = current == null ? 0 : current.routeEpoch();
        if (currentEpoch != proposal.baseEpoch() || proposal.routeEpoch() != proposal.baseEpoch() + 1) {
            throw new RouteConflictException(RouteConflictException.Reason.STALE_EPOCH, current);
        }
        preparing.put(proposal.sessionId(), proposal);
        requestDedupe.put(dedupeKey, proposal);
        return proposal;
    }

    @Override
    public synchronized SessionRoute markReady(long sessionId, long routeEpoch, String assignmentId,
                                               String requestId, PeerRole role) {
        requireId(requestId, "requestId");
        String dedupeKey = sessionId + ":ready:" + requestId;
        SessionRoute deduped = readyDedupe.get(dedupeKey);
        if (deduped != null) {
            return currentEquivalent(deduped);
        }
        SessionRoute route = preparing.get(sessionId);
        if (route == null || route.routeEpoch() != routeEpoch || !route.assignmentId().equals(assignmentId)) {
            SessionRoute current = committed.get(sessionId);
            if (current != null && current.routeEpoch() == routeEpoch
                    && current.assignmentId().equals(assignmentId)) {
                readyDedupe.put(dedupeKey, current);
                return current;
            }
            throw new RouteConflictException(RouteConflictException.Reason.STALE_EPOCH, current);
        }
        boolean controllerReady = route.controllerReady() || role == PeerRole.CONTROLLER;
        boolean agentReady = route.agentReady() || role == PeerRole.AGENT;
        SessionRoute updated = copy(route, RouteState.PREPARING, controllerReady, agentReady, 0, null);
        if (updated.bothReady()) {
            updated = copy(updated, RouteState.COMMITTED, true, true, System.currentTimeMillis(), null);
            committed.put(sessionId, updated);
            preparing.remove(sessionId, route);
        } else {
            preparing.put(sessionId, updated);
        }
        readyDedupe.put(dedupeKey, updated);
        return updated;
    }

    @Override
    public synchronized SessionRoute abort(long sessionId, long routeEpoch,
                                           String assignmentId, String reason) {
        SessionRoute route = preparing.get(sessionId);
        if (route == null || route.routeEpoch() != routeEpoch || !route.assignmentId().equals(assignmentId)) {
            throw new RouteConflictException(RouteConflictException.Reason.STALE_EPOCH, committed.get(sessionId));
        }
        SessionRoute aborted = copy(route, RouteState.ABORTED, route.controllerReady(),
                route.agentReady(), 0, reason == null ? "aborted" : reason);
        preparing.remove(sessionId, route);
        requestDedupe.put(sessionId + ":" + route.switchRequestId(), aborted);
        return aborted;
    }

    @Override
    public synchronized Optional<SessionRoute> expirePreparation(long sessionId, long nowMillis) {
        SessionRoute route = preparing.get(sessionId);
        if (route == null || route.prepareDeadlineAt() > nowMillis) {
            return Optional.empty();
        }
        return Optional.of(abort(sessionId, route.routeEpoch(), route.assignmentId(), "prepare timeout"));
    }

    @Override
    public synchronized SessionRouteSnapshot snapshot(long sessionId) {
        return new SessionRouteSnapshot(committed.get(sessionId), preparing.get(sessionId));
    }

    private SessionRoute currentEquivalent(SessionRoute prior) {
        SessionRoute activePreparing = preparing.get(prior.sessionId());
        if (activePreparing != null && activePreparing.switchRequestId().equals(prior.switchRequestId())) {
            return activePreparing;
        }
        SessionRoute activeCommitted = committed.get(prior.sessionId());
        return activeCommitted != null && activeCommitted.assignmentId().equals(prior.assignmentId())
                ? activeCommitted : prior;
    }

    private static SessionRoute copy(SessionRoute route, RouteState state, boolean controllerReady,
                                     boolean agentReady, long commitAt, String failure) {
        return new SessionRoute(route.sessionId(), route.routeEpoch(), state, route.pathType(),
                route.relayNodeId(), route.relayHost(), route.relayPort(), route.tls(),
                route.switchRequestId(), route.assignmentId(), route.baseEpoch(),
                controllerReady, agentReady, route.prepareDeadlineAt(), commitAt, failure,
                route.excludedRelayNodeIds());
    }

    private static void requirePreparing(SessionRoute route) {
        if (route.state() != RouteState.PREPARING || route.assignmentId().isBlank()
                || route.switchRequestId().isBlank() || route.prepareDeadlineAt() <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("invalid preparing route");
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }
}
