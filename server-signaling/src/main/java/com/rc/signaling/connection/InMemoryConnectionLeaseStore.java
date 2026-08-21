package com.rc.signaling.connection;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Linearizable process-local implementation used by dev and deterministic tests. */
@Component
@Profile("!prod")
public final class InMemoryConnectionLeaseStore implements ConnectionLeaseStore {
    private final Map<Long, ConnectionLease> leases = new HashMap<>();
    private final Map<Long, Long> epochs = new HashMap<>();

    @Override
    public synchronized ConnectionLease register(long deviceId, long userId, String nodeId,
                                                 String connectionId, String clientInstanceId,
                                                 String clientVersion, String protocolVersion,
                                                 Duration ttl) {
        long now = System.currentTimeMillis();
        long epoch = Math.incrementExact(epochs.getOrDefault(deviceId, 0L));
        epochs.put(deviceId, epoch);
        ConnectionLease lease = new ConnectionLease(deviceId, userId, nodeId, connectionId, epoch,
                clientInstanceId, now, Math.addExact(now, ttl.toMillis()), clientVersion, protocolVersion);
        leases.put(deviceId, lease);
        return lease;
    }

    @Override
    public synchronized boolean renew(long deviceId, String connectionId, long connectionEpoch, Duration ttl) {
        ConnectionLease current = leases.get(deviceId);
        if (!matches(current, connectionId, connectionEpoch)) {
            return false;
        }
        long now = System.currentTimeMillis();
        leases.put(deviceId, new ConnectionLease(current.deviceId(), current.userId(), current.nodeId(),
                current.connectionId(), current.connectionEpoch(), current.clientInstanceId(),
                current.connectedAt(), Math.addExact(now, ttl.toMillis()), current.clientVersion(),
                current.protocolVersion()));
        return true;
    }

    @Override
    public synchronized boolean delete(long deviceId, String connectionId, long connectionEpoch) {
        ConnectionLease current = leases.get(deviceId);
        return matches(current, connectionId, connectionEpoch) && leases.remove(deviceId, current);
    }

    @Override
    public synchronized Optional<ConnectionLease> find(long deviceId) {
        ConnectionLease lease = leases.get(deviceId);
        if (lease != null && lease.leaseExpireAt() <= System.currentTimeMillis()) {
            leases.remove(deviceId, lease);
            lease = null;
        }
        return Optional.ofNullable(lease);
    }

    private static boolean matches(ConnectionLease lease, String connectionId, long epoch) {
        return lease != null && lease.connectionEpoch() == epoch && lease.connectionId().equals(connectionId);
    }
}
