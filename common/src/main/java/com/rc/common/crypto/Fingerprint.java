package com.rc.common.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;

/**
 * 设备公钥指纹（SHA-256），用于防伪造校验（指纹篡改抛 {@code RC-4102}）。
 */
public final class Fingerprint {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Fingerprint() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return toHex(digest);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("SHA-256 digest failed", e);
        }
    }

    public static String ofPublicKey(PublicKey publicKey) {
        return sha256Hex(publicKey.getEncoded());
    }

    /** 转成可读分组形式，如 {@code ab12:cd34:...}。 */
    public static String shortForm(String hex, int groupSize) {
        StringBuilder sb = new StringBuilder(hex.length() + hex.length() / groupSize);
        for (int i = 0; i < hex.length(); i++) {
            if (i > 0 && i % groupSize == 0) {
                sb.append(':');
            }
            sb.append(hex.charAt(i));
        }
        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
