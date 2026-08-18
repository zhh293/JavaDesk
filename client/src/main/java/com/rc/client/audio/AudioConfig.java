package com.rc.client.audio;

import javax.sound.sampled.AudioFormat;

/**
 * 实时音频流采样格式与分块参数（收发两端必须一致）。
 *
 * <p>Phase 1 采用裸 PCM（16-bit signed little-endian、单声道、48 kHz）直传，不做编码
 * （Opus 等压缩留 Phase 2）。20ms 每块，兼顾时延与 UDP 承载。</p>
 */
public final class AudioConfig {

    public static final float SAMPLE_RATE = 48000f;
    public static final int SAMPLE_SIZE_BITS = 16;
    public static final int CHANNELS = 1;
    public static final boolean SIGNED = true;
    public static final boolean BIG_ENDIAN = false;

    /** 每块 20ms 的 PCM 字节数（48000 × 2 字节 × 0.02s）。 */
    public static final int CHUNK_BYTES = 1920;

    public static final AudioFormat FORMAT = new AudioFormat(
            SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, SIGNED, BIG_ENDIAN);

    private AudioConfig() {
    }
}
