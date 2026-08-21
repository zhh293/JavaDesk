package com.rc.client.session;

import com.rc.common.constant.SessionStatus;

/** Pure, deterministic client route reducer; stale epochs and timers become no-ops. */
public final class SessionReducer {
    private SessionReducer() { }

    public static SessionStateView reduce(SessionStateView state, SessionEvent event) {
        if (state.ended()) return state;
        RouteStateView route = state.route();
        if (event instanceof SessionEvent.RoutePrepared e) {
            if (e.baseEpoch() != route.activeEpoch() || e.routeEpoch() != e.baseEpoch() + 1
                    || route.migrationInProgress()) return state;
            return new SessionStateView(state.sessionId(), SessionStatus.DEGRADED,
                    new RouteStateView(route.activeEpoch(), e.routeEpoch(), route.activePath(),
                            e.assignmentId(), true), false, null);
        }
        if (event instanceof SessionEvent.RouteCommitted e) {
            if (!route.migrationInProgress() || e.routeEpoch() != route.preparingEpoch()
                    || !e.assignmentId().equals(route.assignmentId())) return state;
            SessionStatus status = e.pathType() == com.rc.common.protocol.PathType.P2P
                    ? SessionStatus.P2P_CONNECTED : SessionStatus.RELAY_CONNECTED;
            return new SessionStateView(state.sessionId(), status,
                    new RouteStateView(e.routeEpoch(), e.routeEpoch(), e.pathType(), "", false), false, null);
        }
        if (event instanceof SessionEvent.RouteAborted e) {
            if (!route.migrationInProgress() || e.routeEpoch() != route.preparingEpoch()
                    || !e.assignmentId().equals(route.assignmentId())) return state;
            return new SessionStateView(state.sessionId(), state.status(),
                    new RouteStateView(route.activeEpoch(), route.activeEpoch(), route.activePath(), "", false),
                    false, e.reason());
        }
        if (event instanceof SessionEvent.TransportClosed e) {
            if (e.routeEpoch() != route.activeEpoch()) return state;
            return new SessionStateView(state.sessionId(), SessionStatus.DEGRADED, route,
                    false, e.cause() == null ? "transport closed" : e.cause().getMessage());
        }
        if (event instanceof SessionEvent.TimerFired) {
            // Timer effects are produced by the actor; the pure reducer never mutates state for a tick.
            return state;
        }
        if (event instanceof SessionEvent.UserHangup) {
            return new SessionStateView(state.sessionId(), SessionStatus.ENDED, route, true, null);
        }
        return state;
    }
}
