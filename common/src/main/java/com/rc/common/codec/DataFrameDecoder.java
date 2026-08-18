package com.rc.common.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 数据帧解码器（流式 / 可靠通道使用；UDP datagram 可直接用 {@link DataFrameCodec#decode}）。
 */
public final class DataFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        DataFrame frame = DataFrameCodec.decode(in);
        if (frame != null) {
            out.add(frame);
        }
    }
}
