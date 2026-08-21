package com.rc.common.protocol;

import java.util.Objects;
import java.util.UUID;

/** Globally unique idempotency key. */
public record MessageId(String value) {
    public MessageId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("message id length must be 1..128");
        }
    }

    public static MessageId random() {
        return new MessageId(UUID.randomUUID().toString());
    }
}
