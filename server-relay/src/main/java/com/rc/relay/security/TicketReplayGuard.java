package com.rc.relay.security;

import com.rc.common.crypto.RelayTicketV2;

import java.util.HashMap;
import java.util.Map;

/** Tracks jti+role consumption while allowing only exact idempotent JOIN retransmission. */
public final class TicketReplayGuard {
    private record Consumption(long sessionId, long epoch, long deviceId, String nonce, long expiresAt) { }
    private final Map<String, Consumption> used = new HashMap<>();

    public synchronized boolean consume(RelayTicketV2 ticket, String connectionNonce, long nowSeconds) {
        used.entrySet().removeIf(e -> e.getValue().expiresAt() <= nowSeconds);
        String key = ticket.tokenId() + ":" + ticket.role();
        Consumption current = used.get(key);
        Consumption proposed = new Consumption(ticket.sessionId(), ticket.routeEpoch(), ticket.deviceId(),
                connectionNonce, ticket.expiresAt());
        if (current == null) {
            used.put(key, proposed);
            return true;
        }
        return current.sessionId() == proposed.sessionId() && current.epoch() == proposed.epoch()
                && current.deviceId() == proposed.deviceId() && current.nonce().equals(proposed.nonce());
    }
}
