package com.rc.client.relay;

public record RelayOutcome(String nodeId, boolean success, String reason, long latencyMillis) { }
