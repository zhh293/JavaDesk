package com.rc.client.capture;

import com.rc.client.transport.TransportChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 被控端屏幕推流器：按目标帧率周期抓帧，经 {@link VideoCodec} 编码后由 {@link VideoSender}
 * 分片 + FEC + NACK 重传，以 {@code ChannelType.VIDEO} 通道推送到控制端。
 *
 * <p>Phase 2 接线：由「整帧 JPEG 裸发」升级为「编码 → 分片 → FEC → 重传」管线；采集抽象为
 * {@link DesktopDuplicationCapturer}（AWT 回退 / DXGI 原生），编码抽象为 {@link VideoCodec}
 * （增量 JPEG / H.264）。抓帧与编码串行单线程，上一帧未完成则跳过本拍，避免堆积失控。</p>
 */
public final class ScreenStreamer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScreenStreamer.class);

    /** 默认采集帧率（AWT Robot 抓屏 + 编码为 CPU 密集型，保守取值）。 */
    private static final int DEFAULT_FPS = 10;

    private final TransportChannel channel;
    private final DesktopDuplicationCapturer capturer;
    private final VideoSender sender;
    private final long intervalMs;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean encoding = new AtomicBoolean();
    private final AtomicBoolean keyFrameRequested = new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean firstFrame = true;

    public ScreenStreamer(TransportChannel channel, DesktopDuplicationCapturer capturer, VideoCodec codec) {
        this(channel, capturer, codec, DEFAULT_FPS);
    }

    public ScreenStreamer(TransportChannel channel, DesktopDuplicationCapturer capturer,
                          VideoCodec codec, int fps) {
        this.channel = channel;
        this.capturer = capturer;
        this.sender = new VideoSender(channel, codec);
        this.intervalMs = Math.max(1L, 1000L / fps);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-capture");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // VideoSender 以 listener 身份接收 NACK 控制帧。
        channel.addListener(sender);
        timer.scheduleAtFixedRate(this::captureAndSend, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 请求下一拍强制关键帧（会话建立 / 回切后重启解码）。 */
    public void requestKeyFrame() {
        keyFrameRequested.set(true);
    }

    private void captureAndSend() {
        if (closed || !encoding.compareAndSet(false, true)) {
            return;
        }
        try {
            BufferedImage image = capturer.capture();
            boolean force = firstFrame || keyFrameRequested.getAndSet(false);
            firstFrame = false;
            sender.sendFrame(image, force);
        } catch (Exception e) {
            log.warn("capture/send failed", e);
        } finally {
            encoding.set(false);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        timer.shutdownNow();
        capturer.close();
    }
}
