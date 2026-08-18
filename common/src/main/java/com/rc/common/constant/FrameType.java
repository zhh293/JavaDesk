package com.rc.common.constant;

/**
 * 数据帧 payload 类型。
 */
public enum FrameType {
    /** 普通数据帧 */
    DATA((byte) 0),
    /** 关键帧（视频 IDR） */
    KEY_FRAME((byte) 1),
    /** 选择性重传请求 */
    NACK((byte) 2),
    /** 前向纠错冗余 */
    FEC((byte) 3),
    /** 心跳 / 保活 */
    HEARTBEAT((byte) 4);

    private final byte code;

    FrameType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static FrameType of(byte code) {
        for (FrameType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown frame type: " + code);
    }
}
