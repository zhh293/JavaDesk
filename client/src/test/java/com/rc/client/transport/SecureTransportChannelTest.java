package com.rc.client.transport;

import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameType;
import com.rc.common.crypto.AesGcmCipher;
import com.rc.common.model.ChannelInfo;
import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SecureTransportChannelTest {
    @Test
    void encryptsBusinessMetadataAndPayloadAndRejectsReplayAndTampering() {
        LoopChannel controllerWire = new LoopChannel();
        LoopChannel agentWire = new LoopChannel();
        controllerWire.peer = agentWire;
        agentWire.peer = controllerWire;
        byte[] master = AesGcmCipher.generateKey();
        SecureTransportChannel controller = new SecureTransportChannel(controllerWire, master, 9, 2,
                PathType.RELAY_UDP, SecureTransportChannel.LocalRole.CONTROLLER);
        SecureTransportChannel agent = new SecureTransportChannel(agentWire, master, 9, 2,
                PathType.RELAY_UDP, SecureTransportChannel.LocalRole.AGENT);
        AtomicInteger count = new AtomicInteger();
        AtomicReference<DataFrame> received = new AtomicReference<>();
        agent.addListener(new TransportListener() {
            public void onData(DataFrame frame) { count.incrementAndGet(); received.set(frame); }
            public void onClosed(Throwable cause) { }
        });

        byte[] secret = "visible-business-secret".getBytes(StandardCharsets.UTF_8);
        controller.send(ChannelType.FILE, secret);
        assertThat(received.get().channel()).isEqualTo(ChannelType.FILE);
        assertThat(received.get().payload()).isEqualTo(secret);
        assertThat(new String(controllerWire.lastPayload, StandardCharsets.UTF_8)).doesNotContain("visible-business-secret");

        agentWire.emit(controllerWire.lastPayload); // replay
        assertThat(count).hasValue(1);
        byte[] tampered = controllerWire.lastPayload.clone();
        tampered[tampered.length - 1] ^= 1;
        agentWire.emit(tampered);
        assertThat(count).hasValue(1);
    }

    static final class LoopChannel implements TransportChannel {
        final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
        LoopChannel peer;
        byte[] lastPayload;
        public void send(ChannelType ch, byte[] payload) { lastPayload = payload.clone(); peer.emit(payload); }
        void emit(byte[] payload) {
            DataFrame frame = new DataFrame(ChannelType.CONTROL, FrameType.DATA, 0, 0, 0, payload);
            listeners.forEach(l -> l.onData(frame));
        }
        public void addListener(TransportListener listener) { listeners.add(listener); }
        public void removeListener(TransportListener listener) { listeners.remove(listener); }
        public ChannelInfo info() { return new ChannelInfo(PathType.RELAY_UDP, 0, 0, 0); }
        public void close() { }
    }
}
