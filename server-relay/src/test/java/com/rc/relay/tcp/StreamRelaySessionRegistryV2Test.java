package com.rc.relay.tcp;

import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.protocol.PathType;
import com.rc.relay.session.RelaySessionKey;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamRelaySessionRegistryV2Test {
    @Test void routesOnlyBetweenDistinctSignedRolesAndRejectsReplaySequence() {
        long now = System.currentTimeMillis();
        StreamRelaySessionRegistryV2 registry = new StreamRelaySessionRegistryV2(30_000);
        EmbeddedChannel controller = new EmbeddedChannel();
        EmbeddedChannel agent = new EmbeddedChannel();
        RelayTicketV2 c = ticket(RelayTicketV2.PeerRole.CONTROLLER, 11, "c", now);
        RelayTicketV2 a = ticket(RelayTicketV2.PeerRole.AGENT, 12, "a", now);
        assertThat(registry.join(c, "relay-a", PathType.RELAY_TCP, "nonce-c", controller, now)).isFalse();
        assertThat(registry.join(a, "relay-a", PathType.RELAY_TCP, "nonce-a", agent, now)).isTrue();
        RelaySessionKey key = new RelaySessionKey(99, 3, PathType.RELAY_TCP);
        assertThat(registry.peerFor(key, RelayTicketV2.PeerRole.CONTROLLER, controller, 1, now)).isSameAs(agent);
        assertThat(registry.peerFor(key, RelayTicketV2.PeerRole.CONTROLLER, controller, 1, now)).isNull();
        registry.close(); controller.finishAndReleaseAll(); agent.finishAndReleaseAll();
    }

    private static RelayTicketV2 ticket(RelayTicketV2.PeerRole role, long device, String id, long nowMillis) {
        long now = nowMillis / 1000;
        return new RelayTicketV2("signaling", "key", id, 99, 3, "assignment", "relay-a",
                PathType.RELAY_TCP, role, device, 1, now, now, now + 30);
    }
}
