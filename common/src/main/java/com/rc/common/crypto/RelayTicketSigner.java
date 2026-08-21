package com.rc.common.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/** Compact binary RelayTicketV2 encoder and Ed25519 signer. */
public final class RelayTicketSigner {
    private static final byte FORMAT_VERSION = 2;
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final PrivateKey privateKey;

    public RelayTicketSigner(PrivateKey privateKey) {
        this.privateKey = java.util.Objects.requireNonNull(privateKey, "privateKey");
    }

    public String sign(RelayTicketV2 ticket) {
        byte[] payload = encode(ticket);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payload);
            return BASE64.encodeToString(payload) + "." + BASE64.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new CryptoException("relay ticket signing failed", e);
        }
    }

    static byte[] encode(RelayTicketV2 ticket) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            writeString(out, ticket.issuer());
            writeString(out, ticket.keyId());
            writeString(out, ticket.tokenId());
            out.writeLong(ticket.sessionId());
            out.writeLong(ticket.routeEpoch());
            writeString(out, ticket.assignmentId());
            writeString(out, ticket.relayNodeId());
            out.writeInt(ticket.pathType().getNumber());
            out.writeByte(ticket.role().ordinal());
            out.writeLong(ticket.deviceId());
            out.writeLong(ticket.connectionEpoch());
            out.writeLong(ticket.issuedAt());
            out.writeLong(ticket.notBefore());
            out.writeLong(ticket.expiresAt());
            out.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) {
            throw new IllegalArgumentException("relay ticket string too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
