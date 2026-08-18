package com.rc.common.model;

/**
 * 网络端点（IP + 端口）。
 */
public record Endpoint(String ip, int port) {

    @Override
    public String toString() {
        return ip + ":" + port;
    }
}
