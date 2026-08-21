package com.rc.signaling.messaging;

import com.rc.common.protocol.Signal;
import com.rc.signaling.connection.ConnectionContext;
import com.rc.signaling.session.ConnectionRegistry;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

/** Final delivery gate rechecks target connectionEpoch immediately before writing to Netty. */
@Component
public final class ConnectionRegistrySignalDelivery implements LocalSignalDelivery {
    private final ConnectionRegistry connections;

    public ConnectionRegistrySignalDelivery(ConnectionRegistry connections) { this.connections = connections; }

    @Override
    public boolean deliver(DeliveryEnvelope envelope) {
        if (envelope.deadlineAt() <= System.currentTimeMillis()) return false;
        ConnectionContext context = connections.contextOf(envelope.targetDeviceId());
        Channel channel = connections.channelOf(envelope.targetDeviceId());
        if (context == null || channel == null || !channel.isActive()
                || context.connectionEpoch() != envelope.targetConnectionEpoch()) return false;
        try {
            channel.writeAndFlush(Signal.parseFrom(envelope.payload()));
            return true;
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            return false;
        }
    }
}
