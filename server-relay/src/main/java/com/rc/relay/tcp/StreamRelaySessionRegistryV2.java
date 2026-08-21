package com.rc.relay.tcp;

import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.crypto.RelayTicketV2.PeerRole;
import com.rc.common.protocol.PathType;
import com.rc.relay.security.TicketReplayGuard;
import com.rc.relay.session.RelaySessionKey;
import io.netty.channel.Channel;

import java.util.HashMap;
import java.util.Map;

/** Connection-oriented V2 relay seats, fenced by assignment epoch, role and monotonic sequence. */
public final class StreamRelaySessionRegistryV2 implements AutoCloseable {
    private final Map<RelaySessionKey, Session> sessions = new HashMap<>();
    private final Map<Channel, SeatRef> byChannel = new HashMap<>();
    private final TicketReplayGuard replay = new TicketReplayGuard();
    private final long idleTimeoutMillis;
    private final java.util.concurrent.ScheduledExecutorService sweeper;

    public StreamRelaySessionRegistryV2(long idleTimeoutMillis) {
        if (idleTimeoutMillis <= 0) throw new IllegalArgumentException("idle timeout must be positive");
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-relay-stream-sweeper"); t.setDaemon(true); return t;
        });
        long interval = Math.max(1_000, Math.min(30_000, idleTimeoutMillis / 2));
        sweeper.scheduleWithFixedDelay(() -> sweep(System.currentTimeMillis()), interval, interval,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public synchronized boolean join(RelayTicketV2 ticket, String expectedNode, PathType expectedPath,
                                     String connectionNonce, Channel channel, long nowMillis) {
        ticket.validateFor(expectedNode, expectedPath, nowMillis / 1000);
        if (!replay.consume(ticket, connectionNonce, nowMillis / 1000)) {
            throw new SecurityException("relay ticket replay or role takeover");
        }
        RelaySessionKey key = new RelaySessionKey(ticket.sessionId(), ticket.routeEpoch(), expectedPath);
        Session current = sessions.getOrDefault(key, new Session(null, null, nowMillis));
        Seat existing = ticket.role() == PeerRole.CONTROLLER ? current.controller : current.agent;
        if (existing != null && existing.channel != channel) {
            if (!existing.tokenId.equals(ticket.tokenId()) || !existing.nonce.equals(connectionNonce)) {
                throw new SecurityException("relay role occupied");
            }
            byChannel.remove(existing.channel);
            existing.channel.close();
        }
        Seat seat = new Seat(ticket.role(), ticket.deviceId(), ticket.tokenId(), connectionNonce, channel, -1);
        Session updated = ticket.role() == PeerRole.CONTROLLER
                ? new Session(seat, current.agent, nowMillis) : new Session(current.controller, seat, nowMillis);
        sessions.put(key, updated);
        byChannel.put(channel, new SeatRef(key, ticket.role()));
        return updated.controller != null && updated.agent != null;
    }

    public synchronized Channel peerFor(RelaySessionKey key, PeerRole role, Channel source,
                                        long sequence, long nowMillis) {
        SeatRef ref = byChannel.get(source);
        Session session = sessions.get(key);
        if (ref == null || session == null || !ref.key.equals(key) || ref.role != role) return null;
        Seat own = role == PeerRole.CONTROLLER ? session.controller : session.agent;
        Seat peer = role == PeerRole.CONTROLLER ? session.agent : session.controller;
        if (own == null || own.channel != source || peer == null || sequence <= own.lastSequence) return null;
        Seat advanced = new Seat(own.role, own.deviceId, own.tokenId, own.nonce, own.channel, sequence);
        sessions.put(key, role == PeerRole.CONTROLLER
                ? new Session(advanced, peer, nowMillis) : new Session(peer, advanced, nowMillis));
        return peer.channel;
    }

    public synchronized boolean peerReady(RelaySessionKey key) {
        Session session = sessions.get(key);
        return session != null && session.controller != null && session.agent != null;
    }

    public synchronized void remove(Channel channel) {
        SeatRef ref = byChannel.remove(channel);
        if (ref == null) return;
        Session session = sessions.get(ref.key);
        if (session == null) return;
        Session updated = ref.role == PeerRole.CONTROLLER
                ? new Session(null, session.agent, System.currentTimeMillis())
                : new Session(session.controller, null, System.currentTimeMillis());
        if (updated.controller == null && updated.agent == null) sessions.remove(ref.key); else sessions.put(ref.key, updated);
    }

    public synchronized int sweep(long nowMillis) {
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().lastActivity + idleTimeoutMillis > nowMillis) return false;
            if (entry.getValue().controller != null) {
                byChannel.remove(entry.getValue().controller.channel);
                entry.getValue().controller.channel.close();
            }
            if (entry.getValue().agent != null) {
                byChannel.remove(entry.getValue().agent.channel);
                entry.getValue().agent.channel.close();
            }
            return true;
        });
        return before - sessions.size();
    }

    public synchronized int size() { return sessions.size(); }
    @Override public synchronized void close() { sweeper.shutdownNow(); sessions.clear(); byChannel.clear(); }

    private record Seat(PeerRole role, long deviceId, String tokenId, String nonce,
                        Channel channel, long lastSequence) { }
    private record SeatRef(RelaySessionKey key, PeerRole role) { }
    private record Session(Seat controller, Seat agent, long lastActivity) { }
}
