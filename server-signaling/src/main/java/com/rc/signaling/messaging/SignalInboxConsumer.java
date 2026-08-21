package com.rc.signaling.messaging;

import com.rc.signaling.config.SignalingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Drains the node inbox with at-least-once delivery, end-to-end dedupe and a dead-letter stream. */
@Component
@Profile("prod")
public final class SignalInboxConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SignalInboxConsumer.class);
    private final StringRedisTemplate redis;
    private final LocalSignalDelivery delivery;
    private final String nodeId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rc-signal-inbox"); t.setDaemon(true); return t;
    });
    private volatile boolean running;

    public SignalInboxConsumer(StringRedisTemplate redis, LocalSignalDelivery delivery,
                               SignalingProperties properties) {
        this.redis = redis;
        this.delivery = delivery;
        this.nodeId = properties.getNodeId();
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        executor.execute(this::loop);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void loop() {
        String inbox = RedisStreamSignalRouter.inbox(nodeId);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        StreamReadOptions.empty().count(100).block(Duration.ofSeconds(1)),
                        StreamOffset.create(inbox, ReadOffset.from("0-0")));
                if (records == null) continue;
                boolean progressed = false;
                for (MapRecord<String, Object, Object> record : records) progressed |= process(inbox, record);
                if (!progressed && !records.isEmpty()) Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("signal inbox read failed: {}", e.getMessage());
            }
        }
    }

    private boolean process(String inbox, MapRecord<String, Object, Object> record) {
        try {
            DeliveryEnvelope envelope = RedisStreamSignalRouter.decode(record.getValue());
            String dedupeKey = "rc:v2:signal:dedupe:{" + nodeId + "}:" + envelope.messageId();
            if (Boolean.TRUE.equals(redis.hasKey(dedupeKey))) {
                redis.opsForStream().delete(inbox, record.getId());
                return true;
            }
            if (envelope.deadlineAt() <= System.currentTimeMillis()) {
                redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                        "messageId", envelope.messageId(), "reason", "deadline_expired"))
                        .withStreamKey("rc:v2:signal:dead-letter:{" + nodeId + "}"));
            } else {
                // Keep the record when its target connection is temporarily absent. Reading from
                // 0-0 deliberately retries it until delivery succeeds or the deadline expires.
                if (!delivery.deliver(envelope)) {
                    return false;
                }
            }
            redis.opsForValue().set(dedupeKey, "1", Duration.ofMinutes(30));
            redis.opsForStream().delete(inbox, record.getId());
            return true;
        } catch (RuntimeException e) {
            log.warn("invalid signal inbox record {}: {}", record.getId(), e.getMessage());
            redis.opsForStream().delete(inbox, record.getId());
            return true;
        }
    }

    @Override
    public void stop() { running = false; executor.shutdownNow(); }
    @Override
    public boolean isRunning() { return running; }
}
