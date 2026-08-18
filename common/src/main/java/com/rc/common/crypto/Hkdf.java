package com.rc.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * HKDF (RFC 5869) 基于 HMAC-SHA256 的密钥派生，用于从会话熵派生 AES-256 会话密钥。
 */
public final class Hkdf {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HASH_LENGTH = 32;

    private Hkdf() {
    }

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            byte[] prk = extract(mac, salt, ikm);
            return expand(mac, prk, info, length);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("HKDF derive failed", e);
        }
    }

    private static byte[] extract(Mac mac, byte[] salt, byte[] ikm) throws GeneralSecurityException {
        byte[] key = (salt == null || salt.length == 0) ? new byte[HASH_LENGTH] : salt;
        mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
        return mac.doFinal(ikm);
    }

    private static byte[] expand(Mac mac, byte[] prk, byte[] info, int length)
            throws GeneralSecurityException {
        byte[] okm = new byte[length];
        byte[] infoBytes = (info == null) ? new byte[0] : info;
        byte[] t = new byte[0];
        byte counter = 1;
        int offset = 0;
        while (offset < length) {
            mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));
            mac.update(t);
            mac.update(infoBytes);
            mac.update(counter++);
            t = mac.doFinal();
            int copyLen = Math.min(t.length, length - offset);
            System.arraycopy(t, 0, okm, offset, copyLen);
            offset += copyLen;
        }
        return okm;
    }
}
