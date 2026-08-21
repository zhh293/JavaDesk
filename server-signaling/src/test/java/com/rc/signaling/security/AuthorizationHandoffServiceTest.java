package com.rc.signaling.security;

import com.rc.signaling.api.dto.TokenResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationHandoffServiceTest {
    @Test
    void handoffIsOpaqueAndCanOnlyBeConsumedOnce() {
        AuthorizationHandoffService service = new AuthorizationHandoffService(new InMemoryHandoffCodeStore());
        TokenResponse tokens = new TokenResponse("access-secret", "refresh-secret", "Bearer", 900);

        String code = service.issue(tokens);

        assertThat(code).doesNotContain("access-secret", "refresh-secret").hasSize(43);
        assertThat(service.consume(code)).contains(tokens);
        assertThat(service.consume(code)).isEmpty();
    }
}
