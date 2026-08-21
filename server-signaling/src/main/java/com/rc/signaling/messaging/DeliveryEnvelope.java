package com.rc.signaling.messaging;

/** Durable at-least-once delivery wrapper; payload remains the external Protobuf Signal bytes. */
public record DeliveryEnvelope(String messageId, String causationId, String traceId,
                               long targetDeviceId, long targetConnectionEpoch, String targetNodeId,
                               Long sessionId, Long sessionVersion, Long routeEpoch,
                               String messageType, byte[] payload,
                               long createdAt, long deadlineAt, int attempt) {
    public DeliveryEnvelope {
        if (messageId == null || messageId.isBlank() || messageId.length() > 128
                || targetDeviceId <= 0 || targetConnectionEpoch <= 0
                || targetNodeId == null || targetNodeId.isBlank()
                || deadlineAt <= createdAt || attempt < 0) {
            throw new IllegalArgumentException("invalid delivery envelope");
        }
        payload = payload == null ? new byte[0] : payload.clone();
    }
}
