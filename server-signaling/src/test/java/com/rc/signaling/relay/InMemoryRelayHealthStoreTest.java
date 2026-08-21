package com.rc.signaling.relay;

import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRelayHealthStoreTest {
    @Test void failuresAndPressureIncreaseSharedSchedulingScore() {
        InMemoryRelayHealthStore store = new InMemoryRelayHealthStore();
        long now = System.currentTimeMillis();
        store.updateRuntime(new RelayRuntimeSample("healthy", 100, 1000, .1, .1, .1, now));
        store.updateRuntime(new RelayRuntimeSample("bad", 900, 1000, .9, .8, .7, now));
        store.record(new RelayObservation("bad", "cn-east", "telecom", PathType.RELAY_UDP,
                false, 400, .3, "timeout", now));
        RelayHealth healthy = store.health("healthy", "cn-east", "telecom", PathType.RELAY_UDP);
        RelayHealth bad = store.health("bad", "cn-east", "telecom", PathType.RELAY_UDP);
        assertThat(bad.score(now)).isGreaterThan(healthy.score(now));
        assertThat(bad.failureRate()).isEqualTo(1);
    }
}
