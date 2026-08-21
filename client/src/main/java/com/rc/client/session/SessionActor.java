package com.rc.client.session;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Per-session serial actor; all asynchronous callbacks enqueue events instead of mutating shared fields. */
public final class SessionActor implements AutoCloseable {
    private final java.util.concurrent.ExecutorService executor;
    private final Consumer<SessionStateView> observer;
    private volatile SessionStateView view;

    public SessionActor(SessionStateView initial, Consumer<SessionStateView> observer) {
        this.view = Objects.requireNonNull(initial, "initial");
        this.observer = observer == null ? ignored -> { } : observer;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rc-session-actor-" + initial.sessionId());
            t.setDaemon(true); return t;
        });
    }

    public void accept(SessionEvent event) {
        executor.execute(() -> {
            SessionStateView next = SessionReducer.reduce(view, event);
            if (next != view) {
                view = next;
                observer.accept(next);
            }
        });
    }

    public SessionStateView view() { return view; }
    @Override public void close() { executor.shutdownNow(); }
}
