package com.rc.signaling.security;

import com.rc.signaling.api.dto.TokenResponse;

import java.time.Duration;
import java.util.Optional;

/** One-time storage used to keep bearer tokens out of browser redirect URLs. */
public interface HandoffCodeStore {
    void put(String code, TokenResponse tokens, Duration ttl);

    Optional<TokenResponse> consume(String code);
}
