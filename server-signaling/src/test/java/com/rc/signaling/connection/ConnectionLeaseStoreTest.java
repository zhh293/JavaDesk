package com.rc.signaling.connection;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionLeaseStoreTest {
    @Test
    void oldDisconnectAndHeartbeatCannotMutateNewLease() {
        InMemoryConnectionLeaseStore store = new InMemoryConnectionLeaseStore();
        ConnectionLease old = store.register(1, 2, "node-a", "connection-a", "client-a",
                "1", "2.0", Duration.ofSeconds(30));
        ConnectionLease current = store.register(1, 2, "node-b", "connection-b", "client-b",
                "1", "2.0", Duration.ofSeconds(30));

        assertThat(current.connectionEpoch()).isGreaterThan(old.connectionEpoch());
        assertThat(store.renew(1, old.connectionId(), old.connectionEpoch(), Duration.ofSeconds(30))).isFalse();
        assertThat(store.delete(1, old.connectionId(), old.connectionEpoch())).isFalse();
        assertThat(store.find(1)).contains(current);
        assertThat(store.delete(1, current.connectionId(), current.connectionEpoch())).isTrue();
    }
}
