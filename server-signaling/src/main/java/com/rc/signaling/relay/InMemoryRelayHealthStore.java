package com.rc.signaling.relay;

import com.rc.common.protocol.PathType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!prod")
public final class InMemoryRelayHealthStore implements RelayHealthStore {
    private static final double ALPHA = 0.25;
    private final ConcurrentHashMap<String, RelayRuntimeSample> runtime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Aggregate> observations = new ConcurrentHashMap<>();

    @Override public void updateRuntime(RelayRuntimeSample sample) { runtime.put(sample.nodeId(), sample); }

    @Override
    public void record(RelayObservation observation) {
        observations.computeIfAbsent(key(observation.nodeId(), observation.dimension()), ignored -> new Aggregate())
                .record(observation);
    }

    @Override
    public RelayHealth health(String nodeId, String region, String provider, PathType path) {
        RelayRuntimeSample rt = runtime.get(nodeId);
        Aggregate aggregate = observations.get(key(nodeId, dimension(region, provider, path)));
        double capacity = rt == null ? 0 : rt.capacityRatio();
        double pressure = rt == null ? 0 : Math.max(rt.cpuRatio(), Math.max(rt.bandwidthRatio(), rt.directMemoryRatio()));
        if (aggregate == null) return new RelayHealth(capacity, pressure, 0, 0, 0,
                rt == null ? 0 : rt.observedAt(), 0);
        return aggregate.snapshot(capacity, pressure, rt == null ? 0 : rt.observedAt());
    }

    private static String dimension(String region, String provider, PathType path) {
        return normalize(region) + ":" + normalize(provider) + ":" + path.name();
    }
    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
    private static String key(String nodeId, String dimension) { return nodeId + "|" + dimension; }

    private static final class Aggregate {
        private double failureRate;
        private double rtt;
        private double loss;
        private long updatedAt;
        private long samples;
        synchronized void record(RelayObservation observation) {
            double failed = observation.success() ? 0 : 1;
            failureRate = samples == 0 ? failed : ALPHA * failed + (1 - ALPHA) * failureRate;
            if (observation.rttMs() > 0) rtt = rtt == 0 ? observation.rttMs() : ALPHA * observation.rttMs() + (1 - ALPHA) * rtt;
            loss = samples == 0 ? observation.lossRate() : ALPHA * observation.lossRate() + (1 - ALPHA) * loss;
            samples++; updatedAt = observation.observedAt();
        }
        synchronized RelayHealth snapshot(double capacity, double pressure, long runtimeAt) {
            return new RelayHealth(capacity, pressure, failureRate, rtt, loss,
                    Math.max(runtimeAt, updatedAt), samples);
        }
    }
}
