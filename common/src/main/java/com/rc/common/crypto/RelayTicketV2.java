package com.rc.common.crypto;

import com.rc.common.protocol.PathType;

import java.util.Objects;

/** Ed25519-signed, relay/epoch/path/role-bound admission claims. */
public record RelayTicketV2(
        String issuer,
        String keyId,
        String tokenId,
        long sessionId,
        long routeEpoch,
        String assignmentId,
        String relayNodeId,
        PathType pathType,
        PeerRole role,
        long deviceId,
        long connectionEpoch,
        long issuedAt,
        long notBefore,
        long expiresAt) {

    public enum PeerRole { CONTROLLER, AGENT }

    public RelayTicketV2 {
        issuer = required(issuer, "issuer", 128);
        keyId = required(keyId, "keyId", 128);
        tokenId = required(tokenId, "tokenId", 128);
        assignmentId = required(assignmentId, "assignmentId", 128);
        relayNodeId = required(relayNodeId, "relayNodeId", 128);
        Objects.requireNonNull(pathType, "pathType");
        Objects.requireNonNull(role, "role");
        if (sessionId <= 0 || routeEpoch <= 0 || deviceId <= 0 || connectionEpoch <= 0) {
            throw new IllegalArgumentException("ticket ids and epochs must be positive");
        }
        if (issuedAt <= 0 || notBefore < issuedAt || expiresAt <= notBefore) {
            throw new IllegalArgumentException("invalid relay ticket time range");
        }
    }

    public void validateFor(String expectedNode, PathType expectedPath, long nowEpochSeconds) {
        if (!relayNodeId.equals(expectedNode)) {
            throw new CryptoException("relay ticket bound to another node");
        }
        if (pathType != expectedPath) {
            throw new CryptoException("relay ticket bound to another path");
        }
        if (nowEpochSeconds < notBefore || nowEpochSeconds >= expiresAt) {
            throw new CryptoException("relay ticket not currently valid");
        }
    }

    private static String required(String value, String name, int maxLength) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " length invalid");
        }
        return value;
    }
}
