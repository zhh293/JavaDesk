package com.rc.client.relay;

import com.rc.common.protocol.PathType;

public record RelayEndpoint(String nodeId, String host, int port, boolean tls, PathType pathType) {
    public RelayEndpoint {
        if (nodeId == null || nodeId.isBlank() || host == null || host.isBlank()
                || port < 1 || port > 65535) throw new IllegalArgumentException("invalid relay endpoint");
    }
}
