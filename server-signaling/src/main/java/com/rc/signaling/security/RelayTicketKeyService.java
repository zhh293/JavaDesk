package com.rc.signaling.security;

import com.rc.common.crypto.RelayTicketSigner;
import com.rc.common.crypto.RelayTicketV2;
import com.rc.signaling.config.SecurityProperties;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Owns the signaling-only Ed25519 private key and exposes only the verification key to Relay. */
@Service
public final class RelayTicketKeyService {
    private final String keyId;
    private final String issuer;
    private final PublicKey publicKey;
    private final RelayTicketSigner signer;
    private final java.util.Map<String, String> verificationKeys;

    public RelayTicketKeyService(SecurityProperties properties) {
        try {
            KeyPair pair = loadOrGenerate(properties);
            keyId = required(properties.getRelayTicketKeyId(), "relay ticket key id");
            issuer = required(properties.getRelayTicketIssuer(), "relay ticket issuer");
            publicKey = pair.getPublic();
            signer = new RelayTicketSigner(pair.getPrivate());
            java.util.LinkedHashMap<String, String> keys = new java.util.LinkedHashMap<>();
            keys.put(keyId, Base64.getEncoder().encodeToString(publicKey.getEncoded()));
            if (properties.getRelayTicketPreviousKeyId() != null
                    && !properties.getRelayTicketPreviousKeyId().isBlank()) {
                if (properties.getRelayTicketPreviousPublicKey() == null
                        || properties.getRelayTicketPreviousPublicKey().isBlank()) {
                    throw new IllegalArgumentException("previous relay ticket public key is required with key id");
                }
                KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(properties.getRelayTicketPreviousPublicKey())));
                keys.put(properties.getRelayTicketPreviousKeyId(), properties.getRelayTicketPreviousPublicKey());
            }
            verificationKeys = java.util.Map.copyOf(keys);
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize Relay ticket signing key", e);
        }
    }

    public String sign(RelayTicketV2 ticket) { return signer.sign(ticket); }
    public String keyId() { return keyId; }
    public String issuer() { return issuer; }
    public String publicKeyBase64() { return Base64.getEncoder().encodeToString(publicKey.getEncoded()); }
    public java.util.Map<String, String> verificationKeys() { return verificationKeys; }

    private static KeyPair loadOrGenerate(SecurityProperties p) throws Exception {
        if (p.getRelayTicketPrivateKey() == null || p.getRelayTicketPrivateKey().isBlank()) {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        }
        if (p.getRelayTicketPublicKey() == null || p.getRelayTicketPublicKey().isBlank()) {
            throw new IllegalArgumentException("relay ticket public key is required with private key");
        }
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(p.getRelayTicketPrivateKey())));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(p.getRelayTicketPublicKey())));
        return new KeyPair(publicKey, privateKey);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
