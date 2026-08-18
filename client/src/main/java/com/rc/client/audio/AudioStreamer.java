package com.rc.client.audio;

import com.rc.client.transport.TransportChannel;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 被控端音频采集推流：打开麦克风 {@link TargetDataLine}，按 20ms 块读 PCM，
 * 经 {@link ChannelType#AUDIO} 通道推给控制端。单线程循环，无音频设备时降级 no-op。
 */
public final class AudioStreamer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioStreamer.class);

    private final TransportChannel channel;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile TargetDataLine line;
    private volatile Thread thread;

    public AudioStreamer(TransportChannel channel) {
        this.channel = channel;
    }

    /** 启动采集线程；音频设备不可用时记 warn 并退出。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "rc-audio-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        try {
            TargetDataLine l = AudioSystem.getTargetDataLine(AudioConfig.FORMAT);
            l.open(AudioConfig.FORMAT, AudioConfig.CHUNK_BYTES * 4);
            line = l;
            l.start();
            byte[] buf = new byte[AudioConfig.CHUNK_BYTES];
            while (running.get()) {
                int n = l.read(buf, 0, buf.length);
                if (n <= 0) {
                    continue;
                }
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                channel.send(ChannelType.AUDIO, chunk);
            }
        } catch (LineUnavailableException e) {
            log.warn("audio capture unavailable: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("audio capture error", e);
        } finally {
            closeLine();
            running.set(false);
        }
    }

    @Override
    public void close() {
        running.set(false);
        closeLine();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void closeLine() {
        TargetDataLine l = line;
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
