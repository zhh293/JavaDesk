package com.rc.signaling.session;

import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话表：{@code sessionId → SessionRecord}，维护双方设备与信令通道，支撑双向路由。
 * 单机内存实现；集群化时替换为 Redis（key {@code session:{sessionId}}）。
 */
@Component
public class SessionManager {

    public record SessionRecord(long sessionId, long controllerDeviceId, long agentDeviceId,
                                Channel controllerChannel, Channel agentChannel) {

        /** 返回对端通道（按对象同一性匹配），非会话参与方返回 {@code null}。 */
        public Channel peerOf(Channel channel) {
            if (channel == controllerChannel) {
                return agentChannel;
            }
            if (channel == agentChannel) {
                return controllerChannel;
            }
            return null;
        }
    }

    private final Map<Long, SessionRecord> sessions = new ConcurrentHashMap<>();

    public SessionRecord create(long sessionId, long controllerDeviceId, long agentDeviceId,
                                Channel controllerChannel, Channel agentChannel) {
        SessionRecord record = new SessionRecord(
                sessionId, controllerDeviceId, agentDeviceId, controllerChannel, agentChannel);
        sessions.put(sessionId, record);
        return record;
    }

    public SessionRecord get(long sessionId) {
        return sessions.get(sessionId);
    }

    public SessionRecord remove(long sessionId) {
        return sessions.remove(sessionId);
    }

    /** 当前活跃会话数（供指标 gauge 使用）。 */
    public int size() {
        return sessions.size();
    }
}
