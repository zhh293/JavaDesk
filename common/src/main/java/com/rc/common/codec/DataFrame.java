package com.rc.common.codec;

import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameType;

/**
 * 数据面自定义帧（裸字节 + 自定义帧头，不走 Protobuf）。
 *
 * <pre>
 * channel(1B) | type(1B) | flags(2B) | seq(4B) | timestamp(8B) | length(4B) | payload
 * </pre>
 */
public final class DataFrame {

    private final ChannelType channel;
    private final FrameType type;
    private final int flags;
    private final int seq;
    private final long timestamp;
    private final byte[] payload;

    public DataFrame(ChannelType channel, FrameType type, int flags, int seq, long timestamp, byte[] payload) {
        this.channel = channel;
        this.type = type;
        this.flags = flags;
        this.seq = seq;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public ChannelType channel() {
        return channel;
    }

    public FrameType type() {
        return type;
    }

    public int flags() {
        return flags;
    }

    public int seq() {
        return seq;
    }

    public long timestamp() {
        return timestamp;
    }

    public byte[] payload() {
        return payload;
    }

    public int payloadLength() {
        return payload.length;
    }
}
