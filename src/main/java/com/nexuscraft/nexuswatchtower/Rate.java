package com.nexuscraft.nexuswatchtower;

/**
 * "{@code limit} events inside {@code windowMs}" detector with a fixed ring of timestamps, so memory
 * stays tiny however fast events arrive. After it fires it forgets everything, so it needs another
 * full {@code limit} events before it can fire again.
 */
final class Rate {
    private final long[] ring;
    private final long windowMs;
    private int count;
    private int next;

    Rate(int limit, long windowMs) {
        this.ring = new long[Math.max(1, limit)];
        this.windowMs = Math.max(1, windowMs);
    }

    int limit() {
        return ring.length;
    }

    /** Records one event and returns true if it was the {@code limit}-th inside the window. */
    boolean hit(long now) {
        ring[next] = now;
        next = (next + 1) % ring.length;
        if (count < ring.length) {
            count++;
        }
        if (count < ring.length) {
            return false;
        }
        long oldest = ring[next];
        if (now - oldest <= windowMs) {
            count = 0;
            next = 0;
            return true;
        }
        return false;
    }
}
