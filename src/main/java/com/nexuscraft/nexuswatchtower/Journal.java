package com.nexuscraft.nexuswatchtower;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** A private record of everything noteworthy: a bounded in-memory list for /watchtower log, and one text file per day on disk. */
final class Journal {
    private final File dir;
    private final ZoneId zone;
    private final int memory;
    private final Deque<Alert> recent = new ArrayDeque<>();
    private BufferedWriter out;
    private LocalDate outDay;
    private boolean failed;

    Journal(File dir, ZoneId zone, int memory) {
        this.dir = dir;
        this.zone = zone;
        this.memory = memory;
    }

    synchronized void add(Alert a) {
        recent.addLast(a);
        while (recent.size() > memory) {
            recent.removeFirst();
        }
        if (dir == null) {
            return;
        }
        try {
            LocalDate day = Instant.ofEpochMilli(a.time()).atZone(zone).toLocalDate();
            if (out == null || !day.equals(outDay)) {
                closeQuietly();
                Files.createDirectories(dir.toPath());
                out = Files.newBufferedWriter(new File(dir, "journal-" + day + ".log").toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                outDay = day;
            }
            out.write(Esc.join(a.time(), a.severity(), a.category(), a.who(), a.title(), a.detail()));
            out.newLine();
            out.flush();
            failed = false;
        } catch (IOException e) {
            failed = true;
            closeQuietly();
        }
    }

    /** True if the last attempt to write the journal file failed. */
    synchronized boolean writeFailed() {
        return failed;
    }

    /** Newest last. {@code who} may be null for everyone; a name matches ignoring case and a leading Bedrock dot. */
    synchronized List<Alert> recent(String who, int count) {
        String clean = who == null ? null : Identity.clean(who);
        List<Alert> out2 = new ArrayList<>();
        Alert[] all = recent.toArray(new Alert[0]);
        for (int i = all.length - 1; i >= 0 && out2.size() < count; i--) {
            if (clean == null || Identity.clean(all[i].who()).equals(clean)) {
                out2.add(0, all[i]);
            }
        }
        return out2;
    }

    synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            out = null;
        }
    }

    /** Deletes journal files older than {@code keepDays}. Returns how many were removed. */
    int prune(int keepDays, long now) {
        if (dir == null) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        LocalDate cutoff = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(keepDays);
        int removed = 0;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("journal-") && n.endsWith(".log")) {
                try {
                    LocalDate d = LocalDate.parse(n.substring("journal-".length(), n.length() - ".log".length()));
                    if (d.isBefore(cutoff) && f.delete()) {
                        removed++;
                    }
                } catch (RuntimeException ignored) {
                    // not one of ours
                }
            }
        }
        return removed;
    }

    static String describe(Alert a, ZoneId zone) {
        String t = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)
                .format(Instant.ofEpochMilli(a.time()).atZone(zone));
        String who = a.who().isEmpty() ? "" : a.who() + ": ";
        String detail = a.detail().isEmpty() ? "" : " -- " + a.detail();
        return t + " [" + a.severity() + "] " + a.category() + " | " + who + a.title() + detail;
    }
}
