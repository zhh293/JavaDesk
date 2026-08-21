package com.rc.signaling.relay;

import com.rc.common.model.RelayNode;

import java.util.List;

/** Candidate discovery only. It never decides or mutates a committed session route. */
public interface RelayDiscovery {
    List<RelayNode> healthyNodes();

    /** Dev/local registration hook; Nacos production discovery deliberately ignores HTTP liveness. */
    default void acceptHeartbeat(RelayNode node) { }

    default void remove(String nodeId) { }
}
