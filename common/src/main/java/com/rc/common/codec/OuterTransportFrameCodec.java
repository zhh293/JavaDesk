package com.rc.common.codec;

import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameType;

import java.nio.ByteBuffer;

/** Binary codecs for the encrypted inner frame and minimal outer frame. */
public final class OuterTransportFrameCodec {
    private static final int OUTER_HEADER = 1 + 8 + 8 + 2 + 2 + 4 + 4;
    private static final int INNER_HEADER = 1 + 1 + 2 + 4 + 8 + 4;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private OuterTransportFrameCodec() {
    }

    public static byte[] encode(OuterTransportFrame frame) {
        byte[] cipher = frame.ciphertext();
        return ByteBuffer.allocate(OUTER_HEADER + cipher.length)
                .put(frame.version()).putLong(frame.sessionId()).putLong(frame.routeEpoch())
                .putShort(frame.directionId()).putShort(frame.streamId())
                .putInt((int) frame.packetSequence()).putInt(cipher.length).put(cipher).array();
    }

    public static OuterTransportFrame decode(byte[] bytes) {
        if (bytes == null || bytes.length < OUTER_HEADER) {
            throw new IllegalArgumentException("outer frame truncated");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        byte version = in.get();
        long sessionId = in.getLong();
        long epoch = in.getLong();
        short direction = in.getShort();
        short stream = in.getShort();
        long seq = Integer.toUnsignedLong(in.getInt());
        int length = in.getInt();
        if (length < 0 || length > MAX_PAYLOAD || length != in.remaining()) {
            throw new IllegalArgumentException("invalid outer ciphertext length");
        }
        byte[] cipher = new byte[length];
        in.get(cipher);
        return new OuterTransportFrame(version, sessionId, epoch, direction, stream, seq, cipher);
    }

    public static byte[] encodeInner(EncryptedInnerFrame frame) {
        byte[] payload = frame.payload();
        return ByteBuffer.allocate(INNER_HEADER + payload.length)
                .put(frame.channel().code()).put(frame.type().code()).putShort((short) frame.flags())
                .putInt(frame.streamSequence()).putLong(frame.timestamp())
                .putInt(payload.length).put(payload).array();
    }

    public static EncryptedInnerFrame decodeInner(byte[] bytes) {
        if (bytes == null || bytes.length < INNER_HEADER) {
            throw new IllegalArgumentException("inner frame truncated");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        ChannelType channel = ChannelType.of(in.get());
        FrameType type = FrameType.of(in.get());
        int flags = Short.toUnsignedInt(in.getShort());
        int seq = in.getInt();
        long timestamp = in.getLong();
        int length = in.getInt();
        if (length < 0 || length > MAX_PAYLOAD || length != in.remaining()) {
            throw new IllegalArgumentException("invalid inner payload length");
        }
        byte[] payload = new byte[length];
        in.get(payload);
        return new EncryptedInnerFrame(channel, type, flags, seq, timestamp, payload);
    }

    public static byte[] aad(OuterTransportFrame frame) {
        return ByteBuffer.allocate(1 + 8 + 8 + 2 + 2 + 4)
                .put(frame.version()).putLong(frame.sessionId()).putLong(frame.routeEpoch())
                .putShort(frame.directionId()).putShort(frame.streamId())
                .putInt((int) frame.packetSequence()).array();
    }
}
