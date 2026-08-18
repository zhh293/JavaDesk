package com.rc.signaling.session;

import com.rc.signaling.config.SignalingProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机内存实现（dev 默认），TTL 靠访问时惰性淘汰。
 */
@Component
@Profile("!prod")
public class InMemoryDeviceRegistry implements DeviceRegistry {

    private final long ttlMillis;
    private final Map<Long, Entry> online = new ConcurrentHashMap<>();

    public InMemoryDeviceRegistry(SignalingProperties props) {
        this.ttlMillis = props.getDeviceTtlSeconds() * 1000L;
    }

    @Override
    public void online(long deviceId, String nodeId) {
        online.put(deviceId, new Entry(nodeId, System.currentTimeMillis()));
    }

    @Override
    public void heartbeat(long deviceId, String nodeId) {
        online.put(deviceId, new Entry(nodeId, System.currentTimeMillis()));
    }

    @Override
    public void offline(long deviceId) {
        online.remove(deviceId);
    }

    @Override
    public boolean isOnline(long deviceId) {
        return nodeOf(deviceId) != null;
    }

    @Override
    public String nodeOf(long deviceId) {
        Entry entry = online.get(deviceId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.lastSeen() > ttlMillis) {
            online.remove(deviceId);
            return null;
        }
        return entry.nodeId();
    }

    private record Entry(String nodeId, long lastSeen) {
    }
}
