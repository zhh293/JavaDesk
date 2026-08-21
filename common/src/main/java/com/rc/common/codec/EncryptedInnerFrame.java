package com.rc.common.codec;

import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameType;

/** Business metadata and payload encrypted inside an outer transport frame. */
public record EncryptedInnerFrame(ChannelType channel, FrameType type, int flags,
                                  int streamSequence, long timestamp, byte[] payload) {
    public EncryptedInnerFrame {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof EncryptedInnerFrame other && channel == other.channel && type == other.type
                && flags == other.flags && streamSequence == other.streamSequence && timestamp == other.timestamp
                && java.util.Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(channel, type, flags, streamSequence, timestamp)
                + java.util.Arrays.hashCode(payload);
    }
}
