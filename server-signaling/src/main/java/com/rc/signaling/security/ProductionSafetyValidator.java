package com.rc.signaling.security;

import com.rc.signaling.config.SecurityProperties;
import com.rc.signaling.config.SignalingProperties;
import com.rc.signaling.config.NacosProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Refuses to start a production node with development identities, secrets or plaintext signaling. */
@Component
@Profile("prod")
public final class ProductionSafetyValidator implements SmartInitializingSingleton {
    private final SignalingProperties signaling;
    private final SecurityProperties security;
    private final Environment environment;
    private final NacosProperties nacos;

    public ProductionSafetyValidator(SignalingProperties signaling, SecurityProperties security,
                                     Environment environment, NacosProperties nacos) {
        this.signaling = signaling;
        this.security = security;
        this.environment = environment;
        this.nacos = nacos;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> violations = new ArrayList<>();
        if (blank(signaling.getNodeId()) || "node-1".equals(signaling.getNodeId())) {
            violations.add("unique rc.signaling.node-id is required");
        }
        if (!signaling.isTls() || blank(signaling.getCertFile()) || blank(signaling.getKeyFile())) {
            violations.add("signaling TLS certificate and private key are required");
        }
        if (weak(security.getJwtSecret(), "dev-secret", "change-me")) {
            violations.add("production JWT secret is required");
        }
        if (weak(security.getInternalServiceToken(), "dev-token", "change-me")) {
            violations.add("production internal service credential is required");
        }
        if (blank(security.getRelayTicketPrivateKey()) || blank(security.getRelayTicketPublicKey())
                || blank(security.getRelayTicketKeyId())) {
            violations.add("production Ed25519 Relay ticket key pair and key id are required");
        }
        if ("localhost".equalsIgnoreCase(environment.getProperty("spring.data.redis.host"))) {
            violations.add("production Redis must not use localhost");
        }
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");
        if (datasourceUrl.contains("localhost") || datasourceUrl.contains("127.0.0.1")) {
            violations.add("production datasource must not use a loopback host");
        }
        if (weak(environment.getProperty("spring.datasource.password"), "change-me", "password")) {
            violations.add("production datasource password is required");
        }
        if (!nacos.isEnabled() || blank(nacos.getServerAddr())) {
            violations.add("production Nacos Relay discovery is required");
        } else if (nacos.getServerAddr().contains("127.0.0.1") || nacos.getServerAddr().contains("localhost")) {
            violations.add("production Nacos must not use a loopback host");
        }
        if (weak(nacos.getPassword(), "nacos", "change-me")) {
            violations.add("production Nacos credentials must be overridden");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("unsafe production configuration: " + String.join("; ", violations));
        }
    }

    private static boolean weak(String value, String... markers) {
        if (blank(value) || value.length() < 32) {
            return true;
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        for (String marker : markers) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
