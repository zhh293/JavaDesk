package com.rc.common.codec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayJoinPayloadV2Test {
    @Test void roundTripsBoundedTicketAndNonce() {
        RelayJoinPayloadV2 payload = new RelayJoinPayloadV2("signed.ticket", "connection-1");
        assertThat(RelayJoinPayloadV2.decode(payload.encode())).isEqualTo(payload);
        assertThatThrownBy(() -> RelayJoinPayloadV2.decode(new byte[]{0, 5, 1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
