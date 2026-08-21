package com.rc.client.transport;

import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import com.rc.common.model.ChannelInfo;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Stable business-facing channel whose committed transport delegate can advance by route epoch. */
public final class SwitchableTransportChannel implements TransportChannel {
    private record ActiveDelegate(long routeEpoch, TransportChannel channel, TransportListener bridge) { }

    private final AtomicReference<ActiveDelegate> active;
    private final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public SwitchableTransportChannel(long initialRouteEpoch, TransportChannel initialChannel) {
        if (initialRouteEpoch < 0) {
            throw new IllegalArgumentException("route epoch must be non-negative");
        }
        Objects.requireNonNull(initialChannel, "initialChannel");
        TransportListener bridge = bridgeFor(initialRouteEpoch, initialChannel);
        initialChannel.addListener(bridge);
        this.active = new AtomicReference<>(new ActiveDelegate(initialRouteEpoch, initialChannel, bridge));
    }

    /** Commits a newer delegate and retires the old one only after listener handoff. */
    public synchronized boolean commit(long routeEpoch, TransportChannel next) {
        Objects.requireNonNull(next, "next");
        if (closed) {
            next.close();
            return false;
        }
        ActiveDelegate current = active.get();
        if (routeEpoch <= current.routeEpoch()) {
            if (next != current.channel()) {
                next.close();
            }
            return routeEpoch == current.routeEpoch() && next == current.channel();
        }
        TransportListener bridge = bridgeFor(routeEpoch, next);
        next.addListener(bridge);
        ActiveDelegate replacement = new ActiveDelegate(routeEpoch, next, bridge);
        active.set(replacement);
        current.channel().removeListener(current.bridge());
        current.channel().close();
        return true;
    }

    public long routeEpoch() {
        return active.get().routeEpoch();
    }

    @Override
    public void send(ChannelType ch, byte[] payload) {
        if (closed) {
            throw new IllegalStateException("transport channel is closed");
        }
        active.get().channel().send(ch, payload);
    }

    @Override
    public void addListener(TransportListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(TransportListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ChannelInfo info() {
        return active.get().channel().info();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ActiveDelegate current = active.get();
        current.channel().removeListener(current.bridge());
        current.channel().close();
        notifyClosed(null);
        listeners.clear();
    }

    private TransportListener bridgeFor(long epoch, TransportChannel expected) {
        return new TransportListener() {
            @Override
            public void onData(DataFrame frame) {
                ActiveDelegate current = active == null ? null : active.get();
                if (!closed && current != null && current.routeEpoch() == epoch
                        && current.channel() == expected) {
                    for (TransportListener listener : listeners) {
                        listener.onData(frame);
                    }
                }
            }

            @Override
            public void onClosed(Throwable cause) {
                ActiveDelegate current = active == null ? null : active.get();
                if (!closed && current != null && current.routeEpoch() == epoch
                        && current.channel() == expected) {
                    notifyClosed(cause);
                }
            }
        };
    }

    private void notifyClosed(Throwable cause) {
        for (TransportListener listener : listeners) {
            listener.onClosed(cause);
        }
    }
}
