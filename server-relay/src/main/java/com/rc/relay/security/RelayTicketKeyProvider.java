package com.rc.relay.security;

import com.rc.common.crypto.CryptoException;
import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.crypto.RelayTicketVerifier;
import com.rc.relay.config.RelayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Refreshing Ed25519 verification-key cache sourced from the signaling internal API. */
public final class RelayTicketKeyProvider implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RelayTicketKeyProvider.class);
    private static final Pattern KEY = Pattern.compile(
            "\\\"keyId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^}]*\\\"algorithm\\\"\\s*:\\s*\\\"Ed25519\\\"[^}]*\\\"publicKey\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI endpoint;
    private final String internalToken;
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rc-relay-ticket-keys"); t.setDaemon(true); return t;
    });
    private volatile RelayTicketVerifier verifier;

    public RelayTicketKeyProvider(RelayConfig config) {
        URI heartbeat = URI.create(config.signalingUrl());
        this.endpoint = heartbeat.resolve("/internal/relay-ticket-keys");
        this.internalToken = config.internalServiceToken();
        refreshSafely();
        refresher.scheduleWithFixedDelay(this::refreshSafely, 10, 60, TimeUnit.SECONDS);
    }

    public RelayTicketV2 verify(String encoded) {
        RelayTicketVerifier current = verifier;
        if (current == null) throw new CryptoException("relay ticket verification keys unavailable");
        try {
            return current.verify(encoded);
        } catch (CryptoException first) {
            refreshSafely();
            RelayTicketVerifier refreshed = verifier;
            if (refreshed == null || refreshed == current) throw first;
            return refreshed.verify(encoded);
        }
    }

    private void refreshSafely() {
        try { refresh(); } catch (RuntimeException e) { log.warn("relay ticket key refresh failed: {}", e.getMessage()); }
    }

    private synchronized void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("X-RC-Internal-Token", internalToken).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("ticket key endpoint returned " + response.statusCode());
            }
            Matcher matcher = KEY.matcher(response.body());
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            while (matcher.find()) {
                keys.put(matcher.group(1), factory.generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(matcher.group(2)))));
            }
            if (keys.isEmpty()) throw new IllegalStateException("no Ed25519 verification keys returned");
            verifier = new RelayTicketVerifier(keys);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ticket key refresh interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("ticket key refresh failed", e);
        }
    }

    @Override public void close() { refresher.shutdownNow(); }
}
