package com.nexuscraft.nexuswatchtower;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The people the owner is keeping a closer eye on, saved to watchlist.tsv. */
final class Watchlist {

    enum Mode {
        /** Everything they do is logged and streamed to the owner. */
        OBSERVE,
        /** Observed, plus dangerous commands are blocked and a big tripwire freezes them. */
        GUARD;

        static Mode parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "observe", "watch" -> OBSERVE;
                case "guard" -> GUARD;
                default -> null;
            };
        }
    }

    static final class Entry {
        String uuid = "";
        String name;
        Mode mode = Mode.OBSERVE;
        boolean locked;
        long added;
        String note = "";
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean lockdown;

    Watchlist(File file) {
        this.file = file;
        load();
    }

    List<String> warnings() {
        return warnings;
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            int n = 0;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                n++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> f = Esc.split(line);
                if (f.size() == 2 && f.get(0).equals("L")) {
                    lockdown = f.get(1).equals("1");
                    continue;
                }
                if (f.size() < 7 || !f.get(0).equals("W")) {
                    warnings.add("watchlist.tsv line " + n + " skipped (not a valid record).");
                    continue;
                }
                Entry e = new Entry();
                e.uuid = f.get(1);
                e.name = f.get(2);
                Mode m = Mode.parse(f.get(3));
                e.mode = m == null ? Mode.OBSERVE : m;
                e.locked = f.get(4).equals("1");
                try {
                    e.added = Long.parseLong(f.get(5));
                } catch (NumberFormatException ex) {
                    e.added = 0L;
                }
                e.note = f.get(6);
                if (e.name == null || e.name.isBlank()) {
                    warnings.add("watchlist.tsv line " + n + " skipped (no name).");
                    continue;
                }
                entries.add(e);
            }
        } catch (IOException e) {
            warnings.add("Could not read watchlist.tsv: " + e.getMessage());
        }
    }

    private void save() {
        StringBuilder b = new StringBuilder("# NexusWatchtower watchlist. Edit with /watchtower, not by hand.\n");
        if (lockdown) {
            b.append(Esc.join("L", "1")).append('\n');
        }
        for (Entry e : entries) {
            b.append(Esc.join("W", e.uuid, e.name, e.mode.name(), e.locked ? "1" : "0", e.added, e.note)).append('\n');
        }
        AtomicFile.write(file, b.toString());
    }

    synchronized boolean lockdown() {
        return lockdown;
    }

    synchronized void setLockdown(boolean on) {
        lockdown = on;
        save();
    }

    synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    synchronized int size() {
        return entries.size();
    }

    /** The entry for this player: by UUID once known, otherwise by name. */
    synchronized Entry find(Identity who) {
        if (who == null) {
            return null;
        }
        String id = who.uuid() == null ? "" : who.uuid().toString();
        for (Entry e : entries) {
            if (!e.uuid.isEmpty() && e.uuid.equalsIgnoreCase(id)) {
                return e;
            }
        }
        String clean = who.cleanName();
        for (Entry e : entries) {
            if (e.uuid.isEmpty() && Identity.clean(e.name).equals(clean)) {
                return e;
            }
        }
        return null;
    }

    synchronized Entry findByName(String typed) {
        String clean = Identity.clean(typed);
        for (Entry e : entries) {
            if (Identity.clean(e.name).equals(clean)) {
                return e;
            }
        }
        return null;
    }

    /** Adds or updates. {@code uuid} may be null when the player has never been seen. */
    synchronized Entry add(String name, UUID uuid, Mode mode, String note, long now) {
        Entry e = findByName(name);
        if (e == null) {
            e = new Entry();
            e.added = now;
            entries.add(e);
        }
        e.name = name;
        if (uuid != null) {
            e.uuid = uuid.toString();
        }
        e.mode = mode;
        if (note != null && !note.isBlank()) {
            e.note = note.trim();
        }
        save();
        return e;
    }

    synchronized boolean remove(String name) {
        Entry e = findByName(name);
        if (e == null) {
            return false;
        }
        entries.remove(e);
        save();
        return true;
    }

    synchronized void setMode(Entry e, Mode mode) {
        e.mode = mode;
        save();
    }

    synchronized void setLocked(Entry e, boolean locked) {
        e.locked = locked;
        save();
    }

    /** Remembers the real UUID (and current name) the first time a name-only entry actually joins. */
    synchronized void bind(Identity who) {
        Entry e = find(who);
        if (e != null && e.uuid.isEmpty() && who.uuid() != null) {
            e.uuid = who.uuid().toString();
            e.name = who.name();
            save();
        } else if (e != null && who.name() != null && !who.name().equals(e.name)) {
            e.name = who.name();
            save();
        }
    }
}
