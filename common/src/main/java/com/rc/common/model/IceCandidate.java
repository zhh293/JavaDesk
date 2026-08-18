package com.rc.common.model;

import com.rc.common.protocol.CandidateType;

/**
 * ICE 候选（host / srflx / relay）。
 */
public record IceCandidate(
        CandidateType type,
        String ip,
        int port,
        long priority,
        String ufrag,
        String password,
        String sdpMid,
        int sdpMlineIndex) {

    public Endpoint endpoint() {
        return new Endpoint(ip, port);
    }
}
