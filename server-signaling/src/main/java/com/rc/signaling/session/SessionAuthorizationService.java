package com.rc.signaling.session;

import com.rc.signaling.connection.ConnectionContext;
import org.springframework.stereotype.Service;

/** Centralized command authorization based only on authenticated connection identity and metadata. */
@Service
public final class SessionAuthorizationService {
    public enum Command { INVITE_DECISION, CANDIDATE, SWITCH_ROUTE, READY, END, SNAPSHOT }

    public void authorize(ConnectionContext actor, Command command, SessionMetadata session) {
        if (actor == null || session == null || !session.isMember(actor.deviceId())) {
            throw new SecurityException("not a session member");
        }
        long expectedEpoch = session.isController(actor.deviceId())
                ? session.controllerConnectionEpoch() : session.agentConnectionEpoch();
        if (expectedEpoch > 0 && actor.connectionEpoch() != expectedEpoch) {
            throw new SecurityException("stale connection epoch");
        }
        if (command == Command.INVITE_DECISION && !session.isAgent(actor.deviceId())) {
            throw new SecurityException("only the agent may decide an invitation");
        }
        if (session.state().terminal() && command != Command.SNAPSHOT && command != Command.END) {
            throw new IllegalStateException("session already ended");
        }
    }
}
