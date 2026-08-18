package com.rc.signaling.session;

/**
 * 在线设备注册表抽象：维护 {@code deviceId -> 信令节点} 映射，支撑跨节点路由与在线判定。
 *
 * <p>dev 用 {@link InMemoryDeviceRegistry}（单机内存），prod 用 {@link RedisDeviceRegistry}
 * （Redis 集群，key {@code device:online:{deviceId}}，TTL 心跳续期）。</p>
 */
public interface DeviceRegistry {

    /** 设备上线（写入所在信令节点，带 TTL）。 */
    void online(long deviceId, String nodeId);

    /** 心跳续期（刷新 TTL）。 */
    void heartbeat(long deviceId, String nodeId);

    /** 设备下线。 */
    void offline(long deviceId);

    boolean isOnline(long deviceId);

    /** 返回设备所在信令节点 ID，离线返回 {@code null}。 */
    String nodeOf(long deviceId);
}
