package com.rc.signaling.connection;

/** Authenticated, node-local actor identity attached to a Netty connection. */
public record ConnectionContext(long userId, long deviceId, String connectionId, long connectionEpoch) {
}
