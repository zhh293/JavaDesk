package com.rc.common.codec;

import com.rc.common.crypto.RelayTicketV2.PeerRole;
import com.rc.common.protocol.PathType;

import java.nio.ByteBuffer;

/** Strict relay V2 envelope with epoch/path/role/sequence fencing. */
public final class RelayPacketCodecV2 {
    public static final int MAGIC = 0x52435632; // RCV2
    public static final byte VERSION = 2;
    public static final int HEADER_SIZE = 4 + 1 + 1 + 2 + 8 + 8 + 1 + 1 + 4 + 4;
    public static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    public enum Type {
        JOIN(1), JOIN_ACCEPTED(2), PEER_READY(3), JOIN_REJECTED(4),
        DATA(5), PING(6), PONG(7), LEAVE(8);
        private final int code;
        Type(int code) { this.code = code; }
        static Type of(int code) {
            for (Type value : values()) if (value.code == code) return value;
            throw new IllegalArgumentException("unknown relay packet type");
        }
    }

    public record Packet(Type type, int flags, long sessionId, long routeEpoch,
                         PathType pathType, PeerRole role, long sequence, byte[] payload) {
        public Packet {
            if (sessionId <= 0 || routeEpoch <= 0 || sequence < 0 || sequence > 0xffff_ffffL) {
                throw new IllegalArgumentException("invalid relay v2 packet metadata");
            }
            payload = payload == null ? new byte[0] : payload.clone();
        }
    }

    private RelayPacketCodecV2() { }

    public static byte[] encode(Packet packet) {
        if (packet.payload().length > MAX_PAYLOAD) throw new IllegalArgumentException("payload too large");
        return ByteBuffer.allocate(HEADER_SIZE + packet.payload().length)
                .putInt(MAGIC).put(VERSION).put((byte) packet.type().code)
                .putShort((short) packet.flags()).putLong(packet.sessionId()).putLong(packet.routeEpoch())
                .put((byte) packet.pathType().getNumber()).put((byte) packet.role().ordinal())
                .putInt((int) packet.sequence()).putInt(packet.payload().length)
                .put(packet.payload()).array();
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_SIZE) throw new IllegalArgumentException("packet truncated");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (in.getInt() != MAGIC || in.get() != VERSION) throw new IllegalArgumentException("bad relay header");
        Type type = Type.of(Byte.toUnsignedInt(in.get()));
        int flags = Short.toUnsignedInt(in.getShort());
        long sessionId = in.getLong();
        long epoch = in.getLong();
        PathType path = PathType.forNumber(Byte.toUnsignedInt(in.get()));
        int roleValue = Byte.toUnsignedInt(in.get());
        long sequence = Integer.toUnsignedLong(in.getInt());
        int length = in.getInt();
        if (path == null || roleValue >= PeerRole.values().length || length < 0
                || length > MAX_PAYLOAD || length != in.remaining()) {
            throw new IllegalArgumentException("invalid relay packet fields");
        }
        byte[] payload = new byte[length];
        in.get(payload);
        return new Packet(type, flags, sessionId, epoch, path, PeerRole.values()[roleValue], sequence, payload);
    }
}
