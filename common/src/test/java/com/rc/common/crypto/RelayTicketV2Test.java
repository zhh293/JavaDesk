package com.rc.common.crypto;

import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayTicketV2Test {
    @Test
    void signatureAndAllBindingClaimsAreVerified() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        long now = System.currentTimeMillis() / 1000;
        RelayTicketV2 ticket = new RelayTicketV2("signaling", "kid-1", "jti-1", 7, 3,
                "assignment-1", "relay-a", PathType.RELAY_UDP,
                RelayTicketV2.PeerRole.CONTROLLER, 11, 5, now, now, now + 30);
        String encoded = new RelayTicketSigner(pair.getPrivate()).sign(ticket);
        RelayTicketV2 decoded = new RelayTicketVerifier(Map.of("kid-1", pair.getPublic())).verify(encoded);

        assertThat(decoded).isEqualTo(ticket);
        decoded.validateFor("relay-a", PathType.RELAY_UDP, now);
        assertThatThrownBy(() -> decoded.validateFor("relay-b", PathType.RELAY_UDP, now))
                .isInstanceOf(CryptoException.class);

        char replacement = encoded.charAt(encoded.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = encoded.substring(0, encoded.length() - 1) + replacement;
        assertThatThrownBy(() -> new RelayTicketVerifier(Map.of("kid-1", pair.getPublic())).verify(tampered))
                .isInstanceOf(CryptoException.class);
    }
}
