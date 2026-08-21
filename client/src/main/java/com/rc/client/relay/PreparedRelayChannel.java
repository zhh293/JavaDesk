package com.rc.client.relay;

import com.rc.client.transport.TransportChannel;

public record PreparedRelayChannel(RelayAssignment assignment, TransportChannel channel) { }
