package com.rc.common.protocol;

/** Monotonic fencing token for a session data path. */
public record RouteEpoch(long value) implements Comparable<RouteEpoch> {
    public RouteEpoch {
        if (value < 0) {
            throw new IllegalArgumentException("route epoch must be non-negative");
        }
    }

    public RouteEpoch next() {
        return new RouteEpoch(Math.incrementExact(value));
    }

    @Override
    public int compareTo(RouteEpoch other) {
        return Long.compare(value, other.value);
    }
}
