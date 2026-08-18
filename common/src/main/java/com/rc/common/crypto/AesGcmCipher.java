package com.rc.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * AES-256-GCM 对称加密。数据面流媒体 / 文件 / 剪贴板统一走此加密，
 * 每次加密使用随机 12 字节 IV（96-bit），128-bit 认证标签追加在密文尾部。
 */
public final class AesGcmCipher {

    public static final int KEY_SIZE_BITS = 256;
    public static final int IV_LENGTH = 12;
    public static final int TAG_LENGTH_BITS = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private AesGcmCipher() {
    }

    public static byte[] generateKey() {
        byte[] key = new byte[KEY_SIZE_BITS / 8];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public static byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    public static byte[] encrypt(byte[] key, byte[] iv, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM encrypt failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM decrypt failed (tag mismatch or corrupted)", e);
        }
    }
}
