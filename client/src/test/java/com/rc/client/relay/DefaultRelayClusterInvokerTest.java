package com.rc.client.relay;

import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRelayClusterInvokerTest {
    @Test
    void invokesOnlyTheAssignedNodeAndNeverFallsBackLocally() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        RelayInvoker invokerA = assignment -> { a.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(null); };
        RelayInvoker invokerB = assignment -> { b.incrementAndGet(); return java.util.concurrent.CompletableFuture.completedFuture(null); };
        DefaultRelayClusterInvoker cluster = new DefaultRelayClusterInvoker(Map.of("a", invokerA, "b", invokerB));
        RelayAssignment assignment = new RelayAssignment(1, 2, "x",
                new RelayEndpoint("b", "127.0.0.1", 9, false, PathType.RELAY_UDP),
                "ticket", System.currentTimeMillis() + 1000);
        cluster.prepare(assignment).toCompletableFuture().join();
        assertThat(a).hasValue(0);
        assertThat(b).hasValue(1);

        RelayAssignment unknown = new RelayAssignment(1, 2, "y",
                new RelayEndpoint("c", "127.0.0.1", 9, false, PathType.RELAY_UDP),
                "ticket", System.currentTimeMillis() + 1000);
        assertThatThrownBy(() -> cluster.prepare(unknown).toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(a).hasValue(0);
        assertThat(b).hasValue(1);
    }
}
