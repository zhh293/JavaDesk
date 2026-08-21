package com.rc.relay.session;

import com.rc.common.protocol.PathType;

public record RelaySessionKey(long sessionId, long routeEpoch, PathType pathType) {
    public RelaySessionKey {
        if (sessionId <= 0 || routeEpoch <= 0) throw new IllegalArgumentException("invalid relay session key");
        java.util.Objects.requireNonNull(pathType, "pathType");
    }
}
