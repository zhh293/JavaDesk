package com.rc.signaling.session;

/** Serializable authoritative session membership and lifecycle metadata. */
public record SessionMetadata(long sessionId, long version, SessionState state,
                              long controllerDeviceId, long agentDeviceId,
                              long controllerConnectionEpoch, long agentConnectionEpoch,
                              String coordinatorNodeId, long routeEpoch,
                              long createdAt, long updatedAt, long expiresAt,
                              String endReason, int endCode) {
    public SessionMetadata {
        if (sessionId <= 0 || version < 0 || controllerDeviceId <= 0 || agentDeviceId <= 0
                || controllerDeviceId == agentDeviceId || routeEpoch < 0) {
            throw new IllegalArgumentException("invalid session metadata");
        }
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(coordinatorNodeId, "coordinatorNodeId");
    }

    public boolean isMember(long deviceId) {
        return deviceId == controllerDeviceId || deviceId == agentDeviceId;
    }

    public boolean isController(long deviceId) {
        return deviceId == controllerDeviceId;
    }

    public boolean isAgent(long deviceId) {
        return deviceId == agentDeviceId;
    }
}
