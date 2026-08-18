package com.rc.client.capture;

import java.awt.image.BufferedImage;

/**
 * 整帧 JPEG 编解码占位实现：每帧自包含（均视为关键帧），不做增量 / 运动补偿。
 * 作为 H.264 管线落地的过渡，跑通「编码 → 分片 → NACK/FEC → 重组 → 解码」全链路。
 */
public final class JpegVideoCodec implements VideoCodec {

    private final float quality;

    public JpegVideoCodec() {
        this(ScreenCodec.DEFAULT_JPEG_QUALITY);
    }

    public JpegVideoCodec(float quality) {
        this.quality = quality;
    }

    @Override
    public EncodedFrame encode(BufferedImage image, boolean forceKeyFrame) {
        byte[] jpeg = ScreenCodec.encode(image, quality);
        // JPEG 每帧自包含，等价于关键帧。
        return new EncodedFrame(jpeg, true, System.currentTimeMillis());
    }

    @Override
    public BufferedImage decode(byte[] data, boolean keyFrame) {
        return ScreenCodec.decode(data);
    }
}
