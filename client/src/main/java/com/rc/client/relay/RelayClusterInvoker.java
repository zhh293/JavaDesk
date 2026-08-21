package com.rc.client.relay;

import java.util.concurrent.CompletionStage;

public interface RelayClusterInvoker {
    CompletionStage<PreparedRelayChannel> prepare(RelayAssignment assignment);
    void reportOutcome(RelayOutcome outcome);
}
