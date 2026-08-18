package com.rc.common.codec;

import com.rc.common.constant.ProtocolConstants;
import com.rc.common.protocol.Signal;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 信令帧编码器。帧格式：magic(2B) + version(1B) + reserved(1B) + length(4B) + protobuf payload。
 */
public final class SignalFrameEncoder extends MessageToByteEncoder<Signal> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Signal msg, ByteBuf out) {
        byte[] payload = msg.toByteArray();
        out.writeShort(ProtocolConstants.SIGNAL_MAGIC);
        out.writeByte(ProtocolConstants.PROTOCOL_VERSION);
        out.writeByte(0); // reserved
        out.writeInt(payload.length);
        out.writeBytes(payload);
    }
}
