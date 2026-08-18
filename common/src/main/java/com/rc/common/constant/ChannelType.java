package com.rc.common.constant;

/**
 * 数据面逻辑通道（1 Byte channel 标头多路复用）。
 */
public enum ChannelType {
    /** 控制信令（键鼠），可靠，P0 最高 */
    CONTROL((byte) 0),
    /** 视频数据，部分可靠，P1 */
    VIDEO((byte) 1),
    /** 音频数据，部分可靠，P1 */
    AUDIO((byte) 2),
    /** 文件传输，强可靠，P3 */
    FILE((byte) 3),
    /** 系统剪贴板，可靠，P2 */
    CLIPBOARD((byte) 4);

    private final byte code;

    ChannelType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static ChannelType of(byte code) {
        for (ChannelType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown channel type: " + code);
    }
}
