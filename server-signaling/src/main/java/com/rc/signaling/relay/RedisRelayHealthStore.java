package com.rc.signaling.relay;

import com.rc.common.protocol.PathType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shared runtime/observation EWMA so all signaling nodes schedule from the same measurements. */
@Component
@Profile("prod")
public final class RedisRelayHealthStore implements RelayHealthStore {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final DefaultRedisScript<Long> OBSERVE = new DefaultRedisScript<>("""
            local samples = tonumber(redis.call('HGET', KEYS[1], 'samples') or '0')
            local alpha = tonumber(ARGV[1])
            local function ewma(field, value)
              local old = tonumber(redis.call('HGET', KEYS[1], field) or '0')
              if samples == 0 then return value else return alpha * value + (1-alpha) * old end
            end
            local rttValue = tonumber(ARGV[3])
            local rtt = tonumber(redis.call('HGET', KEYS[1], 'rtt') or '0')
            if rttValue > 0 then rtt = ewma('rtt', rttValue) end
            redis.call('HSET', KEYS[1],
              'failureRate', ewma('failureRate', tonumber(ARGV[2])),
              'rtt', rtt,
              'loss', ewma('loss', tonumber(ARGV[4])),
              'samples', samples + 1, 'updatedAt', ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            return samples + 1
            """, Long.class);
    private final StringRedisTemplate redis;
    public RedisRelayHealthStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void updateRuntime(RelayRuntimeSample s) {
        String key = runtimeKey(s.nodeId());
        redis.opsForHash().putAll(key, Map.of("active", Integer.toString(s.activeSessions()),
                "capacity", Integer.toString(s.capacity()), "capacityRatio", Double.toString(s.capacityRatio()),
                "cpu", Double.toString(s.cpuRatio()), "bandwidth", Double.toString(s.bandwidthRatio()),
                "directMemory", Double.toString(s.directMemoryRatio()), "updatedAt", Long.toString(s.observedAt())));
        redis.expire(key, TTL);
    }

    @Override
    public void record(RelayObservation o) {
        redis.execute(OBSERVE, List.of(observationKey(o.nodeId(), o.dimension())), "0.25",
                o.success() ? "0" : "1", Double.toString(Math.max(0, o.rttMs())),
                Double.toString(o.lossRate()), Long.toString(o.observedAt()), Long.toString(TTL.toMillis()));
    }

    @Override
    public RelayHealth health(String nodeId, String region, String provider, PathType path) {
        Map<Object, Object> rt = redis.opsForHash().entries(runtimeKey(nodeId));
        Map<Object, Object> ob = redis.opsForHash().entries(observationKey(nodeId,
                normalize(region) + ":" + normalize(provider) + ":" + path.name()));
        double capacity = number(rt, "capacityRatio");
        double pressure = Math.max(number(rt, "cpu"), Math.max(number(rt, "bandwidth"), number(rt, "directMemory")));
        return new RelayHealth(capacity, pressure, number(ob, "failureRate"), number(ob, "rtt"),
                number(ob, "loss"), Math.max(integer(rt, "updatedAt"), integer(ob, "updatedAt")),
                integer(ob, "samples"));
    }

    private static String runtimeKey(String nodeId) { return "rc:v2:relay:{" + nodeId + "}:runtime"; }
    private static String observationKey(String nodeId, String dimension) {
        return "rc:v2:relay:{" + nodeId + "}:observation:" + dimension;
    }
    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
    private static double number(Map<Object, Object> values, String key) {
        Object value = values.get(key); return value == null ? 0 : Double.parseDouble(value.toString());
    }
    private static long integer(Map<Object, Object> values, String key) {
        Object value = values.get(key); return value == null ? 0 : Long.parseLong(value.toString());
    }
}
