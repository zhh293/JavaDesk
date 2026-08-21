package com.rc.signaling.relay;

/** Aggregated health used by scheduling. */
public record RelayHealth(double capacityRatio, double resourcePressure, double failureRate,
                          double rttMs, double lossRate, long updatedAt, long samples) {
    public static RelayHealth unknown() { return new RelayHealth(0, 0, 0, 0, 0, 0, 0); }

    public double score(long now) {
        double stalePenalty = updatedAt == 0 || now - updatedAt > 30_000 ? 0.15 : 0;
        double latencyPenalty = rttMs <= 0 ? 0 : Math.min(1, rttMs / 500.0);
        return 0.30 * capacityRatio + 0.20 * resourcePressure + 0.30 * failureRate
                + 0.10 * lossRate + 0.10 * latencyPenalty + stalePenalty;
    }
}
