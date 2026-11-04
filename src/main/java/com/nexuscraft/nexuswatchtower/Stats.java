package com.nexuscraft.nexuswatchtower;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cheap counters per player since the last digest. */
final class Stats {

    static final class Rec {
        String name;
        int commands;
        int broken;
        int placed;
        int containers;
        int joins;
        long onlineMs;
        long joinedAt;
    }

    private final Map<UUID, Rec> recs = new HashMap<>();

    synchronized Rec rec(Identity who) {
        UUID id = who.uuid();
        Rec r = recs.get(id);
        if (r == null) {
            r = new Rec();
            recs.put(id, r);
        }
        if (who.name() != null) {
            r.name = who.name();
        }
        return r;
    }

    synchronized void joined(Identity who, long now) {
        Rec r = rec(who);
        r.joins++;
        r.joinedAt = now;
    }

    synchronized void left(Identity who, long now) {
        Rec r = rec(who);
        if (r.joinedAt > 0) {
            r.onlineMs += Math.max(0, now - r.joinedAt);
            r.joinedAt = 0;
        }
    }

    /** A copy of everyone's counters (online time includes the session in progress), then a reset. */
    synchronized List<Rec> takeAndReset(long now) {
        List<Rec> out = new ArrayList<>();
        for (Rec r : recs.values()) {
            Rec c = new Rec();
            c.name = r.name;
            c.commands = r.commands;
            c.broken = r.broken;
            c.placed = r.placed;
            c.containers = r.containers;
            c.joins = r.joins;
            c.onlineMs = r.onlineMs + (r.joinedAt > 0 ? Math.max(0, now - r.joinedAt) : 0);
            out.add(c);
            long keep = r.joinedAt > 0 ? now : 0;
            r.commands = 0;
            r.broken = 0;
            r.placed = 0;
            r.containers = 0;
            r.joins = 0;
            r.onlineMs = 0;
            r.joinedAt = keep;
        }
        return out;
    }

    synchronized void forgetOffline() {
        recs.values().removeIf(r -> r.joinedAt == 0 && r.commands == 0 && r.broken == 0 && r.placed == 0
                && r.containers == 0 && r.joins == 0);
    }
}
