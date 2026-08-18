package com.rc.common.codec;

import com.rc.common.constant.ProtocolConstants;
import com.rc.common.protocol.Signal;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * 信令帧解码器。校验魔术后按长度字段切帧，再反序列化为 {@link Signal}。
 * 魔术不匹配 / 长度越界 / protobuf 解析失败均抛 {@link CorruptedFrameException}，
 * 由 Netty 默认关闭连接（控制面应严格拒绝畸形帧）。
 */
public final class SignalFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < ProtocolConstants.SIGNAL_HEADER_SIZE) {
            return;
        }
        in.markReaderIndex();
        int magic = in.readUnsignedShort();
        if (magic != ProtocolConstants.SIGNAL_MAGIC) {
            throw new CorruptedFrameException(
                    "invalid signal magic: 0x" + Integer.toHexString(magic));
        }
        in.skipBytes(2); // version + reserved
        long length = in.readUnsignedInt();
        if (length > ProtocolConstants.MAX_SIGNAL_FRAME_SIZE) {
            throw new CorruptedFrameException("signal frame too large: " + length);
        }
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }
        byte[] payload = new byte[(int) length];
        in.readBytes(payload);
        try {
            out.add(Signal.parseFrom(payload));
        } catch (InvalidProtocolBufferException e) {
            throw new CorruptedFrameException("invalid protobuf signal", e);
        }
    }
}
