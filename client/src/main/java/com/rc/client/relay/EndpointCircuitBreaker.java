package com.rc.client.relay;

/** Local reachability predictor only; OPEN produces a NACK and never selects another relay. */
public final class EndpointCircuitBreaker {
    private final int threshold;
    private final long cooldownMillis;
    private int failures;
    private long openedAt;

    public EndpointCircuitBreaker(int threshold, long cooldownMillis) {
        if (threshold <= 0 || cooldownMillis <= 0) throw new IllegalArgumentException("invalid breaker config");
        this.threshold = threshold; this.cooldownMillis = cooldownMillis;
    }
    public synchronized boolean allow(long now) {
        return failures < threshold || now - openedAt >= cooldownMillis;
    }
    public synchronized void success() { failures = 0; openedAt = 0; }
    public synchronized void failure(long now) {
        failures++;
        if (failures >= threshold && openedAt == 0) openedAt = now;
    }
}
