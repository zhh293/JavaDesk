package com.rc.signaling.relay;

import com.rc.common.protocol.PathType;

/** Passive client or server observation, dimensioned by region/provider/path. */
public record RelayObservation(String nodeId, String region, String networkProvider,
                               PathType pathType, boolean success, double rttMs,
                               double lossRate, String failureType, long observedAt) {
    public RelayObservation {
        if (nodeId == null || nodeId.isBlank() || pathType == null) {
            throw new IllegalArgumentException("invalid Relay observation");
        }
        region = normalize(region); networkProvider = normalize(networkProvider);
        failureType = normalize(failureType);
        lossRate = Math.max(0, Math.min(1, lossRate));
    }
    public String dimension() { return region + ":" + networkProvider + ":" + pathType.name(); }
    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
