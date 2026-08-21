package com.rc.client.session;

import com.rc.common.protocol.PathType;

public sealed interface SessionEvent permits SessionEvent.RoutePrepared, SessionEvent.RouteCommitted,
        SessionEvent.RouteAborted, SessionEvent.TransportClosed, SessionEvent.TimerFired,
        SessionEvent.UserHangup {
    record RoutePrepared(long baseEpoch, long routeEpoch, PathType pathType,
                         String assignmentId) implements SessionEvent { }
    record RouteCommitted(long routeEpoch, PathType pathType, String assignmentId) implements SessionEvent { }
    record RouteAborted(long routeEpoch, String assignmentId, String reason) implements SessionEvent { }
    record TransportClosed(long routeEpoch, Throwable cause) implements SessionEvent { }
    record TimerFired(String timerId, long expectedEpoch) implements SessionEvent { }
    record UserHangup() implements SessionEvent { }
}
