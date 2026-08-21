package com.rc.signaling.route;

import com.rc.common.crypto.RelayTicketV2.PeerRole;
import com.rc.common.protocol.PathType;
import com.rc.signaling.session.SessionMetadata;
import com.rc.signaling.session.SessionStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Sole server-side authority for PREPARE -> dual READY -> COMMIT. */
@Service
public final class SessionRouteCoordinator {
    private final SessionRouteStore routes;
    private final SessionStore sessions;

    public SessionRouteCoordinator(SessionRouteStore routes, SessionStore sessions) {
        this.routes = routes;
        this.sessions = sessions;
    }

    public SessionRoute prepare(long sessionId, long actorDeviceId, long baseEpoch,
                                String requestId, PathType pathType, String relayNodeId,
                                String relayHost, int relayPort, boolean tls,
                                Set<String> excludedNodes, Duration timeout) {
        SessionMetadata session = sessions.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found"));
        if (session.state().terminal()) {
            throw new RouteConflictException(RouteConflictException.Reason.SESSION_ENDED, null);
        }
        if (!session.isMember(actorDeviceId)) {
            throw new SecurityException("actor is not a session member");
        }
        long now = System.currentTimeMillis();
        SessionRoute proposal = new SessionRoute(sessionId, baseEpoch + 1, RouteState.PREPARING,
                pathType, relayNodeId, relayHost, relayPort, tls, requestId,
                UUID.randomUUID().toString(), baseEpoch, false, false,
                Math.addExact(now, timeout.toMillis()), 0, null, excludedNodes);
        return routes.prepare(proposal);
    }

    public SessionRoute ready(long sessionId, long actorDeviceId, long connectionEpoch,
                              long routeEpoch, String assignmentId, String requestId) {
        SessionMetadata session = sessions.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found"));
        if (!session.isMember(actorDeviceId)) {
            throw new SecurityException("actor is not a session member");
        }
        long expectedConnectionEpoch = session.isController(actorDeviceId)
                ? session.controllerConnectionEpoch() : session.agentConnectionEpoch();
        if (expectedConnectionEpoch > 0 && connectionEpoch != expectedConnectionEpoch) {
            throw new SecurityException("stale connection epoch");
        }
        PeerRole role = session.isController(actorDeviceId) ? PeerRole.CONTROLLER : PeerRole.AGENT;
        SessionRoute route = routes.markReady(sessionId, routeEpoch, assignmentId, requestId, role);
        if (route.state() == RouteState.COMMITTED && session.routeEpoch() < route.routeEpoch()) {
            sessions.updateRouteEpoch(sessionId, route.baseEpoch(), route.routeEpoch());
        }
        return route;
    }

    public SessionRouteSnapshot snapshot(long sessionId, long actorDeviceId) {
        SessionMetadata session = sessions.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found"));
        if (!session.isMember(actorDeviceId)) {
            throw new SecurityException("actor is not a session member");
        }
        return routes.snapshot(sessionId);
    }
}
