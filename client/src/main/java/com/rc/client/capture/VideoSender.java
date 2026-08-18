package com.rc.client.capture;

import com.rc.client.transport.TransportChannel;
import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频发送端（被控端）：编码 → 分片 → 发数据片 + FEC 校验片；收到 NACK 时重传缺失片。
 * 以 {@link TransportListener} 身份挂在 {@code TransportChannel} 上接收
 * {@link ChannelType#VIDEO} 通道的 NACK 控制帧。
 *
 * <p>近期帧缓存（有界 LRU）支撑 NACK 重传；FEC 为每帧额外 1 片 XOR 校验。</p>
 */
public final class VideoSender implements TransportListener {

    private static final Logger log = LoggerFactory.getLogger(VideoSender.class);
    private static final int RETRANSMIT_CACHE_FRAMES = 16;

    private final TransportChannel channel;
    private final VideoCodec codec;
    private final AtomicInteger frameId = new AtomicInteger();
    private final Map<Integer, FrameChunks> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, FrameChunks> eldest) {
            return size() > RETRANSMIT_CACHE_FRAMES;
        }
    };

    public VideoSender(TransportChannel channel, VideoCodec codec) {
        this.channel = channel;
        this.codec = codec;
    }

    /** 编码并发送一帧（含分片 + FEC）。 */
    public void sendFrame(BufferedImage image, boolean forceKeyFrame) {
        VideoCodec.EncodedFrame encoded = codec.encode(image, forceKeyFrame);
        if (encoded.data() == null || encoded.data().length == 0) {
            return;
        }
        byte[] data = encoded.data();
        int count = (data.length + VideoFraming.FRAGMENT_SIZE - 1) / VideoFraming.FRAGMENT_SIZE;
        byte[][] padded = new byte[count][VideoFraming.FRAGMENT_SIZE];
        int[] lengths = new int[count];
        for (int i = 0; i < count; i++) {
            int off = i * VideoFraming.FRAGMENT_SIZE;
            int len = Math.min(VideoFraming.FRAGMENT_SIZE, data.length - off);
            System.arraycopy(data, off, padded[i], 0, len);
            lengths[i] = len;
        }
        byte[] parity = FecCodec.parity(Arrays.asList(padded));
        int fid = frameId.getAndIncrement();
        for (int i = 0; i < count; i++) {
            channel.send(ChannelType.VIDEO,
                    VideoFraming.dataFragment(encoded.keyFrame(), fid, i, count,
                            Arrays.copyOf(padded[i], lengths[i])));
        }
        channel.send(ChannelType.VIDEO, VideoFraming.fecFragment(fid, count, count, parity));
        recent.put(fid, new FrameChunks(encoded.keyFrame(), count, padded, lengths, parity));
    }

    @Override
    public void onData(DataFrame frame) {
        if (frame.channel() != ChannelType.VIDEO) {
            return;
        }
        VideoFraming.Fragment f = VideoFraming.decode(frame.payload());
        if (f == null || !f.isNack()) {
            return;
        }
        retransmit(f);
    }

    private void retransmit(VideoFraming.Fragment nack) {
        FrameChunks chunks = recent.get(nack.frameId());
        if (chunks == null) {
            log.debug("nack for unknown frame ignored: frameId={}", nack.frameId());
            return;
        }
        boolean[] missing = VideoFraming.decodeNack(nack);
        for (int i = 0; i < missing.length && i < chunks.count(); i++) {
            if (missing[i]) {
                channel.send(ChannelType.VIDEO,
                        VideoFraming.dataFragment(chunks.keyFrame(), nack.frameId(), i, chunks.count(),
                                Arrays.copyOf(chunks.padded()[i], chunks.lengths()[i])));
            }
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        recent.clear();
    }

    private record FrameChunks(boolean keyFrame, int count, byte[][] padded,
                               int[] lengths, byte[] parity) {
    }
}
