package com.rc.signaling.api;

import com.rc.signaling.security.RelayTicketKeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/relay-ticket-keys")
public final class RelayTicketKeyController {
    private final RelayTicketKeyService keys;
    public RelayTicketKeyController(RelayTicketKeyService keys) { this.keys = keys; }

    @GetMapping
    public ApiResult<List<VerificationKey>> keys() {
        return ApiResult.ok(keys.verificationKeys().entrySet().stream()
                .map(entry -> new VerificationKey(entry.getKey(), "Ed25519", entry.getValue())).toList());
    }

    public record VerificationKey(String keyId, String algorithm, String publicKey) { }
}
