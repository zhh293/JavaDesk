package com.rc.signaling.security;

import com.rc.signaling.api.dto.TokenResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!prod")
public final class InMemoryHandoffCodeStore implements HandoffCodeStore {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void put(String code, TokenResponse tokens, Duration ttl) {
        entries.put(code, new Entry(tokens, Math.addExact(System.currentTimeMillis(), ttl.toMillis())));
    }

    @Override
    public Optional<TokenResponse> consume(String code) {
        Entry entry = entries.remove(code);
        if (entry == null || entry.expiresAt <= System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry.tokens);
    }

    private record Entry(TokenResponse tokens, long expiresAt) { }
}
