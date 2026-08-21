package com.rc.signaling.route;

import com.rc.common.crypto.RelayTicketV2.PeerRole;

import java.util.Optional;

public interface SessionRouteStore {
    SessionRoute prepare(SessionRoute proposal);

    SessionRoute markReady(long sessionId, long routeEpoch, String assignmentId,
                           String requestId, PeerRole role);

    SessionRoute abort(long sessionId, long routeEpoch, String assignmentId, String reason);

    Optional<SessionRoute> expirePreparation(long sessionId, long nowMillis);

    SessionRouteSnapshot snapshot(long sessionId);
}
