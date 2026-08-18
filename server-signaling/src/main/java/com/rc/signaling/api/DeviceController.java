package com.rc.signaling.api;

import com.rc.common.constant.ErrorCode;
import com.rc.common.model.Device;
import com.rc.signaling.api.dto.DeviceInfoResponse;
import com.rc.signaling.dao.DeviceMapper;
import com.rc.signaling.session.ConnectionRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备信息查询：控制端取被控端公钥 / 指纹 / NAT 类型。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceMapper deviceMapper;
    private final ConnectionRegistry connectionRegistry;

    public DeviceController(DeviceMapper deviceMapper, ConnectionRegistry connectionRegistry) {
        this.deviceMapper = deviceMapper;
        this.connectionRegistry = connectionRegistry;
    }

    @GetMapping("/{deviceCode}")
    public ApiResult<DeviceInfoResponse> getDevice(@PathVariable String deviceCode) {
        Device device = deviceMapper.findByDeviceCode(deviceCode);
        if (device == null) {
            throw new ApiException(ErrorCode.TARGET_OFFLINE, HttpStatus.NOT_FOUND, "device not found");
        }
        boolean online = connectionRegistry.channelOf(device.getId()) != null;
        return ApiResult.ok(new DeviceInfoResponse(
                device.getId(),
                device.getDeviceCode(),
                device.getDeviceName(),
                device.getDevicePublicKey(),
                device.getPublicKeyFingerprint(),
                device.getNatType(),
                online));
    }
}
