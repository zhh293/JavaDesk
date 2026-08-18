package com.rc.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 中继一次性会话令牌（HMAC-SHA256 签名，无状态）。
 *
 * <p>信令服务器签发、中继服务器校验，双方仅共享一个 secret（配置注入），
 * 中继无需回查信令即可验证令牌真伪与有效期。令牌绑定会话 ID，短 TTL 防重放。</p>
 *
 * <pre>
 * payload = sessionId(8B big-endian) || expiryEpochSeconds(8B big-endian)
 * token   = base64url( payload || HMAC-SHA256(secret, payload) )
 * </pre>
 */
public final class RelayToken {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAC_LENGTH = 32;
    private static final int PAYLOAD_LENGTH = 16;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private RelayToken() {
    }

    /** 签发令牌。 */
    public static String sign(long sessionId, byte[] secret, long ttlSeconds) {
        byte[] payload = new byte[PAYLOAD_LENGTH];
        long expiry = System.currentTimeMillis() / 1000 + ttlSeconds;
        writeLong(payload, 0, sessionId);
        writeLong(payload, 8, expiry);

        byte[] mac = hmac(secret, payload);
        byte[] token = new byte[PAYLOAD_LENGTH + MAC_LENGTH];
        System.arraycopy(payload, 0, token, 0, PAYLOAD_LENGTH);
        System.arraycopy(mac, 0, token, PAYLOAD_LENGTH, MAC_LENGTH);
        return ENCODER.encodeToString(token);
    }

    /**
     * 校验令牌并返回会话 ID；签名不符 / 已过期抛 {@link CryptoException}。
     * HMAC 比较用常量时间实现，避免时序侧信道。
     */
    public static long verify(String token, byte[] secret) {
        byte[] raw;
        try {
            raw = DECODER.decode(token);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("invalid relay token encoding");
        }
        if (raw.length != PAYLOAD_LENGTH + MAC_LENGTH) {
            throw new CryptoException("invalid relay token length");
        }
        byte[] payload = Arrays.copyOf(raw, PAYLOAD_LENGTH);
        byte[] mac = Arrays.copyOfRange(raw, PAYLOAD_LENGTH, raw.length);
        if (!MessageDigest.isEqual(mac, hmac(secret, payload))) {
            throw new CryptoException("relay token signature mismatch");
        }
        long sessionId = readLong(payload, 0);
        long expiry = readLong(payload, 8);
        if (expiry <= System.currentTimeMillis() / 1000) {
            throw new CryptoException("relay token expired");
        }
        return sessionId;
    }

    private static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("relay token hmac failed", e);
        }
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
