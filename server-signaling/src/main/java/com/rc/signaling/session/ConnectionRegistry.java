package com.rc.signaling.session;

import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点内 {@code deviceId → Channel} 映射，支撑 Invite / Candidate 的直接路由。
 * 单机部署即用；跨节点路由后续经 Redis Pub/Sub 扩展。
 */
@Component
public class ConnectionRegistry {

    private final Map<Long, Channel> deviceChannels = new ConcurrentHashMap<>();

    public void register(long deviceId, Channel channel) {
        deviceChannels.put(deviceId, channel);
    }

    /** 仅当映射仍指向同一 channel 时移除，避免重连后的新连接被旧连接的关闭事件误删。 */
    public void unregister(long deviceId, Channel channel) {
        deviceChannels.remove(deviceId, channel);
    }

    public Channel channelOf(long deviceId) {
        return deviceChannels.get(deviceId);
    }

    /** 当前在线设备数（本节点已建立信令长连接的设备，供指标 gauge 使用）。 */
    public int size() {
        return deviceChannels.size();
    }
}
