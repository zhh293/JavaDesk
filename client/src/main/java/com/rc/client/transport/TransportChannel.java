package com.rc.client.transport;

import com.rc.common.constant.ChannelType;
import com.rc.common.model.ChannelInfo;

/**
 * 传输协议栈统一抽象。底层无论 P2P UDP 还是多阶降级 Relay，
 * 对上层业务流暴露完全相同的收发与容错 API（Phase 2 以 QUIC 替换裸 UDP，接口不变）。
 */
public interface TransportChannel {

    /**
     * 发送数据到指定逻辑通道。
     *
     * @param ch      通道类型（控制 / 视频 / 音频 / 文件 / 剪贴板）
     * @param payload 业务载荷（加密与否由通道约定决定）
     */
    void send(ChannelType ch, byte[] payload);

    void addListener(TransportListener listener);

    void removeListener(TransportListener listener);

    /** 当前通道状态（路径类型、RTT、丢包率）。 */
    ChannelInfo info();

    void close();
}
