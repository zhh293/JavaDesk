package com.rc.client.capture;

import java.util.List;

/**
 * 视频分片 XOR 前向纠错（FEC）：对一帧的 N 个数据分片计算 1 片奇偶校验，
 * 接收端丢任意 1 片（非末片）时可由其余片 + 校验片还原。
 *
 * <p>所有分片固定 {@link VideoFraming#FRAGMENT_SIZE} 字节（末片零填充），XOR 按字节对齐。
 * 仅覆盖「丢 1 片」场景；丢多片走 NACK 重传。末片因真实长度未知（填充语义）不参与
 * FEC 还原，丢失时回退 NACK。</p>
 */
public final class FecCodec {

    private FecCodec() {
    }

    /** 计算一帧所有分片的 XOR 奇偶校验片（与分片等长）。 */
    public static byte[] parity(List<byte[]> fragments) {
        byte[] out = new byte[VideoFraming.FRAGMENT_SIZE];
        for (byte[] f : fragments) {
            xorInto(out, f);
        }
        return out;
    }

    /** 由已收分片 + 校验片还原缺失片（各分片与校验片等长）。 */
    public static byte[] recover(List<byte[]> received, byte[] parity) {
        byte[] out = new byte[VideoFraming.FRAGMENT_SIZE];
        System.arraycopy(parity, 0, out, 0, out.length);
        for (byte[] f : received) {
            xorInto(out, f);
        }
        return out;
    }

    private static void xorInto(byte[] acc, byte[] f) {
        int len = Math.min(acc.length, f.length);
        for (int i = 0; i < len; i++) {
            acc[i] ^= f[i];
        }
    }
}
