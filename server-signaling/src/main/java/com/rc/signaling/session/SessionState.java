package com.rc.signaling.session;

public enum SessionState {
    INVITING, ACCEPTED, NEGOTIATING, ACTIVE, ENDING, ENDED;

    public boolean terminal() {
        return this == ENDED;
    }
}
