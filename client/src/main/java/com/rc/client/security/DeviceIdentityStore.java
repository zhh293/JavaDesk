package com.rc.client.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.common.crypto.RsaCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 本机设备身份持久化：把 {@link DeviceIdentity} 的 RSA 密钥对 / 设备码落盘为 JSON，
 * 保证设备码跨启动稳定（否则每次随机新设备码会导致「本机码」失效）。
 *
 * <p>连接密码不持久化，仅存内存，由 UI 运行时设置。</p>
 */
public final class DeviceIdentityStore {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityStore.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeviceIdentityStore() {
        this(Path.of(System.getProperty("user.home", "."), ".rc-client", "identity.json"));
    }

    public DeviceIdentityStore(Path file) {
        this.file = file;
    }

    /** 持久化字段：deviceCode + 密钥对（公钥 / 私钥 base64）+ 指纹。 */
    private record Stored(String deviceCode, String deviceName, String os, String version,
                          String publicKeyBase64, String privateKeyBase64, String fingerprint) {
    }

    /** 加载身份；不存在或损坏则生成新身份并落盘。 */
    public DeviceIdentity load() {
        if (Files.exists(file)) {
            try {
                Stored s = mapper.readValue(file.toFile(), Stored.class);
                PublicKey pub = RsaCipher.decodePublicKey(s.publicKeyBase64());
                PrivateKey priv = RsaCipher.decodePrivateKey(s.privateKeyBase64());
                return new DeviceIdentity(s.deviceCode(), s.deviceName(), s.os(), s.version(),
                        new KeyPair(pub, priv), s.publicKeyBase64(), s.fingerprint(), null);
            } catch (Exception e) {
                log.warn("identity load failed ({}), regenerating", e.getMessage());
            }
        }
        DeviceIdentity created = DeviceIdentity.create(null);
        save(created);
        return created;
    }

    public void save(DeviceIdentity identity) {
        try {
            Files.createDirectories(file.getParent());
            Stored s = new Stored(identity.deviceCode(), identity.deviceName(), identity.os(), identity.version(),
                    identity.publicKeyBase64(), RsaCipher.encodePrivateKey(identity.privateKey()), identity.fingerprint());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), s);
        } catch (Exception e) {
            log.warn("identity save failed: {}", e.getMessage());
        }
    }
}
