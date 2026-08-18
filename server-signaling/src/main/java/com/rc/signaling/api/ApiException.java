package com.rc.signaling.api;

import com.rc.common.constant.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带统一失败码与 HTTP 状态，由 {@link GlobalExceptionHandler} 兜底转出。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    public ApiException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public ApiException(ErrorCode errorCode, HttpStatus status) {
        this(errorCode, status, errorCode.description());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, HttpStatus.BAD_REQUEST, message);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
