package com.rc.client.capture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 屏幕帧编解码：{@link BufferedImage} ↔ JPEG 字节。
 *
 * <p>Phase 1 用 JPEG 有损压缩整帧传输（体积小、ImageIO 原生支持、免第三方依赖）；
 * Phase 2 由 H.264（关键帧 + 增量帧 + NACK/FEC）替换，本类仅承载「帧 → 字节」契约。</p>
 */
public final class ScreenCodec {

    /** JPEG 默认压缩质量（0~1）。 */
    public static final float DEFAULT_JPEG_QUALITY = 0.7f;

    private ScreenCodec() {
    }

    /** 编码为 JPEG 字节。 */
    public static byte[] encode(BufferedImage image, float quality) {
        try {
            var jpegParams = new javax.imageio.plugins.jpeg.JPEGImageWriteParam(null);
            jpegParams.setCompressionMode(javax.imageio.plugins.jpeg.JPEGImageWriteParam.MODE_EXPLICIT);
            jpegParams.setCompressionQuality(quality);
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                try (var imageOut = ImageIO.createImageOutputStream(out)) {
                    writer.setOutput(imageOut);
                    writer.write(null, new javax.imageio.IIOImage(image, null, null), jpegParams);
                } finally {
                    writer.dispose();
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalStateException("jpeg encode failed", e);
        }
    }

    /** 解码 JPEG 字节为图像。 */
    public static BufferedImage decode(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            throw new IllegalStateException("jpeg decode failed", e);
        }
    }
}
