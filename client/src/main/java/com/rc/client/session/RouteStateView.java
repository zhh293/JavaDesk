package com.rc.client.session;

import com.rc.common.protocol.PathType;

public record RouteStateView(long activeEpoch, long preparingEpoch, PathType activePath,
                             String assignmentId, boolean migrationInProgress) {
    public RouteStateView {
        if (activeEpoch < 0 || preparingEpoch < 0 || preparingEpoch < activeEpoch) {
            throw new IllegalArgumentException("invalid route state view");
        }
    }
}
