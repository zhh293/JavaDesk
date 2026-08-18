package com.rc.client.signaling;

import com.rc.common.protocol.Signal;

/**
 * 信令客户端事件回调。
 */
public interface SignalingListener {

    /** 长连接建立成功。 */
    void onConnected();

    /** 连接断开（{@code cause} 可能为 null）。 */
    void onDisconnected(Throwable cause);

    /** 收到一帧信令。 */
    void onSignal(Signal signal);
}
