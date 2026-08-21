package com.rc.signaling.security;

import com.rc.signaling.api.dto.TokenResponse;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

@Service
public final class AuthorizationHandoffService {
    private static final Duration TTL = Duration.ofSeconds(60);
    private final SecureRandom random = new SecureRandom();
    private final HandoffCodeStore store;

    public AuthorizationHandoffService(HandoffCodeStore store) {
        this.store = store;
    }

    public String issue(TokenResponse tokens) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        store.put(code, tokens, TTL);
        return code;
    }

    public Optional<TokenResponse> consume(String code) {
        if (code == null || code.length() < 32 || code.length() > 128) {
            return Optional.empty();
        }
        return store.consume(code);
    }
}
