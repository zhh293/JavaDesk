package com.rc.signaling.connection;

import java.time.Duration;
import java.util.Optional;

/** Atomic fencing operations for device connection leases. */
public interface ConnectionLeaseStore {
    ConnectionLease register(long deviceId, long userId, String nodeId, String connectionId,
                             String clientInstanceId, String clientVersion, String protocolVersion,
                             Duration ttl);

    boolean renew(long deviceId, String connectionId, long connectionEpoch, Duration ttl);

    boolean delete(long deviceId, String connectionId, long connectionEpoch);

    Optional<ConnectionLease> find(long deviceId);
}
