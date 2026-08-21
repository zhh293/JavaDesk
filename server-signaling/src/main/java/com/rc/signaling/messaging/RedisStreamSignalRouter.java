package com.rc.signaling.messaging;

import com.rc.signaling.config.SignalingProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Production cross-node router using durable Redis Streams rather than lossy Pub/Sub. */
@Component
@Profile("prod")
public final class RedisStreamSignalRouter implements SignalRouter {
    private final StringRedisTemplate redis;
    private final LocalSignalDelivery local;
    private final String localNodeId;

    public RedisStreamSignalRouter(StringRedisTemplate redis, ConnectionRegistrySignalDelivery local,
                                   SignalingProperties properties) {
        this.redis = redis;
        this.local = local;
        this.localNodeId = properties.getNodeId();
    }

    @Override
    public boolean route(DeliveryEnvelope envelope) {
        if (envelope.deadlineAt() <= System.currentTimeMillis()) return false;
        if (localNodeId.equals(envelope.targetNodeId()) && local.deliver(envelope)) return true;
        Map<String, String> fields = encode(envelope);
        return redis.opsForStream().add(StreamRecords.mapBacked(fields)
                .withStreamKey(inbox(envelope.targetNodeId()))) != null;
    }

    static Map<String, String> encode(DeliveryEnvelope e) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("messageId", e.messageId()); f.put("causationId", safe(e.causationId()));
        f.put("traceId", safe(e.traceId())); f.put("targetDeviceId", Long.toString(e.targetDeviceId()));
        f.put("targetConnectionEpoch", Long.toString(e.targetConnectionEpoch()));
        f.put("targetNodeId", e.targetNodeId()); f.put("sessionId", nullable(e.sessionId()));
        f.put("sessionVersion", nullable(e.sessionVersion())); f.put("routeEpoch", nullable(e.routeEpoch()));
        f.put("messageType", safe(e.messageType()));
        f.put("payload", Base64.getEncoder().encodeToString(e.payload()));
        f.put("createdAt", Long.toString(e.createdAt())); f.put("deadlineAt", Long.toString(e.deadlineAt()));
        f.put("attempt", Integer.toString(e.attempt()));
        return f;
    }

    static DeliveryEnvelope decode(Map<Object, Object> f) {
        return new DeliveryEnvelope(text(f, "messageId"), text(f, "causationId"), text(f, "traceId"),
                Long.parseLong(text(f, "targetDeviceId")), Long.parseLong(text(f, "targetConnectionEpoch")),
                text(f, "targetNodeId"), nullableLong(text(f, "sessionId")),
                nullableLong(text(f, "sessionVersion")), nullableLong(text(f, "routeEpoch")),
                text(f, "messageType"), Base64.getDecoder().decode(text(f, "payload")),
                Long.parseLong(text(f, "createdAt")), Long.parseLong(text(f, "deadlineAt")),
                Integer.parseInt(text(f, "attempt")));
    }

    static String inbox(String nodeId) { return "rc:v2:signal:inbox:{" + nodeId + "}"; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String nullable(Long value) { return value == null ? "" : value.toString(); }
    private static Long nullableLong(String value) { return value.isEmpty() ? null : Long.valueOf(value); }
    private static String text(Map<Object, Object> f, String key) {
        Object value = f.get(key); return value == null ? "" : value.toString();
    }
}
