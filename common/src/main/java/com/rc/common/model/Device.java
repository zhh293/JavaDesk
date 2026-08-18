package com.rc.common.model;

/**
 * 设备实体（对应 MySQL {@code device} 表）。
 */
public class Device {

    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_ONLINE = 1;

    private Long id;
    private Long userId;
    private String deviceCode;
    private String deviceName;
    private String os;
    private String version;
    private String connectPasswordHash;
    private String devicePublicKey;
    private String publicKeyFingerprint;
    private int natType;
    private Long lastOnlineAt;
    private int status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getConnectPasswordHash() {
        return connectPasswordHash;
    }

    public void setConnectPasswordHash(String connectPasswordHash) {
        this.connectPasswordHash = connectPasswordHash;
    }

    public String getDevicePublicKey() {
        return devicePublicKey;
    }

    public void setDevicePublicKey(String devicePublicKey) {
        this.devicePublicKey = devicePublicKey;
    }

    public String getPublicKeyFingerprint() {
        return publicKeyFingerprint;
    }

    public void setPublicKeyFingerprint(String publicKeyFingerprint) {
        this.publicKeyFingerprint = publicKeyFingerprint;
    }

    public int getNatType() {
        return natType;
    }

    public void setNatType(int natType) {
        this.natType = natType;
    }

    public Long getLastOnlineAt() {
        return lastOnlineAt;
    }

    public void setLastOnlineAt(Long lastOnlineAt) {
        this.lastOnlineAt = lastOnlineAt;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public boolean isOnline() {
        return status == STATUS_ONLINE;
    }
}
