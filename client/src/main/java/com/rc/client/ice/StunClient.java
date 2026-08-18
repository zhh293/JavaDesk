package com.rc.client.ice;

import com.rc.common.model.Endpoint;

import java.util.concurrent.TimeUnit;

/**
 * STUN 客户端门面：在给定 {@link UdpSocket} 上做绑定请求并同步取回映射地址。
 */
public final class StunClient {

    private StunClient() {
    }

    /** 查询公网映射，返回 srflx 端点；失败抛异常。 */
    public static Endpoint query(UdpSocket socket, Endpoint server, long timeoutMs) throws Exception {
        return socket.stunRequest(server, null, timeoutMs)
                .get(timeoutMs + 500, TimeUnit.MILLISECONDS);
    }
}
