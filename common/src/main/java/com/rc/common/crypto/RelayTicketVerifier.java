package com.rc.common.crypto;

import com.rc.common.protocol.PathType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/** Verifies Ed25519 signatures by key id and parses bounded ticket claims. */
public final class RelayTicketVerifier {
    private static final Base64.Decoder BASE64 = Base64.getUrlDecoder();
    private final Map<String, PublicKey> keys;

    public RelayTicketVerifier(Map<String, PublicKey> keys) {
        this.keys = Map.copyOf(keys);
    }

    public RelayTicketV2 verify(String encoded) {
        if (encoded == null || encoded.length() > 8192) {
            throw new CryptoException("invalid relay ticket size");
        }
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 2) {
            throw new CryptoException("invalid relay ticket format");
        }
        try {
            byte[] payload = BASE64.decode(parts[0]);
            byte[] signature = BASE64.decode(parts[1]);
            // Reject alternative Base64 spellings whose unused tail bits decode to identical bytes.
            // A ticket has one canonical wire representation, which keeps caches and replay guards stable.
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(parts[0])
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1])) {
                throw new CryptoException("non-canonical relay ticket encoding");
            }
            RelayTicketV2 ticket = decode(payload);
            PublicKey key = keys.get(ticket.keyId());
            if (key == null) {
                throw new CryptoException("unknown relay ticket key id");
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new CryptoException("relay ticket signature mismatch");
            }
            return ticket;
        } catch (IllegalArgumentException | java.io.IOException | GeneralSecurityException e) {
            if (e instanceof CryptoException crypto) {
                throw crypto;
            }
            throw new CryptoException("invalid relay ticket", e);
        }
    }

    private static RelayTicketV2 decode(byte[] payload) throws java.io.IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        if (in.readUnsignedByte() != 2) {
            throw new CryptoException("unsupported relay ticket version");
        }
        String issuer = readString(in);
        String keyId = readString(in);
        String tokenId = readString(in);
        long sessionId = in.readLong();
        long routeEpoch = in.readLong();
        String assignment = readString(in);
        String node = readString(in);
        PathType path = PathType.forNumber(in.readInt());
        int roleValue = in.readUnsignedByte();
        if (path == null || roleValue >= RelayTicketV2.PeerRole.values().length) {
            throw new CryptoException("invalid relay ticket enum claim");
        }
        RelayTicketV2 ticket = new RelayTicketV2(issuer, keyId, tokenId, sessionId, routeEpoch,
                assignment, node, path, RelayTicketV2.PeerRole.values()[roleValue],
                in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong());
        if (in.available() != 0) {
            throw new CryptoException("trailing relay ticket bytes");
        }
        return ticket;
    }

    private static String readString(DataInputStream in) throws java.io.IOException {
        int length = in.readUnsignedShort();
        if (length == 0 || length > 1024 || length > in.available()) {
            throw new CryptoException("invalid relay ticket string");
        }
        byte[] bytes = in.readNBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
