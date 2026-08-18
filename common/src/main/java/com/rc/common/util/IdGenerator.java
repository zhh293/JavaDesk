package com.rc.common.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 会话 ID / 追踪 ID / 令牌 / 设备连接码 生成器，统一走 {@link SecureRandom}。
 */
public final class IdGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private IdGenerator() {
    }

    /** 63-bit 正随机会话 ID（用于 protobuf uint64 session_id）。 */
    public static long newSessionId() {
        long id = SECURE_RANDOM.nextLong() & Long.MAX_VALUE;
        return id == 0 ? 1 : id;
    }

    /** 全链路追踪 ID（16 字节 hex）。 */
    public static String newTraceId() {
        return randomHex(16);
    }

    /** URL-safe Base64 随机令牌（用于中继一次性令牌 / 恢复票据）。 */
    public static String newToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 设备连接码，形如 {@code 123 456 789}，便于人工读入。 */
    public static String newDeviceCode() {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 9; i++) {
            if (i > 0 && i % 3 == 0) {
                sb.append(' ');
            }
            sb.append((char) ('0' + SECURE_RANDOM.nextInt(10)));
        }
        return sb.toString();
    }

    private static String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        char[] out = new char[byteLength * 2];
        for (int i = 0; i < byteLength; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
