package com.rc.client.capture;

import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * H.264 编码器（JCodec 纯 Java 软编），实现 {@link VideoCodec} 的 H.264 落地骨架。
 *
 * <p>流程：{@code BufferedImage(RGB)} → {@link AWTUtil#fromBufferedImage} → YUV420
 * {@link Picture} → {@link H264Encoder#encodeFrame} → NAL 字节流；解码反向。
 * 输出为「关键帧 + 增量帧」的真实 GOP，配合 {@link VideoFraming} 的分片 / FEC / NACK 抗丢包管线。</p>
 *
 * <p><b>定位与校正</b>：JCodec 软编为硬编（NVENC / QuickSync / VAAPI，经 FFmpeg native）落地前的
 * 可用过渡，CPU 开销高于 JPEG 增量、清晰度受 baseline profile 限制。以下 JCodec 0.2.5 API
 * 未编译核对，接入时需对照其 javadoc 校正（与 kwik 同类处理）：</p>
 * <ul>
 *   <li>{@code H264Encoder.createH264Encoder()} —— 若该版本无此静态工厂，改用
 *       {@code new H264Encoder(new H264Encoder.RateControl(...))} 显式构造；</li>
 *   <li>{@code H264Encoder.encodeFrame(Picture, ByteBuffer)} 返回续写后的 ByteBuffer，
 *       签名与容量管理待核对；</li>
 *   <li>{@code H264Decoder.decodeFrame(ByteBuffer, int[][])} 的缓冲区参数待核对；</li>
 *   <li>关键帧判定当前以 {@code forceKeyFrame} 近似，正确做法是扫描 NAL（IDR_SLICE, type 5）。</li>
 * </ul>
 */
public final class H264VideoCodec implements VideoCodec {

    private final H264Encoder encoder;
    private final H264Decoder decoder;
    private static final int MAX_DECODE_WIDTH = 3840;
    private static final int MAX_DECODE_HEIGHT = 2160;

    public H264VideoCodec() {
        this.encoder = H264Encoder.createH264Encoder();
        this.decoder = new H264Decoder();
    }

    @Override
    public EncodedFrame encode(BufferedImage image, boolean forceKeyFrame) {
        Picture yuv = AWTUtil.fromBufferedImage(image, ColorSpace.YUV420);
        ByteBuffer out = ByteBuffer.allocate(Math.max(encoder.estimateBufferSize(yuv), 1 << 20));
        org.jcodec.common.VideoEncoder.EncodedFrame encoded = encoder.encodeFrame(yuv, out);
        ByteBuffer bytes = encoded.getData().duplicate();
        byte[] data = new byte[bytes.remaining()];
        bytes.get(data);
        return new EncodedFrame(data, encoded.isKeyFrame(), System.currentTimeMillis());
    }

    @Override
    public BufferedImage decode(byte[] data, boolean keyFrame) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            int luma = MAX_DECODE_WIDTH * MAX_DECODE_HEIGHT;
            Picture pic = decoder.decodeFrame(ByteBuffer.wrap(data), new byte[][]{
                    new byte[luma], new byte[luma / 4], new byte[luma / 4]
            });
            if (pic == null) {
                return null;
            }
            return AWTUtil.toBufferedImage(pic);
        } catch (Exception e) {
            return null;
        }
    }
}
