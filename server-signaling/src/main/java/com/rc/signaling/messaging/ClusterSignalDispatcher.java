package com.rc.signaling.messaging;

import com.rc.common.protocol.MessageId;
import com.rc.common.protocol.Signal;
import com.rc.signaling.connection.ConnectionLease;
import com.rc.signaling.connection.ConnectionLeaseStore;
import com.rc.signaling.session.SessionMetadata;
import org.springframework.stereotype.Service;

/** Resolves the latest fenced connection lease and routes one Protobuf signal locally or cross-node. */
@Service
public final class ClusterSignalDispatcher {
    private static final long DEFAULT_DEADLINE_MS = 10_000;
    private final ConnectionLeaseStore leases;
    private final SignalRouter router;

    public ClusterSignalDispatcher(ConnectionLeaseStore leases, SignalRouter router) {
        this.leases = leases;
        this.router = router;
    }

    public boolean send(long targetDeviceId, SessionMetadata session, Long routeEpoch,
                        String type, Signal signal) {
        ConnectionLease lease = leases.find(targetDeviceId).orElse(null);
        if (lease == null || lease.leaseExpireAt() <= System.currentTimeMillis()) return false;
        long now = System.currentTimeMillis();
        DeliveryEnvelope envelope = new DeliveryEnvelope(MessageId.random().value(), "", signal.getTraceId(),
                targetDeviceId, lease.connectionEpoch(), lease.nodeId(),
                session == null ? null : session.sessionId(), session == null ? null : session.version(),
                routeEpoch, type, signal.toByteArray(), now, Math.addExact(now, DEFAULT_DEADLINE_MS), 0);
        return router.route(envelope);
    }
}
