package com.rc.client.relay;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RelayInvoker {
    CompletionStage<PreparedRelayChannel> prepare(RelayAssignment assignment);
}
