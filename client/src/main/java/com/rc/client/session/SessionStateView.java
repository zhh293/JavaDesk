package com.rc.client.session;

import com.rc.common.constant.SessionStatus;

public record SessionStateView(long sessionId, SessionStatus status, RouteStateView route,
                               boolean ended, String lastFailure) {
}
