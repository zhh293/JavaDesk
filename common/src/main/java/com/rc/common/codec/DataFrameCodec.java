package com.rc.common.codec;

import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameType;
import com.rc.common.constant.ProtocolConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;

/**
 * 数据帧编解码（大端网络字节序）。
 *
 * <p>帧头布局：channel(1) type(1) flags(2) seq(4) timestamp(8) length(4)，共 20 字节。</p>
 */
public final class DataFrameCodec {

    private DataFrameCodec() {
    }

    public static void encode(DataFrame frame, ByteBuf out) {
        out.writeByte(frame.channel().code());
        out.writeByte(frame.type().code());
        out.writeShort(frame.flags());
        out.writeInt(frame.seq());
        out.writeLong(frame.timestamp());
        out.writeInt(frame.payloadLength());
        out.writeBytes(frame.payload());
    }

    public static ByteBuf encode(ByteBufAllocator alloc, DataFrame frame) {
        ByteBuf buf = alloc.buffer(ProtocolConstants.DATA_FRAME_HEADER_SIZE + frame.payloadLength());
        encode(frame, buf);
        return buf;
    }

    /**
     * 从缓冲区解码一帧。缓冲区字节不足时返回 {@code null}（流式场景需等待更多数据）。
     */
    public static DataFrame decode(ByteBuf in) {
        if (in.readableBytes() < ProtocolConstants.DATA_FRAME_HEADER_SIZE) {
            return null;
        }
        in.markReaderIndex();
        int channelCode = in.readUnsignedByte();
        int typeCode = in.readUnsignedByte();
        int flags = in.readUnsignedShort();
        int seq = in.readInt();
        long timestamp = in.readLong();
        long length = in.readUnsignedInt();
        if (length > ProtocolConstants.MAX_DATA_PAYLOAD_SIZE) {
            throw new CorruptedFrameException("data frame payload too large: " + length);
        }
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return null;
        }
        ChannelType channel = ChannelType.of((byte) channelCode);
        FrameType type = FrameType.of((byte) typeCode);
        byte[] payload = new byte[(int) length];
        in.readBytes(payload);
        return new DataFrame(channel, type, flags, seq, timestamp, payload);
    }
}
