package com.rc.client.transport;

import com.rc.common.codec.DataFrame;
import com.rc.common.codec.EncryptedInnerFrame;
import com.rc.common.codec.OuterTransportFrame;
import com.rc.common.codec.OuterTransportFrameCodec;
import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameFlags;
import com.rc.common.constant.FrameType;
import com.rc.common.crypto.AesGcmCipher;
import com.rc.common.crypto.CryptoException;
import com.rc.common.crypto.DataNonceFactory;
import com.rc.common.crypto.ReplayWindow;
import com.rc.common.crypto.SessionKeySchedule;
import com.rc.common.crypto.SessionKeySchedule.Direction;
import com.rc.common.model.ChannelInfo;
import com.rc.common.protocol.PathType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Mandatory end-to-end encryption decorator hiding business channel/type/payload from relay paths. */
public final class SecureTransportChannel implements TransportChannel, TransportListener {
    public enum LocalRole { CONTROLLER, AGENT }

    private final TransportChannel delegate;
    private final long sessionId;
    private final long routeEpoch;
    private final Direction sendDirection;
    private final Direction receiveDirection;
    private final byte[] sendKey;
    private final byte[] receiveKey;
    private final Map<ChannelType, AtomicLong> sendSequences = new EnumMap<>(ChannelType.class);
    private final Map<ChannelType, ReplayWindow> replayWindows = new EnumMap<>(ChannelType.class);
    private final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public SecureTransportChannel(TransportChannel delegate, byte[] sessionMasterKey,
                                  long sessionId, long routeEpoch, PathType pathType, LocalRole role) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.sessionId = sessionId;
        this.routeEpoch = routeEpoch;
        this.sendDirection = role == LocalRole.CONTROLLER
                ? Direction.CONTROLLER_TO_AGENT : Direction.AGENT_TO_CONTROLLER;
        this.receiveDirection = role == LocalRole.CONTROLLER
                ? Direction.AGENT_TO_CONTROLLER : Direction.CONTROLLER_TO_AGENT;
        this.sendKey = SessionKeySchedule.directionKey(sessionMasterKey, routeEpoch, pathType, sendDirection);
        this.receiveKey = SessionKeySchedule.directionKey(sessionMasterKey, routeEpoch, pathType, receiveDirection);
        for (ChannelType channel : ChannelType.values()) {
            sendSequences.put(channel, new AtomicLong());
            replayWindows.put(channel, new ReplayWindow());
        }
        delegate.addListener(this);
    }

    @Override
    public void send(ChannelType channel, byte[] payload) {
        if (closed) {
            throw new IllegalStateException("secure channel is closed");
        }
        long sequence = sendSequences.get(channel).getAndIncrement();
        if (sequence > 0xffff_ffffL) {
            close();
            throw new IllegalStateException("secure transport sequence exhausted; new route key required");
        }
        short streamId = (short) Byte.toUnsignedInt(channel.code());
        EncryptedInnerFrame inner = new EncryptedInnerFrame(channel, FrameType.DATA, FrameFlags.NONE,
                (int) sequence, System.currentTimeMillis(), payload);
        OuterTransportFrame header = new OuterTransportFrame(OuterTransportFrame.VERSION, sessionId,
                routeEpoch, sendDirection.id(), streamId, sequence, new byte[0]);
        byte[] nonce = DataNonceFactory.create(routeEpoch, sendDirection.id(), streamId, sequence);
        byte[] cipher = AesGcmCipher.encrypt(sendKey, nonce,
                OuterTransportFrameCodec.encodeInner(inner), OuterTransportFrameCodec.aad(header));
        OuterTransportFrame outer = new OuterTransportFrame(OuterTransportFrame.VERSION, sessionId,
                routeEpoch, sendDirection.id(), streamId, sequence, cipher);
        // A constant outer channel prevents relay-visible business channel classification.
        delegate.send(ChannelType.CONTROL, OuterTransportFrameCodec.encode(outer));
    }

    @Override
    public void onData(DataFrame frame) {
        if (closed) {
            return;
        }
        try {
            OuterTransportFrame outer = OuterTransportFrameCodec.decode(frame.payload());
            if (outer.sessionId() != sessionId || outer.routeEpoch() != routeEpoch
                    || outer.directionId() != receiveDirection.id()) {
                throw new CryptoException("stale or misdirected secure transport frame");
            }
            ChannelType stream = ChannelType.of((byte) outer.streamId());
            byte[] nonce = DataNonceFactory.create(routeEpoch, receiveDirection.id(),
                    outer.streamId(), outer.packetSequence());
            byte[] plaintext = AesGcmCipher.decrypt(receiveKey, nonce, outer.ciphertext(),
                    OuterTransportFrameCodec.aad(outer));
            EncryptedInnerFrame inner = OuterTransportFrameCodec.decodeInner(plaintext);
            if (inner.channel() != stream || Integer.toUnsignedLong(inner.streamSequence()) != outer.packetSequence()) {
                throw new CryptoException("secure inner/outer sequence mismatch");
            }
            if (!replayWindows.get(stream).accept(outer.packetSequence())) {
                return;
            }
            DataFrame decoded = new DataFrame(inner.channel(), inner.type(), inner.flags(),
                    inner.streamSequence(), inner.timestamp(), inner.payload());
            for (TransportListener listener : listeners) {
                listener.onData(decoded);
            }
        } catch (RuntimeException ignored) {
            // Authentication, framing and replay failures are intentionally fail-closed.
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        if (!closed) {
            for (TransportListener listener : listeners) {
                listener.onClosed(cause);
            }
        }
    }

    @Override
    public void addListener(TransportListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void removeListener(TransportListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ChannelInfo info() {
        return delegate.info();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        delegate.removeListener(this);
        delegate.close();
        java.util.Arrays.fill(sendKey, (byte) 0);
        java.util.Arrays.fill(receiveKey, (byte) 0);
        listeners.clear();
    }
}
