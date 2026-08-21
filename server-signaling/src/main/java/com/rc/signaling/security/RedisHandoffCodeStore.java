package com.rc.signaling.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.signaling.api.dto.TokenResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@Profile("prod")
public final class RedisHandoffCodeStore implements HandoffCodeStore {
    private static final String KEY_PREFIX = "rc:v2:auth:handoff:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisHandoffCodeStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void put(String code, TokenResponse tokens, Duration ttl) {
        try {
            redis.opsForValue().set(KEY_PREFIX + code, objectMapper.writeValueAsString(tokens), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize SSO handoff", e);
        }
    }

    @Override
    public Optional<TokenResponse> consume(String code) {
        String value = redis.opsForValue().getAndDelete(KEY_PREFIX + code);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, TokenResponse.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot deserialize SSO handoff", e);
        }
    }
}
