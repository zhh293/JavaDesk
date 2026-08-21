package com.rc.common.codec;

/** Minimal relay-visible envelope. */
public record OuterTransportFrame(byte version, long sessionId, long routeEpoch,
                                  short directionId, short streamId, long packetSequence,
                                  byte[] ciphertext) {
    public static final byte VERSION = 2;

    public OuterTransportFrame {
        if (version != VERSION || sessionId <= 0 || routeEpoch < 0
                || packetSequence < 0 || packetSequence > 0xffff_ffffL) {
            throw new IllegalArgumentException("invalid outer transport frame metadata");
        }
        ciphertext = ciphertext == null ? new byte[0] : ciphertext.clone();
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof OuterTransportFrame other && version == other.version
                && sessionId == other.sessionId && routeEpoch == other.routeEpoch
                && directionId == other.directionId && streamId == other.streamId
                && packetSequence == other.packetSequence
                && java.util.Arrays.equals(ciphertext, other.ciphertext);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(version, sessionId, routeEpoch, directionId, streamId, packetSequence)
                + java.util.Arrays.hashCode(ciphertext);
    }
}
