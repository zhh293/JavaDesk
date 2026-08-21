package com.rc.signaling.connection;

/** Serializable device-to-signaling routing lease; never contains a Netty Channel. */
public record ConnectionLease(long deviceId, long userId, String nodeId, String connectionId,
                              long connectionEpoch, String clientInstanceId, long connectedAt,
                              long leaseExpireAt, String clientVersion, String protocolVersion) {
    public ConnectionLease {
        if (deviceId <= 0 || userId <= 0 || connectionEpoch <= 0 || leaseExpireAt <= connectedAt) {
            throw new IllegalArgumentException("invalid connection lease");
        }
        java.util.Objects.requireNonNull(nodeId, "nodeId");
        java.util.Objects.requireNonNull(connectionId, "connectionId");
        java.util.Objects.requireNonNull(clientInstanceId, "clientInstanceId");
    }
}
