package com.rc.client.capture;

import com.rc.client.transport.TransportChannel;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 被控端屏幕推流器：按目标帧率周期抓帧、JPEG 编码，经 {@link TransportChannel}
 * 以 {@link ChannelType#VIDEO} 通道推送到控制端。
 *
 * <p>Phase 1 为整帧采集（每帧自包含 JPEG），不做增量/关键帧区分与拥塞控制；
 * 抓帧与编码为串行单线程，上一帧未完成则跳过本拍，避免堆积与失控。</p>
 */
public final class ScreenStreamer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScreenStreamer.class);

    /** Phase 1 默认采集帧率（AWT Robot 抓屏 + JPEG 编码为 CPU 密集型，保守取值）。 */
    private static final int DEFAULT_FPS = 10;

    private final TransportChannel channel;
    private final ScreenCapturer capturer;
    private final float jpegQuality;
    private final long intervalMs;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean encoding = new AtomicBoolean();
    private volatile boolean closed;

    public ScreenStreamer(TransportChannel channel, ScreenCapturer capturer) {
        this(channel, capturer, ScreenCodec.DEFAULT_JPEG_QUALITY, DEFAULT_FPS);
    }

    public ScreenStreamer(TransportChannel channel, ScreenCapturer capturer,
                          float jpegQuality, int fps) {
        this.channel = channel;
        this.capturer = capturer;
        this.jpegQuality = jpegQuality;
        this.intervalMs = Math.max(1L, 1000L / fps);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-capture");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        timer.scheduleAtFixedRate(this::captureAndSend, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void captureAndSend() {
        if (closed || !encoding.compareAndSet(false, true)) {
            return;
        }
        try {
            BufferedImage image = capturer.capture();
            byte[] jpeg = ScreenCodec.encode(image, jpegQuality);
            channel.send(ChannelType.VIDEO, jpeg);
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
