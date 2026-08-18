package com.rc.common.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 数据帧编码器（流式 / 可靠通道使用；UDP datagram 可直接用 {@link DataFrameCodec#encode}）。
 */
public final class DataFrameEncoder extends MessageToByteEncoder<DataFrame> {

    @Override
    protected void encode(ChannelHandlerContext ctx, DataFrame msg, ByteBuf out) {
        DataFrameCodec.encode(msg, out);
    }
}
