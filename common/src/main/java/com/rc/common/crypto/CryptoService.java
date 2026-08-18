package com.rc.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

/**
 * 端到端加密编排门面。
 *
 * <p>连接密码 E2EE 流程：控制端用被控端公钥 RSA-OAEP 加密
 * 「会话熵(32B) + 连接密码」，信令只做密文透传；被控端解密后校验密码，
 * 双方凭会话熵经 HKDF 派生会话级 AES-256-GCM 密钥，接管后续数据面加密。</p>
 */
public final class CryptoService {

    public static final int ENTROPY_LENGTH = 32;
    public static final int SESSION_KEY_LENGTH = 32;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final byte[] SESSION_KEY_INFO = "rc-session-key-v1".getBytes(StandardCharsets.UTF_8);

    private CryptoService() {
    }

    public record DeviceKeyPair(KeyPair keyPair, String publicKeyBase64, String fingerprint) {
    }

    public record UnsealedPassword(byte[] password, byte[] entropy) {
    }

    /** 加密结果：密文（交信令透传）+ 会话熵（控制端本地派生会话密钥用）。 */
    public record SealedPassword(byte[] ciphertext, byte[] entropy) {
    }

    public static DeviceKeyPair generateDeviceKeyPair() {
        KeyPair keyPair = RsaCipher.generateKeyPair();
        String publicKeyBase64 = RsaCipher.encodePublicKey(keyPair.getPublic());
        String fingerprint = Fingerprint.ofPublicKey(keyPair.getPublic());
        return new DeviceKeyPair(keyPair, publicKeyBase64, fingerprint);
    }

    public static byte[] encryptConnectionPassword(PublicKey targetPublicKey, byte[] password) {
        return sealConnectionPassword(targetPublicKey, password).ciphertext();
    }

    /** 加密连接密码并返回会话熵，控制端据此派生会话密钥（被控端经解密同样获得熵）。 */
    public static SealedPassword sealConnectionPassword(PublicKey targetPublicKey, byte[] password) {
        if (password == null || password.length == 0) {
            throw new CryptoException("empty connection password");
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            throw new CryptoException("connection password too long: " + password.length);
        }
        byte[] entropy = new byte[ENTROPY_LENGTH];
        new SecureRandom().nextBytes(entropy);

        byte[] plaintext = new byte[ENTROPY_LENGTH + password.length];
        System.arraycopy(entropy, 0, plaintext, 0, ENTROPY_LENGTH);
        System.arraycopy(password, 0, plaintext, ENTROPY_LENGTH, password.length);
        return new SealedPassword(RsaCipher.encrypt(targetPublicKey, plaintext), entropy);
    }

    public static UnsealedPassword decryptConnectionPassword(PrivateKey myPrivateKey, byte[] ciphertext) {
        byte[] plaintext = RsaCipher.decrypt(myPrivateKey, ciphertext);
        if (plaintext.length <= ENTROPY_LENGTH) {
            throw new CryptoException("invalid connection password package");
        }
        byte[] entropy = new byte[ENTROPY_LENGTH];
        System.arraycopy(plaintext, 0, entropy, 0, ENTROPY_LENGTH);
        byte[] password = new byte[plaintext.length - ENTROPY_LENGTH];
        System.arraycopy(plaintext, ENTROPY_LENGTH, password, 0, password.length);
        return new UnsealedPassword(password, entropy);
    }

    public static byte[] deriveSessionKey(byte[] entropy) {
        return Hkdf.derive(entropy, null, SESSION_KEY_INFO, SESSION_KEY_LENGTH);
    }
}
