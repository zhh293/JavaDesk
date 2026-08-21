package com.rc.signaling.messaging;

import com.rc.common.protocol.Signal;
import com.rc.signaling.connection.ConnectionContext;
import com.rc.signaling.session.ConnectionRegistry;
import io.netty.channel.Channel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Dev router that still enforces target connection fencing and deadlines. */
@Component
@Profile("!prod")
public final class LocalSignalRouter implements SignalRouter {
    private final ConnectionRegistry connections;

    public LocalSignalRouter(ConnectionRegistry connections) { this.connections = connections; }

    @Override
    public boolean route(DeliveryEnvelope envelope) {
        if (envelope.deadlineAt() <= System.currentTimeMillis()) return false;
        ConnectionContext target = connections.contextOf(envelope.targetDeviceId());
        Channel channel = connections.channelOf(envelope.targetDeviceId());
        if (target == null || channel == null || !channel.isActive()
                || target.connectionEpoch() != envelope.targetConnectionEpoch()) return false;
        try {
            channel.writeAndFlush(Signal.parseFrom(envelope.payload()));
            return true;
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("invalid signal payload", e);
        }
    }
}
