package com.rc.signaling.messaging;

public interface SignalRouter {
    /** Returns true once delivered locally or durably appended to the target inbox. */
    boolean route(DeliveryEnvelope envelope);
}
