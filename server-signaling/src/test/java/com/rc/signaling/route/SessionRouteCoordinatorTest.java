package com.rc.signaling.route;

import com.rc.common.protocol.PathType;
import com.rc.signaling.session.InMemorySessionStore;
import com.rc.signaling.session.SessionMetadata;
import com.rc.signaling.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRouteCoordinatorTest {
    @Test
    void concurrentSwitchRequestsProduceOnePreparingRouteAndDualReadyCommitsOnce() throws Exception {
        InMemorySessionStore sessions = new InMemorySessionStore();
        long now = System.currentTimeMillis();
        sessions.create(new SessionMetadata(100, 0, SessionState.ACTIVE, 1, 2,
                10, 20, "node-a", 0, now, now, now + 60_000, null, 0), "create");
        InMemorySessionRouteStore routes = new InMemorySessionRouteStore();
        SessionRouteCoordinator coordinator = new SessionRouteCoordinator(routes, sessions);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<SessionRoute>> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String request = "switch-" + i;
                calls.add(() -> {
                    try {
                        return coordinator.prepare(100, 1, 0, request, PathType.RELAY_UDP,
                                "relay-a", "127.0.0.1", 9090, false, Set.of(), Duration.ofSeconds(5));
                    } catch (RouteConflictException conflict) {
                        return conflict.current();
                    }
                });
            }
            Set<String> assignments = pool.invokeAll(calls).stream().map(f -> {
                try { return f.get().assignmentId(); } catch (Exception e) { throw new RuntimeException(e); }
            }).collect(java.util.stream.Collectors.toSet());
            assertThat(assignments).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        SessionRoute prepared = routes.snapshot(100).preparing();
        SessionRoute oneReady = coordinator.ready(100, 1, 10, 1, prepared.assignmentId(), "ready-c");
        assertThat(oneReady.state()).isEqualTo(RouteState.PREPARING);
        SessionRoute committed = coordinator.ready(100, 2, 20, 1, prepared.assignmentId(), "ready-a");
        assertThat(committed.state()).isEqualTo(RouteState.COMMITTED);
        assertThat(routes.snapshot(100).preparing()).isNull();
        assertThat(routes.snapshot(100).committed().routeEpoch()).isEqualTo(1);
        assertThat(sessions.find(100).orElseThrow().routeEpoch()).isEqualTo(1);
    }
}
