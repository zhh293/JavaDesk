package com.rc.common.codec;

import java.nio.charset.StandardCharsets;

/**
 * 中继 UDP 数据报编解码（客户端 ↔ 中继服务器）。
 *
 * <pre>
 * type(1B) | sessionId(8B big-endian) | payload(变长)
 * </pre>
 *
 * <p>类型：{@link #TYPE_JOIN} 入会（payload 为令牌）、{@link #TYPE_JOIN_ACK} 入会确认、
 * {@link #TYPE_DATA} 数据透传（payload 为 {@link DataFrame} 编码字节，中继不解解析、原样转发）。
 * 中继仅读取 type + sessionId 做路由，业务载荷保持透明，实现「网络包维度密文转发」。</p>
 */
public final class RelayPacketCodec {

    public static final byte TYPE_JOIN = 0x01;
    public static final byte TYPE_JOIN_ACK = 0x02;
    public static final byte TYPE_DATA = 0x03;

    public static final int HEADER_SIZE = 9;

    /** 解析后的中继包。 */
    public record Packet(byte type, long sessionId, byte[] payload) {
    }

    private RelayPacketCodec() {
    }

    public static byte[] join(long sessionId, String token) {
        return build(TYPE_JOIN, sessionId, token.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] joinAck(long sessionId) {
        return build(TYPE_JOIN_ACK, sessionId, new byte[0]);
    }

    public static byte[] data(long sessionId, byte[] payload) {
        return build(TYPE_DATA, sessionId, payload);
    }

    /** 解码；长度不足返回 {@code null}（畸形包由调用方丢弃）。 */
    public static Packet decode(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            return null;
        }
        byte type = data[0];
        long sessionId = readLong(data, 1);
        byte[] payload = new byte[data.length - HEADER_SIZE];
        System.arraycopy(data, HEADER_SIZE, payload, 0, payload.length);
        return new Packet(type, sessionId, payload);
    }

    private static byte[] build(byte type, long sessionId, byte[] payload) {
        byte[] out = new byte[HEADER_SIZE + payload.length];
        out[0] = type;
        writeLong(out, 1, sessionId);
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.length);
        return out;
    }

    private static void writeLong(byte[] buf, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            buf[offset + i] = (byte) value;
            value >>>= 8;
        }
    }

    private static long readLong(byte[] buf, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (buf[offset + i] & 0xFFL);
        }
        return value;
    }
}
