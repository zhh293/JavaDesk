package com.rc.common.crypto;

/**
 * 加解密失败统一异常（运行时异常，避免污染调用栈签名）。
 */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
