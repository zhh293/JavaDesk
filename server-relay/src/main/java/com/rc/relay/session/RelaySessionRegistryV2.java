package com.rc.relay.session;

import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.crypto.RelayTicketV2.PeerRole;
import com.rc.common.model.Endpoint;
import com.rc.common.protocol.PathType;
import com.rc.relay.security.TicketReplayGuard;

import java.util.HashMap;
import java.util.Map;

/** Role-based relay seats fenced by session+epoch+path and signed ticket claims. */
public final class RelaySessionRegistryV2 implements AutoCloseable {
    private final Map<RelaySessionKey, RelaySession> sessions = new HashMap<>();
    private final TicketReplayGuard replayGuard = new TicketReplayGuard();
    private final long idleTimeoutMillis;
    private final java.util.concurrent.ScheduledExecutorService sweeper;

    public RelaySessionRegistryV2(long idleTimeoutMillis) {
        if (idleTimeoutMillis <= 0) throw new IllegalArgumentException("idle timeout must be positive");
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-relay-udp-sweeper"); t.setDaemon(true); return t;
        });
        long interval = Math.max(1_000, Math.min(30_000, idleTimeoutMillis / 2));
        sweeper.scheduleWithFixedDelay(() -> sweep(System.currentTimeMillis()), interval, interval,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public synchronized RelaySession join(RelayTicketV2 ticket, String expectedNode,
                                          PathType expectedPath, String connectionNonce,
                                          Endpoint endpoint, long nowMillis) {
        ticket.validateFor(expectedNode, expectedPath, nowMillis / 1000);
        if (connectionNonce == null || connectionNonce.isBlank() || connectionNonce.length() > 128) {
            throw new IllegalArgumentException("invalid connection nonce");
        }
        if (!replayGuard.consume(ticket, connectionNonce, nowMillis / 1000)) {
            throw new SecurityException("relay ticket replay or role takeover");
        }
        RelaySessionKey key = new RelaySessionKey(ticket.sessionId(), ticket.routeEpoch(), expectedPath);
        RelaySession current = sessions.get(key);
        RelaySeat seat = new RelaySeat(ticket.role(), ticket.deviceId(), ticket.tokenId(),
                connectionNonce, endpoint, nowMillis, nowMillis, -1);
        RelaySeat controller = current == null ? null : current.controllerSeat();
        RelaySeat agent = current == null ? null : current.agentSeat();
        RelaySeat existing = ticket.role() == PeerRole.CONTROLLER ? controller : agent;
        if (existing != null && (!existing.tokenId().equals(ticket.tokenId())
                || !existing.connectionNonce().equals(connectionNonce))) {
            throw new SecurityException("relay role occupied");
        }
        if (ticket.role() == PeerRole.CONTROLLER) controller = seat; else agent = seat;
        RelaySession updated = new RelaySession(key, controller, agent, nowMillis);
        sessions.put(key, updated);
        return updated;
    }

    public synchronized Endpoint peerFor(RelaySessionKey key, PeerRole role, long deviceId,
                                         long sequence, long nowMillis) {
        RelaySession current = sessions.get(key);
        if (current == null) return null;
        RelaySeat own = role == PeerRole.CONTROLLER ? current.controllerSeat() : current.agentSeat();
        RelaySeat peer = role == PeerRole.CONTROLLER ? current.agentSeat() : current.controllerSeat();
        if (own == null || peer == null || own.deviceId() != deviceId
                || sequence <= own.lastSequence()) return null;
        RelaySeat refreshed = new RelaySeat(own.role(), own.deviceId(), own.tokenId(), own.connectionNonce(),
                own.udpEndpoint(), own.joinedAt(), nowMillis, sequence);
        RelaySession next = role == PeerRole.CONTROLLER
                ? new RelaySession(key, refreshed, peer, nowMillis)
                : new RelaySession(key, peer, refreshed, nowMillis);
        sessions.put(key, next);
        return peer.udpEndpoint();
    }

    public synchronized Endpoint peerFor(RelaySessionKey key, PeerRole role, Endpoint source,
                                         long sequence, long nowMillis) {
        RelaySession current = sessions.get(key);
        if (current == null) return null;
        RelaySeat own = role == PeerRole.CONTROLLER ? current.controllerSeat() : current.agentSeat();
        if (own == null || !own.udpEndpoint().equals(source)) return null;
        return peerFor(key, role, own.deviceId(), sequence, nowMillis);
    }

    public synchronized boolean peerReady(RelaySessionKey key) {
        RelaySession current = sessions.get(key);
        return current != null && current.peerReady();
    }

    public synchronized int sweep(long nowMillis) {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().lastActivityAt() + idleTimeoutMillis <= nowMillis);
        return before - sessions.size();
    }

    public synchronized int size() { return sessions.size(); }
    @Override public synchronized void close() { sweeper.shutdownNow(); sessions.clear(); }
}
