package com.rc.client.relay;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Executes the server assignment exactly; it deliberately contains no next-node/failover selection. */
public final class DefaultRelayClusterInvoker implements RelayClusterInvoker {
    private final Map<String, RelayInvoker> invokers;
    private final Map<String, EndpointCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public DefaultRelayClusterInvoker(Map<String, RelayInvoker> invokers) {
        this.invokers = Map.copyOf(invokers);
    }

    @Override
    public CompletionStage<PreparedRelayChannel> prepare(RelayAssignment assignment) {
        if (assignment.deadlineAt() <= System.currentTimeMillis()) {
            return CompletableFuture.failedFuture(new IllegalStateException("relay assignment expired"));
        }
        String key = assignment.endpoint().nodeId() + ":" + assignment.endpoint().pathType();
        EndpointCircuitBreaker breaker = breakers.computeIfAbsent(key,
                ignored -> new EndpointCircuitBreaker(3, 10_000));
        if (!breaker.allow(System.currentTimeMillis())) {
            return CompletableFuture.failedFuture(new IllegalStateException("assigned relay breaker is open"));
        }
        RelayInvoker invoker = invokers.get(assignment.endpoint().nodeId());
        if (invoker == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("assigned relay is unknown locally"));
        }
        return invoker.prepare(assignment);
    }

    @Override
    public void reportOutcome(RelayOutcome outcome) {
        for (Map.Entry<String, EndpointCircuitBreaker> entry : breakers.entrySet()) {
            if (entry.getKey().startsWith(outcome.nodeId() + ":")) {
                if (outcome.success()) entry.getValue().success();
                else entry.getValue().failure(System.currentTimeMillis());
            }
        }
    }
}
