package com.rc.signaling.messaging;

public interface LocalSignalDelivery {
    boolean deliver(DeliveryEnvelope envelope);
}
