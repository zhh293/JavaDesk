package com.rc.common.constant;

/**
 * 协议级常量：帧头魔术、版本、长度字段布局与边界。
 */
public final class ProtocolConstants {

    /** 信令帧魔术（"RC" 的 big-endian short） */
    public static final int SIGNAL_MAGIC = 0x5243;

    /** 协议版本 */
    public static final byte PROTOCOL_VERSION = 0x01;

    /** 信令帧头长度：magic(2) + version(1) + reserved(1) + length(4) */
    public static final int SIGNAL_HEADER_SIZE = 8;

    /** 数据帧头长度：channel(1) + type(1) + flags(2) + seq(4) + timestamp(8) + length(4) */
    public static final int DATA_FRAME_HEADER_SIZE = 20;

    /** 单帧 payload 上限（防御畸形帧 / 内存攻击） */
    public static final int MAX_SIGNAL_FRAME_SIZE = 1 << 20;      // 1 MiB
    public static final int MAX_DATA_PAYLOAD_SIZE = 4 << 20;      // 4 MiB

    private ProtocolConstants() {
    }
}
