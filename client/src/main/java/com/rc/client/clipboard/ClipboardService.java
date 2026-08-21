package com.rc.client.clipboard;

import com.rc.client.transport.TransportChannel;
import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 双向剪贴板同步：本机剪贴板变化 → 编码后经 {@link ChannelType#CLIPBOARD} 推给对端；
 * 收到对端内容 → 写回本机剪贴板。以 {@link TransportListener} 身份挂在 {@code TransportChannel} 上。
 *
 * <p>自动同步按指纹（文本哈希 / 图片字节哈希）去重，避免回环（对端写回触发再次推送）。
 * 依赖 AWT 剪贴板，无头环境下降级为 no-op（记 warn）。</p>
 */
public final class ClipboardService implements TransportListener {

    private static final Logger log = LoggerFactory.getLogger(ClipboardService.class);
    private static final long DEFAULT_SYNC_INTERVAL_MS = 500L;

    private final TransportChannel channel;
    private final long intervalMs;
    private ScheduledExecutorService timer;
    private volatile boolean closed;
    private volatile String lastFingerprint;

    public ClipboardService(TransportChannel channel) {
        this(channel, DEFAULT_SYNC_INTERVAL_MS);
    }

    public ClipboardService(TransportChannel channel, long intervalMs) {
        this.channel = channel;
        this.intervalMs = Math.max(100L, intervalMs);
    }

    /** 启动本地剪贴板轮询自动同步。 */
    public void startAutoSync() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-clipboard-sync");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::captureAndSend, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 读取本机剪贴板并推送（内容变化时）。 */
    public void captureAndSend() {
        if (closed) {
            return;
        }
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);
            if (contents == null) {
                return;
            }
            String fingerprint;
            byte[] payload;
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                fingerprint = "t:" + Integer.toHexString(text.hashCode());
                payload = ClipboardCodec.text(text);
            } else if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Object imageValue = contents.getTransferData(DataFlavor.imageFlavor);
                if (!(imageValue instanceof Image image)) {
                    return;
                }
                byte[] png = imageToPng(toBufferedImage(image));
                if (png == null) {
                    return;
                }
                fingerprint = "i:" + Integer.toHexString(java.util.Arrays.hashCode(png));
                payload = ClipboardCodec.image(png);
            } else {
                return;
            }
            if (fingerprint.equals(lastFingerprint)) {
                return;
            }
            lastFingerprint = fingerprint;
            channel.send(ChannelType.CLIPBOARD, payload);
        } catch (UnsupportedFlavorException | IOException e) {
            log.debug("clipboard read unsupported: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("clipboard capture failed: {}", e.getMessage());
        }
    }

    @Override
    public void onData(DataFrame frame) {
        if (frame.channel() != ChannelType.CLIPBOARD || closed) {
            return;
        }
        ClipboardCodec.ClipboardContent content = ClipboardCodec.decode(frame.payload());
        if (content == null) {
            return;
        }
        apply(content);
    }

    /** 将对端剪贴板内容写回本机。 */
    public void apply(ClipboardCodec.ClipboardContent content) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable transferable;
            String fingerprint;
            if (content.type() == ClipboardCodec.TYPE_IMAGE && content.image() != null) {
                BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(content.image()));
                if (image == null) {
                    return;
                }
                transferable = new ImageSelection(image);
                fingerprint = "i:" + Integer.toHexString(java.util.Arrays.hashCode(content.image()));
            } else {
                transferable = new StringSelection(content.text() == null ? "" : content.text());
                fingerprint = "t:" + Integer.toHexString((content.text() == null ? "" : content.text()).hashCode());
            }
            lastFingerprint = fingerprint;
            clipboard.setContents(transferable, NoopOwner.INSTANCE);
        } catch (Exception e) {
            log.warn("clipboard apply failed: {}", e.getMessage());
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        close();
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    private static byte[] imageToPng(BufferedImage image) {
        if (image == null) {
            return null;
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            log.debug("image png encode failed: {}", e.getMessage());
            return null;
        }
    }

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage bi) {
            return bi;
        }
        int w = Math.max(1, image.getWidth(null));
        int h = Math.max(1, image.getHeight(null));
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        bi.getGraphics().drawImage(image, 0, 0, null);
        return bi;
    }

    /** 图片剪贴板 {@link Transferable} 实现。 */
    private record ImageSelection(BufferedImage image) implements Transferable, ClipboardOwner {

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }

        @Override
        public void lostOwnership(Clipboard clipboard, Transferable contents) {
            // 剪贴板所有权被其它进程接管，无需处理。
        }
    }

    private enum NoopOwner implements ClipboardOwner {
        INSTANCE;

        @Override
        public void lostOwnership(Clipboard clipboard, Transferable contents) {
            // no-op
        }
    }
}
