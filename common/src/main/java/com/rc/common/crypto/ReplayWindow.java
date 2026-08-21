package com.rc.common.crypto;

/** Thread-safe 64-packet sliding replay window for an unsigned 32-bit sequence. */
public final class ReplayWindow {
    private long highest = -1;
    private long bitmap;

    public synchronized boolean accept(long sequence) {
        if (sequence < 0 || sequence > 0xffff_ffffL) {
            return false;
        }
        if (highest < 0) {
            highest = sequence;
            bitmap = 1L;
            return true;
        }
        if (sequence > highest) {
            long shift = sequence - highest;
            bitmap = shift >= Long.SIZE ? 1L : (bitmap << shift) | 1L;
            highest = sequence;
            return true;
        }
        long distance = highest - sequence;
        if (distance >= Long.SIZE) {
            return false;
        }
        long bit = 1L << distance;
        if ((bitmap & bit) != 0) {
            return false;
        }
        bitmap |= bit;
        return true;
    }

    public synchronized long highest() {
        return highest;
    }
}
