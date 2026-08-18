package com.rc.client.capture;

import com.rc.client.transport.TransportChannel;
import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 视频接收端（控制端）：分片重组 → 缺口检测（NACK）→ FEC 单丢片还原 → 解码回调。
 * 以 {@link TransportListener} 身份挂在 {@code TransportChannel} 上接收
 * {@link ChannelType#VIDEO} 帧，缺口经定时扫描触发 NACK 请求。
 */
public final class VideoReceiver implements TransportListener {

    private static final Logger log = LoggerFactory.getLogger(VideoReceiver.class);

    /** 解码回调。 */
    public interface Listener {
        void onFrame(BufferedImage image, long ptsMs);
    }

    private static final long NACK_DELAY_MS = 30L;
    private static final long FRAME_EVICT_MS = 2000L;

    private final TransportChannel channel;
    private final VideoCodec codec;
    private final Listener listener;
    private final Map<Integer, FrameBuf> frames = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;

    public VideoReceiver(TransportChannel channel, VideoCodec codec, Listener listener) {
        this.channel = channel;
        this.codec = codec;
        this.listener = listener;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-video-recv");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::sweep, NACK_DELAY_MS, NACK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onData(DataFrame frame) {
        if (frame.channel() != ChannelType.VIDEO) {
            return;
        }
        VideoFraming.Fragment f = VideoFraming.decode(frame.payload());
        if (f == null || f.isNack()) {
            return;
        }
        if (f.isParity()) {
            onParity(f);
        } else {
            onFragment(f);
        }
    }

    private void onFragment(VideoFraming.Fragment f) {
        FrameBuf buf = frames.computeIfAbsent(f.frameId(), id -> new FrameBuf(f.count()));
        if (f.index() >= buf.count() || buf.padded()[f.index()] != null) {
            return;
        }
        buf.padded()[f.index()] = f.data();
        buf.lengths()[f.index()] = f.dataLen();
        buf.keyFrame = f.isKeyFrame() || buf.keyFrame;
        buf.lastUpdate = System.currentTimeMillis();
        if (++buf.received == buf.count()) {
            frames.remove(f.frameId());
            deliver(f.frameId(), buf);
        }
    }

    private void onParity(VideoFraming.Fragment f) {
        FrameBuf buf = frames.computeIfAbsent(f.frameId(), id -> new FrameBuf(f.count()));
        buf.parity = f.data();
        buf.lastUpdate = System.currentTimeMillis();
        int missing = firstMissing(buf);
        if (buf.received == buf.count() - 1 && missing >= 0 && missing < buf.count() - 1) {
            recover(buf, missing);
            frames.remove(f.frameId());
            deliver(f.frameId(), buf);
        }
    }

    private void recover(FrameBuf buf, int missingIndex) {
        List<byte[]> received = new ArrayList<>(buf.count() - 1);
        for (int i = 0; i < buf.count(); i++) {
            if (i != missingIndex && buf.padded()[i] != null) {
                received.add(buf.padded()[i]);
            }
        }
        byte[] recovered = FecCodec.recover(received, buf.parity);
        buf.padded()[missingIndex] = recovered;
        buf.lengths()[missingIndex] = VideoFraming.FRAGMENT_SIZE;
    }

    private void deliver(int frameId, FrameBuf buf) {
        try {
            int total = 0;
            for (int i = 0; i < buf.count(); i++) {
                total += buf.lengths()[i];
            }
            byte[] assembled = new byte[total];
            int off = 0;
            for (int i = 0; i < buf.count(); i++) {
                System.arraycopy(buf.padded()[i], 0, assembled, off, buf.lengths()[i]);
                off += buf.lengths()[i];
            }
            BufferedImage image = codec.decode(assembled, buf.keyFrame);
            if (image != null && listener != null) {
                listener.onFrame(image, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.debug("video frame assemble failed: frameId={} {}", frameId, e.getMessage());
        }
    }

    /** 定时扫描：对超时未齐帧发 NACK，对过期帧清理。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, FrameBuf>> it = frames.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, FrameBuf> e = it.next();
            FrameBuf buf = e.getValue();
            if (now - buf.lastUpdate > FRAME_EVICT_MS) {
                it.remove();
                continue;
            }
            if (buf.received < buf.count() && !buf.nackSent && now - buf.lastUpdate > NACK_DELAY_MS) {
                buf.nackSent = true;
                sendNack(e.getKey(), buf);
            }
        }
    }

    private void sendNack(int frameId, FrameBuf buf) {
        boolean[] missing = new boolean[buf.count()];
        for (int i = 0; i < buf.count(); i++) {
            missing[i] = buf.padded()[i] == null;
        }
        channel.send(ChannelType.VIDEO, VideoFraming.nack(frameId, buf.count(), missing));
    }

    @Override
    public void onClosed(Throwable cause) {
        close();
    }

    /** 显式关闭（幂等），停止 NACK 定时扫描并清空重组缓冲。 */
    public void close() {
        timer.shutdownNow();
        frames.clear();
    }

    private static int firstMissing(FrameBuf buf) {
        for (int i = 0; i < buf.count(); i++) {
            if (buf.padded()[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private static final class FrameBuf {
        private final int count;
        private final byte[][] padded;
        private final int[] lengths;
        private byte[] parity;
        private boolean keyFrame;
        private boolean nackSent;
        private volatile long lastUpdate = System.currentTimeMillis();
        private int received;

        FrameBuf(int count) {
            this.count = count;
            this.padded = new byte[count][];
            this.lengths = new int[count];
        }

        int count() {
            return count;
        }

        byte[][] padded() {
            return padded;
        }

        int[] lengths() {
            return lengths;
        }
    }
}
