package com.rc.signaling.connection;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Redis Cluster-safe connection lease store using same-slot Lua fencing operations. */
@Component
@Profile("prod")
public final class RedisConnectionLeaseStore implements ConnectionLeaseStore {
    private static final DefaultRedisScript<Long> REGISTER = new DefaultRedisScript<>("""
            local epoch = redis.call('INCR', KEYS[1])
            redis.call('HSET', KEYS[2],
              'deviceId', ARGV[1], 'userId', ARGV[2], 'nodeId', ARGV[3],
              'connectionId', ARGV[4], 'connectionEpoch', epoch,
              'clientInstanceId', ARGV[5], 'connectedAt', ARGV[6],
              'leaseExpireAt', ARGV[7], 'clientVersion', ARGV[8], 'protocolVersion', ARGV[9])
            redis.call('PEXPIRE', KEYS[2], ARGV[10])
            return epoch
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'connectionId') ~= ARGV[1] or
               redis.call('HGET', KEYS[1], 'connectionEpoch') ~= ARGV[2] then return 0 end
            redis.call('HSET', KEYS[1], 'leaseExpireAt', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> DELETE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'connectionId') ~= ARGV[1] or
               redis.call('HGET', KEYS[1], 'connectionEpoch') ~= ARGV[2] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisConnectionLeaseStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ConnectionLease register(long deviceId, long userId, String nodeId, String connectionId,
                                    String clientInstanceId, String clientVersion,
                                    String protocolVersion, Duration ttl) {
        requireTtl(ttl);
        long now = System.currentTimeMillis();
        long expires = Math.addExact(now, ttl.toMillis());
        Long epoch = redis.execute(REGISTER, List.of(epochKey(deviceId), leaseKey(deviceId)),
                Long.toString(deviceId), Long.toString(userId), nodeId, connectionId,
                clientInstanceId, Long.toString(now), Long.toString(expires),
                safe(clientVersion), safe(protocolVersion), Long.toString(ttl.toMillis()));
        if (epoch == null || epoch <= 0) throw new IllegalStateException("Redis lease register failed");
        return new ConnectionLease(deviceId, userId, nodeId, connectionId, epoch,
                clientInstanceId, now, expires, safe(clientVersion), safe(protocolVersion));
    }

    @Override
    public boolean renew(long deviceId, String connectionId, long connectionEpoch, Duration ttl) {
        requireTtl(ttl);
        long expires = Math.addExact(System.currentTimeMillis(), ttl.toMillis());
        Long result = redis.execute(RENEW, List.of(leaseKey(deviceId)), connectionId,
                Long.toString(connectionEpoch), Long.toString(expires), Long.toString(ttl.toMillis()));
        return result != null && result == 1;
    }

    @Override
    public boolean delete(long deviceId, String connectionId, long connectionEpoch) {
        Long result = redis.execute(DELETE, List.of(leaseKey(deviceId)),
                connectionId, Long.toString(connectionEpoch));
        return result != null && result == 1;
    }

    @Override
    public Optional<ConnectionLease> find(long deviceId) {
        Map<Object, Object> hash = redis.opsForHash().entries(leaseKey(deviceId));
        if (hash.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new ConnectionLease(
                    number(hash, "deviceId"), number(hash, "userId"), text(hash, "nodeId"),
                    text(hash, "connectionId"), number(hash, "connectionEpoch"),
                    text(hash, "clientInstanceId"), number(hash, "connectedAt"),
                    number(hash, "leaseExpireAt"), text(hash, "clientVersion"),
                    text(hash, "protocolVersion")));
        } catch (RuntimeException e) {
            throw new IllegalStateException("corrupt Redis connection lease for device " + deviceId, e);
        }
    }

    private static String leaseKey(long id) { return "rc:v2:device:{" + id + "}:lease"; }
    private static String epochKey(long id) { return "rc:v2:device:{" + id + "}:connection-epoch"; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String text(Map<Object, Object> hash, String field) {
        Object value = hash.get(field);
        if (value == null) throw new IllegalStateException("missing " + field);
        return value.toString();
    }
    private static long number(Map<Object, Object> hash, String field) {
        return Long.parseLong(text(hash, field));
    }
    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("invalid ttl");
    }
}
