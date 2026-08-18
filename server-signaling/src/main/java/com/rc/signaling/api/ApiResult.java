package com.rc.signaling.api;

import com.rc.common.constant.ErrorCode;

/**
 * REST 统一响应信封。
 */
public record ApiResult<T>(int code, String message, T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.SUCCESS.code(), "success", data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(ErrorCode.SUCCESS.code(), "success", null);
    }

    public static <T> ApiResult<T> error(ErrorCode code, String message) {
        return new ApiResult<>(code.code(), message, null);
    }
}
