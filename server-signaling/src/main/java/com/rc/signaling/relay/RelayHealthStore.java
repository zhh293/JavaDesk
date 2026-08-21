package com.rc.signaling.relay;

import com.rc.common.protocol.PathType;

public interface RelayHealthStore {
    void updateRuntime(RelayRuntimeSample sample);
    void record(RelayObservation observation);
    RelayHealth health(String nodeId, String region, String networkProvider, PathType pathType);
}
