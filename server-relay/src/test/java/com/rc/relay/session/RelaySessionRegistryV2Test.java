package com.rc.relay.session;

import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.model.Endpoint;
import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelaySessionRegistryV2Test {
    @Test
    void seatsAreRoleBoundPeerReadyRequiresBothAndActivityPreventsSweep() {
        long now = System.currentTimeMillis();
        long seconds = now / 1000;
        RelaySessionRegistryV2 registry = new RelaySessionRegistryV2(1000);
        RelayTicketV2 controller = ticket("c", RelayTicketV2.PeerRole.CONTROLLER, 1, seconds);
        RelayTicketV2 agent = ticket("a", RelayTicketV2.PeerRole.AGENT, 2, seconds);
        RelaySession first = registry.join(controller, "relay-a", PathType.RELAY_UDP,
                "nonce-c", new Endpoint("10.0.0.1", 1), now);
        assertThat(first.peerReady()).isFalse();
        RelaySession ready = registry.join(agent, "relay-a", PathType.RELAY_UDP,
                "nonce-a", new Endpoint("10.0.0.2", 2), now);
        assertThat(ready.peerReady()).isTrue();
        RelaySessionKey key = ready.key();
        assertThat(registry.peerFor(key, RelayTicketV2.PeerRole.CONTROLLER, 1, 1, now + 900))
                .isEqualTo(new Endpoint("10.0.0.2", 2));
        assertThat(registry.sweep(now + 1500)).isZero();
        assertThat(registry.sweep(now + 2001)).isEqualTo(1);
    }

    @Test
    void wrongNodeAndRoleTakeoverAreRejected() {
        long now = System.currentTimeMillis();
        RelaySessionRegistryV2 registry = new RelaySessionRegistryV2(1000);
        RelayTicketV2 ticket = ticket("c", RelayTicketV2.PeerRole.CONTROLLER, 1, now / 1000);
        assertThatThrownBy(() -> registry.join(ticket, "relay-b", PathType.RELAY_UDP,
                "n", new Endpoint("10.0.0.1", 1), now)).isInstanceOf(RuntimeException.class);
        registry.join(ticket, "relay-a", PathType.RELAY_UDP,
                "n", new Endpoint("10.0.0.1", 1), now);
        assertThatThrownBy(() -> registry.join(ticket, "relay-a", PathType.RELAY_UDP,
                "other", new Endpoint("10.0.0.9", 9), now)).isInstanceOf(SecurityException.class);
    }

    private static RelayTicketV2 ticket(String jti, RelayTicketV2.PeerRole role, long device, long now) {
        return new RelayTicketV2("signaling", "kid", jti, 9, 3, "assignment",
                "relay-a", PathType.RELAY_UDP, role, device, 1, now, now, now + 30);
    }
}
