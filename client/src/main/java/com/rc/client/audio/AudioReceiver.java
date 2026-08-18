package com.rc.client.audio;

import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * 控制端音频播放：以 {@link TransportListener} 身份挂在 {@code TransportChannel} 上，
 * 过滤 {@link ChannelType#AUDIO} 帧，将 PCM 写入扬声器 {@link SourceDataLine}。
 * 无播放设备时降级丢弃（记 warn）。
 */
public final class AudioReceiver implements TransportListener {

    private static final Logger log = LoggerFactory.getLogger(AudioReceiver.class);

    private volatile SourceDataLine line;
    private volatile boolean unavailable;

    public AudioReceiver() {
    }

    @Override
    public void onData(DataFrame frame) {
        if (frame.channel() != ChannelType.AUDIO) {
            return;
        }
        if (unavailable) {
            return;
        }
        try {
            SourceDataLine l = line();
            l.write(frame.payload(), 0, frame.payload().length);
        } catch (Exception e) {
            log.warn("audio playback failed: {}", e.getMessage());
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        closeLine();
    }

    private SourceDataLine line() throws LineUnavailableException {
        SourceDataLine l = line;
        if (l != null && l.isOpen()) {
            return l;
        }
        l = AudioSystem.getSourceDataLine(AudioConfig.FORMAT);
        l.open(AudioConfig.FORMAT, AudioConfig.CHUNK_BYTES * 4);
        l.start();
        line = l;
        return l;
    }

    private void closeLine() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.close();
            } catch (Exception ignored) {
                // 忽略关闭失败
            }
        }
    }
}
