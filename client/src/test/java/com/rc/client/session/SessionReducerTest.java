package com.rc.client.session;

import com.rc.common.constant.SessionStatus;
import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionReducerTest {
    @Test
    void staleEventsCannotRollBackOrCommitAnotherAssignment() {
        SessionStateView state = new SessionStateView(1, SessionStatus.P2P_CONNECTED,
                new RouteStateView(3, 3, PathType.P2P, "", false), false, null);
        SessionStateView preparing = SessionReducer.reduce(state,
                new SessionEvent.RoutePrepared(3, 4, PathType.RELAY_UDP, "a"));
        assertThat(preparing.route().migrationInProgress()).isTrue();
        assertThat(SessionReducer.reduce(preparing,
                new SessionEvent.RouteCommitted(3, PathType.RELAY_UDP, "a"))).isSameAs(preparing);
        assertThat(SessionReducer.reduce(preparing,
                new SessionEvent.RouteCommitted(4, PathType.RELAY_UDP, "other"))).isSameAs(preparing);
        SessionStateView committed = SessionReducer.reduce(preparing,
                new SessionEvent.RouteCommitted(4, PathType.RELAY_UDP, "a"));
        assertThat(committed.route().activeEpoch()).isEqualTo(4);
        assertThat(committed.status()).isEqualTo(SessionStatus.RELAY_CONNECTED);
        assertThat(SessionReducer.reduce(committed,
                new SessionEvent.TransportClosed(3, new RuntimeException("old")))).isSameAs(committed);
    }
}
