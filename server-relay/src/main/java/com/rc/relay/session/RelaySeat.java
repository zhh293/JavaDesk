package com.rc.relay.session;

import com.rc.common.crypto.RelayTicketV2.PeerRole;
import com.rc.common.model.Endpoint;

public record RelaySeat(PeerRole role, long deviceId, String tokenId, String connectionNonce,
                        Endpoint udpEndpoint, long joinedAt, long lastActivityAt, long lastSequence) {
}
