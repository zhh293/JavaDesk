package com.rc.signaling.route;

/** Typed CAS conflict carrying the current authoritative snapshot. */
public final class RouteConflictException extends RuntimeException {
    public enum Reason { STALE_EPOCH, MIGRATION_IN_PROGRESS, SESSION_ENDED }
    private final Reason reason;
    private final SessionRoute current;

    public RouteConflictException(Reason reason, SessionRoute current) {
        super(reason.name());
        this.reason = reason;
        this.current = current;
    }

    public Reason reason() { return reason; }
    public SessionRoute current() { return current; }
}
