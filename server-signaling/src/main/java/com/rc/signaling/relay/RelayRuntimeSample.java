package com.rc.signaling.relay;

/** High-frequency runtime data kept out of Nacos metadata. Ratios are normalized to [0,1]. */
public record RelayRuntimeSample(String nodeId, int activeSessions, int capacity,
                                 double cpuRatio, double bandwidthRatio, double directMemoryRatio,
                                 long observedAt) {
    public RelayRuntimeSample {
        if (nodeId == null || nodeId.isBlank() || activeSessions < 0 || capacity <= 0) {
            throw new IllegalArgumentException("invalid Relay runtime sample");
        }
        cpuRatio = ratio(cpuRatio); bandwidthRatio = ratio(bandwidthRatio);
        directMemoryRatio = ratio(directMemoryRatio);
    }
    public double capacityRatio() { return Math.min(1, (double) activeSessions / capacity); }
    private static double ratio(double value) {
        if (!Double.isFinite(value)) return 1; return Math.max(0, Math.min(1, value));
    }
}
