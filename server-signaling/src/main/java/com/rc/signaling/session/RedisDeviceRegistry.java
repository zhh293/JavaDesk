package com.rc.signaling.session;

import com.rc.signaling.config.SignalingProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 实现（prod）：{@code device:online:{deviceId}} → 信令节点 ID，TTL 心跳续期。
 */
@Component
@Profile("prod")
public class RedisDeviceRegistry implements DeviceRegistry {

    private static final String KEY_PREFIX = "device:online:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisDeviceRegistry(StringRedisTemplate redis, SignalingProperties props) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(props.getDeviceTtlSeconds());
    }

    @Override
    public void online(long deviceId, String nodeId) {
        redis.opsForValue().set(key(deviceId), nodeId, ttl);
    }

    @Override
    public void heartbeat(long deviceId, String nodeId) {
        redis.opsForValue().set(key(deviceId), nodeId, ttl);
    }

    @Override
    public void offline(long deviceId) {
        redis.delete(key(deviceId));
    }

    @Override
    public boolean isOnline(long deviceId) {
        return Boolean.TRUE.equals(redis.hasKey(key(deviceId)));
    }

    @Override
    public String nodeOf(long deviceId) {
        return redis.opsForValue().get(key(deviceId));
    }

    private String key(long deviceId) {
        return KEY_PREFIX + deviceId;
    }
}
