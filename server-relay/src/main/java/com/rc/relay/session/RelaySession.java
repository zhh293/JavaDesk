package com.rc.relay.session;

public record RelaySession(RelaySessionKey key, RelaySeat controllerSeat, RelaySeat agentSeat,
                           long lastActivityAt) {
    public boolean peerReady() { return controllerSeat != null && agentSeat != null; }
}
