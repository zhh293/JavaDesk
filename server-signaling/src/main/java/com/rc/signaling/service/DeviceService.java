package com.rc.signaling.service;

import com.rc.common.constant.ErrorCode;
import com.rc.common.model.Device;
import com.rc.common.protocol.RegisterReq;
import com.rc.signaling.dao.DeviceMapper;
import com.rc.signaling.session.DeviceRegistry;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final DeviceMapper deviceMapper;
    private final DeviceRegistry deviceRegistry;
    private final JwtService jwtService;

    public DeviceService(DeviceMapper deviceMapper, DeviceRegistry deviceRegistry, JwtService jwtService) {
        this.deviceMapper = deviceMapper;
        this.deviceRegistry = deviceRegistry;
        this.jwtService = jwtService;
    }

    public record Registration(long deviceId, long userId, boolean created) {
    }

    /** 解析长连接上报的访问令牌，返回 userId；失败抛 {@link JwtException}。 */
    public long authenticateUserId(String token) {
        Claims claims = jwtService.parse(token, JwtService.TYPE_ACCESS);
        return jwtService.userIdOf(claims);
    }

    /**
     * 设备上报：按 device_code 幂等 upsert，并写入在线注册表。
     * 已存在但归属他人时抛 {@link DeviceRegistrationException}（{@code RC-1002}）。
     */
    @Transactional
    public Registration registerDevice(long userId, RegisterReq req, String nodeId) {
        Device existing = deviceMapper.findByDeviceCode(req.getDeviceCode());
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                throw new DeviceRegistrationException(ErrorCode.DEVICE_CONFLICT,
                        "device code already bound to another user");
            }
            applyReported(existing, req);
            deviceMapper.updateOnlineInfo(existing);
            deviceRegistry.online(existing.getId(), nodeId);
            return new Registration(existing.getId(), userId, false);
        }

        Device device = new Device();
        device.setUserId(userId);
        device.setDeviceCode(req.getDeviceCode());
        applyReported(device, req);
        device.setConnectPasswordHash(null);
        device.setLastOnlineAt(System.currentTimeMillis());
        device.setStatus(Device.STATUS_ONLINE);
        deviceMapper.insert(device);
        deviceRegistry.online(device.getId(), nodeId);
        return new Registration(device.getId(), userId, true);
    }

    public void renewHeartbeat(long deviceId, String nodeId) {
        deviceRegistry.heartbeat(deviceId, nodeId);
    }

    public void touchOnline(long deviceId, long now) {
        deviceMapper.updateStatus(deviceId, Device.STATUS_ONLINE, now);
    }

    public void markOffline(long deviceId, long now) {
        deviceRegistry.offline(deviceId);
        deviceMapper.updateStatus(deviceId, Device.STATUS_OFFLINE, now);
    }

    private void applyReported(Device device, RegisterReq req) {
        device.setDeviceName(req.getDeviceName());
        device.setOs(req.getOs());
        device.setVersion(req.getVersion());
        device.setDevicePublicKey(req.getPublicKey());
        device.setPublicKeyFingerprint(req.getPublicKeyFingerprint());
        device.setNatType(req.getNatType().getNumber());
        device.setLastOnlineAt(System.currentTimeMillis());
        device.setStatus(Device.STATUS_ONLINE);
    }
}
