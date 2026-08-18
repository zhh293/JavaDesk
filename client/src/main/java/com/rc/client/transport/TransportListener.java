package com.rc.client.transport;

import com.rc.common.codec.DataFrame;

/**
 * 传输通道事件回调。
 */
public interface TransportListener {

    /** 收到一帧数据（已按 channel 区分）。 */
    void onData(DataFrame frame);

    /** 通道关闭（正常关闭 cause 为 null）。 */
    void onClosed(Throwable cause);
}
