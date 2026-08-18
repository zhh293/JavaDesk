package com.rc.signaling.api.dto;

/**
 * 目标设备信息，供控制端发起邀请前取公钥 / 指纹 / NAT 类型进行 E2EE 邀请加密。
 */
public record DeviceInfoResponse(long deviceId, String deviceCode, String deviceName,
                                 String publicKey, String fingerprint, int natType, boolean online) {
}
