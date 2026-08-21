package com.rc.client.transport;

import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameType;
import com.rc.common.model.ChannelInfo;
import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchableTransportChannelTest {
    @Test
    void listenersSurviveCommitAndStaleEpochCannotReplaceDelegate() {
        FakeChannel first = new FakeChannel(PathType.P2P);
        SwitchableTransportChannel stable = new SwitchableTransportChannel(1, first);
        AtomicInteger received = new AtomicInteger();
        stable.addListener(new Listener(received));
        first.emit(new byte[]{1});

        FakeChannel second = new FakeChannel(PathType.RELAY_UDP);
        assertThat(stable.commit(2, second)).isTrue();
        assertThat(first.closed).isTrue();
        second.emit(new byte[]{2});
        FakeChannel stale = new FakeChannel(PathType.RELAY_TCP);
        assertThat(stable.commit(1, stale)).isFalse();
        assertThat(stale.closed).isTrue();
        assertThat(stable.routeEpoch()).isEqualTo(2);
        assertThat(received).hasValue(2);
    }

    private record Listener(AtomicInteger count) implements TransportListener {
        public void onData(DataFrame frame) { count.incrementAndGet(); }
        public void onClosed(Throwable cause) { }
    }

    static final class FakeChannel implements TransportChannel {
        final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
        final PathType path;
        boolean closed;
        FakeChannel(PathType path) { this.path = path; }
        public void send(ChannelType ch, byte[] payload) { }
        public void addListener(TransportListener listener) { listeners.add(listener); }
        public void removeListener(TransportListener listener) { listeners.remove(listener); }
        public ChannelInfo info() { return new ChannelInfo(path, 0, 0, 0); }
        public void close() { closed = true; }
        void emit(byte[] payload) {
            DataFrame frame = new DataFrame(ChannelType.CONTROL, FrameType.DATA, 0, 0, 0, payload);
            listeners.forEach(l -> l.onData(frame));
        }
    }
}
