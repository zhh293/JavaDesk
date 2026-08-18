package com.rc.client.capture;

import java.nio.ByteBuffer;

/**
 * 视频通道（{@code ChannelType.VIDEO}）分片 / 控制帧编解码（H.264 管线应用层协议）。
 *
 * <pre>
 * 数据/FEC 分片: [type 1B][frameId 4B][index 2B][count 2B][dataLen 4B][data FRAGMENT_SIZE B]
 * NACK 请求    : [type 1B][frameId 4B][index=0 2B][count=fragmentCount 2B][dataLen=bitmapLen 4B][bitmap bytes]
 * </pre>
 *
 * <p>数据分片固定 {@link #FRAGMENT_SIZE} 字节（末片零填充），{@code dataLen} 记录真实长度；
 * FEC 奇偶校验片与数据片等长，XOR 运算对齐。NACK 用 bitmap（bit i=1 表示第 i 片缺失）。</p>
 */
public final class VideoFraming {

    public static final byte TYPE_KEY_FRAME = 0x01;
    public static final byte TYPE_DELTA_FRAME = 0x02;
    public static final byte TYPE_FEC_PARITY = 0x03;
    public static final byte TYPE_NACK = 0x04;

    /** 分片固定载荷（MTU 安全值，规避 UDP/IP/中继包头分片）。 */
    public static final int FRAGMENT_SIZE = 1200;

    /** 分片头固定长度：type(1) + frameId(4) + index(2) + count(2) + dataLen(4)。 */
    public static final int HEADER_SIZE = 13;

    public record Fragment(int type, int frameId, int index, int count, int dataLen, byte[] data) {
        public boolean isKeyFrame() {
            return type == TYPE_KEY_FRAME;
        }

        public boolean isParity() {
            return type == TYPE_FEC_PARITY;
        }

        public boolean isNack() {
            return type == TYPE_NACK;
        }
    }

    private VideoFraming() {
    }

    /** 编码数据分片（chunk 长度 ≤ FRAGMENT_SIZE，不足零填充；dataLen 记真实长度）。 */
    public static byte[] dataFragment(boolean keyFrame, int frameId, int index, int count, byte[] chunk) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + FRAGMENT_SIZE);
        buf.put(keyFrame ? TYPE_KEY_FRAME : TYPE_DELTA_FRAME);
        buf.putInt(frameId);
        buf.putShort((short) index);
        buf.putShort((short) count);
        buf.putInt(chunk.length);
        buf.put(chunk);
        return buf.array();
    }

    /** 编码 FEC 奇偶校验片（parity 固定 FRAGMENT_SIZE 字节）。 */
    public static byte[] fecFragment(int frameId, int index, int count, byte[] parity) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + FRAGMENT_SIZE);
        buf.put(TYPE_FEC_PARITY);
        buf.putInt(frameId);
        buf.putShort((short) index);
        buf.putShort((short) count);
        buf.putInt(FRAGMENT_SIZE);
        buf.put(parity);
        return buf.array();
    }

    /** 编码 NACK 请求（missing[i]=true 表示第 i 片缺失）。 */
    public static byte[] nack(int frameId, int fragmentCount, boolean[] missing) {
        byte[] bitmap = toBitmap(missing);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + bitmap.length);
        buf.put(TYPE_NACK);
        buf.putInt(frameId);
        buf.putShort((short) 0);
        buf.putShort((short) fragmentCount);
        buf.putInt(bitmap.length);
        buf.put(bitmap);
        return buf.array();
    }

    /** 解析视频通道载荷；非法载荷返回 {@code null}。 */
    public static Fragment decode(byte[] payload) {
        if (payload == null || payload.length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int type = buf.get() & 0xFF;
        int frameId = buf.getInt();
        int index = buf.getShort() & 0xFFFF;
        int count = buf.getShort() & 0xFFFF;
        int dataLen = buf.getInt();
        int wireLen = type == TYPE_NACK ? dataLen : FRAGMENT_SIZE;
        if (wireLen < 0 || buf.remaining() < wireLen) {
            return null;
        }
        byte[] data = new byte[wireLen];
        buf.get(data);
        return new Fragment(type, frameId, index, count, dataLen, data);
    }

    /** 解码 NACK 载荷为缺失片 bitmap（长度 = count）。 */
    public static boolean[] decodeNack(Fragment f) {
        boolean[] missing = new boolean[f.count()];
        byte[] bitmap = f.data();
        for (int i = 0; i < f.count(); i++) {
            int byteIdx = i >> 3;
            int bitIdx = i & 7;
            if (byteIdx < bitmap.length) {
                missing[i] = (bitmap[byteIdx] & (1 << bitIdx)) != 0;
            }
        }
        return missing;
    }

    private static byte[] toBitmap(boolean[] missing) {
        int bytes = (missing.length + 7) / 8;
        byte[] bitmap = new byte[bytes];
        for (int i = 0; i < missing.length; i++) {
            if (missing[i]) {
                bitmap[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return bitmap;
    }
}
