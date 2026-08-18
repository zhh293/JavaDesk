package com.rc.signaling.service;

import com.rc.common.constant.ErrorCode;

/**
 * 设备上报失败（设备归属冲突等），由信令 Handler 捕获并转 RegisterResp 错误码。
 */
public class DeviceRegistrationException extends RuntimeException {

    private final ErrorCode errorCode;

    public DeviceRegistrationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
