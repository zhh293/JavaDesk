package com.rc.common.constant;

/**
 * 数据帧 flags 位标志（2 字节，可组合）。
 */
public final class FrameFlags {

    public static final int NONE = 0x0000;

    /** payload 已用会话 AES-GCM 密钥加密 */
    public static final int ENCRYPTED = 0x0001;

    /** 重传帧（用于对端统计与去重） */
    public static final int RETRANSMIT = 0x0002;

    /** 分片的最后一片 */
    public static final int LAST_FRAGMENT = 0x0004;

    private FrameFlags() {
    }

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
