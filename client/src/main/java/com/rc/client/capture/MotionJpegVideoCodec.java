package com.rc.client.capture;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 增量 JPEG 编码器：脏矩形检测 + 关键帧 / 增量帧区分，替换 {@link JpegVideoCodec} 的
 * 「每帧全关键帧」占位，在 H.264 硬编落地前实质性降低带宽与编码开销。
 *
 * <p>编码端维护上一帧快照，逐块比较求变化区域（脏矩形）外接框；变化区域占比较小时仅编码
 * 脏矩形为增量帧，否则回退整帧关键帧。增量帧字节格式自描述：
 * <pre>
 * 关键帧: [JPEG bytes]                              （自包含，解码读出宽高）
 * 增量帧: [x(4B)][y(4B)][w(4B)][h(4B)][JPEG bytes]   （解码后覆盖到参考帧对应区域）
 * </pre>
 *
 * <p>接收端维护最近解码的参考帧，增量帧解码为子图后覆盖其上，生成新帧（不改写参考，供后续
 * 增量帧引用）。{@link VideoFraming} 的 type（KEY/DELTA）由 {@link VideoSender} 按
 * {@link EncodedFrame#keyFrame()} 决定，本类只负责帧内字节编排。</p>
 */
public final class MotionJpegVideoCodec implements VideoCodec {

    /** 脏矩形外扩 padding（像素），吸收下采样边界抖动。 */
    private static final int DIRTY_PADDING = 8;
    /** 下采样比较步长，降低逐像素 diff 开销。 */
    private static final int DIFF_STEP = 2;
    /** 变化区域占比超过该阈值（0~1）时回退整帧关键帧，避免增量帧比整帧更大。 */
    private static final float KEYFRAME_RATIO = 0.6f;

    private final float quality;
    private final Rectangle fullBounds;
    private volatile BufferedImage lastEncoded;
    private volatile BufferedImage decodedRef;

    public MotionJpegVideoCodec(float quality) {
        this.quality = quality;
        this.fullBounds = new Rectangle(
                java.awt.Toolkit.getDefaultToolkit().getScreenSize());
    }

    public MotionJpegVideoCodec() {
        this(ScreenCodec.DEFAULT_JPEG_QUALITY);
    }

    @Override
    public EncodedFrame encode(BufferedImage image, boolean forceKeyFrame) {
        long pts = System.currentTimeMillis();
        if (forceKeyFrame || lastEncoded == null
                || lastEncoded.getWidth() != image.getWidth()
                || lastEncoded.getHeight() != image.getHeight()) {
            return keyFrame(image, pts);
        }
        Rectangle dirty = dirtyRect(lastEncoded, image);
        if (dirty == null) {
            // 无变化：返回空数据，上层据此跳过本拍。
            return new EncodedFrame(null, false, pts);
        }
        long dirtyPixels = (long) dirty.width * dirty.height;
        long fullPixels = (long) image.getWidth() * image.getHeight();
        if (dirtyPixels >= fullPixels * KEYFRAME_RATIO) {
            return keyFrame(image, pts);
        }
        try {
            BufferedImage sub = image.getSubimage(dirty.x, dirty.y, dirty.width, dirty.height);
            byte[] jpeg = encodeJpeg(sub, quality);
            byte[] delta = withRectHeader(dirty, jpeg);
            lastEncoded = copy(image);
            return new EncodedFrame(delta, false, pts);
        } catch (Exception e) {
            // 脏矩形路径异常时兜底整帧关键帧，保证可用性。
            return keyFrame(image, pts);
        }
    }

    @Override
    public BufferedImage decode(byte[] data, boolean keyFrame) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            if (keyFrame) {
                BufferedImage img = ScreenCodec.decode(data);
                decodedRef = copy(img);
                return img;
            }
            Rectangle r = readRectHeader(data);
            byte[] jpeg = new byte[data.length - 16];
            System.arraycopy(data, 16, jpeg, 0, jpeg.length);
            BufferedImage sub = ScreenCodec.decode(jpeg);
            BufferedImage base = decodedRef;
            if (base == null) {
                // 参考帧缺失（首帧即增量 / 丢关键帧）：无法还原，等待下一关键帧。
                return null;
            }
            BufferedImage out = copy(base);
            Graphics2D g = out.createGraphics();
            try {
                g.drawImage(sub, r.x, r.y, null);
            } finally {
                g.dispose();
            }
            decodedRef = out;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private EncodedFrame keyFrame(BufferedImage image, long pts) {
        try {
            byte[] jpeg = encodeJpeg(image, quality);
            lastEncoded = copy(image);
            return new EncodedFrame(jpeg, true, pts);
        } catch (Exception e) {
            throw new IllegalStateException("jpeg encode failed", e);
        }
    }

    private static byte[] withRectHeader(Rectangle r, byte[] jpeg) {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream(jpeg.length + 16);
            DataOutputStream out = new DataOutputStream(bout);
            out.writeInt(r.x);
            out.writeInt(r.y);
            out.writeInt(r.width);
            out.writeInt(r.height);
            out.write(jpeg);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("delta frame encode failed", e);
        }
    }

    private static Rectangle readRectHeader(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int x = in.readInt();
            int y = in.readInt();
            int w = in.readInt();
            int h = in.readInt();
            if (x < 0 || y < 0 || w <= 0 || h <= 0) {
                throw new IOException("invalid delta rect");
            }
            return new Rectangle(x, y, w, h);
        }
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        var params = new javax.imageio.plugins.jpeg.JPEGImageWriteParam(null);
        params.setCompressionMode(javax.imageio.plugins.jpeg.JPEGImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality);
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (var imageOut = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(imageOut);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        }
    }

    /** 逐块 diff 求变化区域外接框（下采样 + 外扩），无变化返回 {@code null}。 */
    private static Rectangle dirtyRect(BufferedImage prev, BufferedImage cur) {
        int w = cur.getWidth();
        int h = cur.getHeight();
        if (prev.getWidth() != w || prev.getHeight() != h) {
            return null;
        }
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y += DIFF_STEP) {
            for (int x = 0; x < w; x += DIFF_STEP) {
                if (prev.getRGB(x, y) != cur.getRGB(x, y)) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        int x0 = Math.max(0, minX - DIRTY_PADDING);
        int y0 = Math.max(0, minY - DIRTY_PADDING);
        int x1 = Math.min(w - 1, maxX + DIRTY_PADDING);
        int y1 = Math.min(h - 1, maxY + DIRTY_PADDING);
        if (x1 < x0 || y1 < y0) {
            return null;
        }
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /** 深拷贝一帧，避免 {@code getSubimage} 共享底图导致参考帧被后续写入污染。 */
    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
