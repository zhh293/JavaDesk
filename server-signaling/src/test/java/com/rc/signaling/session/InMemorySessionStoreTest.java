package com.rc.signaling.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySessionStoreTest {
    @Test
    void reconnectAdvancesOnlyTheMatchingMemberFence() {
        InMemorySessionStore store = new InMemorySessionStore();
        long now = System.currentTimeMillis();
        store.create(new SessionMetadata(11, 0, SessionState.ACTIVE,
                1, 2, 7, 8, "node-a", 2, now, now, now + 60_000, null, 0), "create-2");
        SessionMetadata refreshed = store.refreshConnectionEpoch(11, 1, 9);
        assertThat(refreshed.controllerConnectionEpoch()).isEqualTo(9);
        assertThat(refreshed.agentConnectionEpoch()).isEqualTo(8);
        assertThatThrownBy(() -> store.refreshConnectionEpoch(11, 1, 8))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void membershipIsImmutableAndEndIsIdempotentTerminalState() {
        InMemorySessionStore store = new InMemorySessionStore();
        long now = System.currentTimeMillis();
        SessionMetadata session = new SessionMetadata(10, 0, SessionState.INVITING,
                1, 2, 7, 8, "node-a", 0, now, now, now + 60_000, null, 0);
        assertThat(store.create(session, "create-1")).isSameAs(session);
        assertThat(store.create(new SessionMetadata(99, 0, SessionState.INVITING,
                1, 2, 7, 8, "node-a", 0, now, now, now + 60_000, null, 0), "create-1"))
                .isSameAs(session);
        assertThatThrownBy(() -> store.transition(10, 0, SessionState.INVITING,
                SessionState.ACCEPTED, 99)).isInstanceOf(SecurityException.class);

        SessionMetadata ended = store.end(10, 1, "done", 0);
        assertThat(ended.state()).isEqualTo(SessionState.ENDED);
        assertThat(store.end(10, 2, "different", 7)).isEqualTo(ended);
        assertThatThrownBy(() -> store.updateRouteEpoch(10, 0, 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
