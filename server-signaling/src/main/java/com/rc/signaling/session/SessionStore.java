package com.rc.signaling.session;

import java.util.Optional;

public interface SessionStore {
    SessionMetadata create(SessionMetadata session, String requestId);

    Optional<SessionMetadata> find(long sessionId);

    SessionMetadata transition(long sessionId, long expectedVersion, SessionState expectedState,
                               SessionState nextState, long actorDeviceId);

    SessionMetadata updateRouteEpoch(long sessionId, long expectedRouteEpoch, long nextRouteEpoch);

    SessionMetadata refreshConnectionEpoch(long sessionId, long deviceId, long connectionEpoch);

    SessionMetadata end(long sessionId, long actorDeviceId, String reason, int code);
}
