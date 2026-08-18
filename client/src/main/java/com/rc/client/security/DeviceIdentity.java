package com.rc.client.security;

import com.rc.common.crypto.CryptoService;
import com.rc.common.util.IdGenerator;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * 本机设备身份：RSA 密钥对 + 公钥指纹 + 连接密码，登录注册与 E2EE 邀请流共用。
 *
 * <p>连接密码明文仅存于被控端内存，用于解密 InviteReq 后本地比对；
 * 服务端 / DB 只存 bcrypt 哈希（见 {@code connect_password_hash}）。</p>
 */
public final class DeviceIdentity {

    private final String deviceCode;
    private final String deviceName;
    private final String os;
    private final String version;
    private final KeyPair keyPair;
    private final String publicKeyBase64;
    private final String fingerprint;
    private volatile byte[] connectPassword;

    public DeviceIdentity(String deviceCode, String deviceName, String os, String version,
                          KeyPair keyPair, String publicKeyBase64, String fingerprint,
                          byte[] connectPassword) {
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.os = os;
        this.version = version;
        this.keyPair = keyPair;
        this.publicKeyBase64 = publicKeyBase64;
        this.fingerprint = fingerprint;
        this.connectPassword = connectPassword;
    }

    /** 生成全新设备身份（随机连接码 + 新 RSA 密钥对）。 */
    public static DeviceIdentity create(String connectPassword) {
        CryptoService.DeviceKeyPair pair = CryptoService.generateDeviceKeyPair();
        return new DeviceIdentity(
                IdGenerator.newDeviceCode(),
                hostName(),
                System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", ""),
                "1.0.0",
                pair.keyPair(),
                pair.publicKeyBase64(),
                pair.fingerprint(),
                connectPassword == null ? null : connectPassword.getBytes(StandardCharsets.UTF_8));
    }

    public String deviceCode() {
        return deviceCode;
    }

    public String deviceName() {
        return deviceName;
    }

    public String os() {
        return os;
    }

    public String version() {
        return version;
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public String publicKeyBase64() {
        return publicKeyBase64;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public boolean verifyPassword(byte[] password) {
        return connectPassword != null && Arrays.equals(connectPassword, password);
    }

    /** 运行时设置连接密码（被控端校验邀请用；密码仅存内存，不落盘）。 */
    public void setConnectPassword(String password) {
        this.connectPassword = password == null || password.isEmpty()
                ? null : password.getBytes(StandardCharsets.UTF_8);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
