package com.rc.signaling.route;

import com.rc.common.protocol.PathType;

import java.util.Set;

/** Authoritative route snapshot; preparing and committed routes are stored separately. */
public record SessionRoute(long sessionId, long routeEpoch, RouteState state, PathType pathType,
                           String relayNodeId, String relayHost, int relayPort, boolean tls,
                           String switchRequestId, String assignmentId, long baseEpoch,
                           boolean controllerReady, boolean agentReady,
                           long prepareDeadlineAt, long commitAt, String failureReason,
                           Set<String> excludedRelayNodeIds) {
    public SessionRoute {
        if (sessionId <= 0 || routeEpoch < 0 || baseEpoch < 0 || relayPort < 0) {
            throw new IllegalArgumentException("invalid session route");
        }
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(pathType, "pathType");
        switchRequestId = switchRequestId == null ? "" : switchRequestId;
        assignmentId = assignmentId == null ? "" : assignmentId;
        excludedRelayNodeIds = excludedRelayNodeIds == null ? Set.of() : Set.copyOf(excludedRelayNodeIds);
    }

    public boolean bothReady() {
        return controllerReady && agentReady;
    }
}
