package com.nexuscraft.nexuswatchtower;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every alert goes through here: it is always journaled, then (unless INFO) de-duplicated so one
 * repeating event cannot flood the owner's phone, and sent. RED alerts are never held back by the
 * per-minute cap, only by de-duplication.
 */
final class Alerts {
    private final Settings settings;
    private final Journal journal;
    private final Sink sink;

    private final Map<String, Long> lastSent = new HashMap<>();
    private long minuteStart;
    private int yellowThisMinute;
    private int held;

    private int periodRed;
    private int periodYellow;
    private int periodInfo;
    private int periodHeld;
    private final Map<String, Integer> periodByCategory = new TreeMap<>();

    Alerts(Settings settings, Journal journal, Sink sink) {
        this.settings = settings;
        this.journal = journal;
        this.sink = sink;
    }

    /** Returns true if the alert was actually sent. */
    synchronized boolean raise(Severity sev, String category, String who, String title, String detail, long now) {
        Alert a = new Alert(now, sev, category, who == null ? "" : who, title, detail == null ? "" : detail);
        journal.add(a);
        switch (sev) {
            case RED -> periodRed++;
            case YELLOW -> periodYellow++;
            default -> periodInfo++;
        }
        if (sev != Severity.INFO) {
            periodByCategory.merge(category, 1, Integer::sum);
        }
        if (sev == Severity.INFO) {
            return false;
        }
        String key = category + "|" + a.who().toLowerCase(Locale.ROOT) + "|" + title;
        Long last = lastSent.get(key);
        if (last != null && now - last < settings.dedupeSeconds * 1000L) {
            held++;
            periodHeld++;
            return false;
        }
        if (sev == Severity.YELLOW) {
            if (now - minuteStart >= 60_000L) {
                minuteStart = now;
                yellowThisMinute = 0;
            }
            if (yellowThisMinute >= settings.maxYellowPerMinute) {
                held++;
                periodHeld++;
                return false;
            }
            yellowThisMinute++;
        }
        lastSent.put(key, now);
        if (lastSent.size() > 500) {
            Iterator<Map.Entry<String, Long>> it = lastSent.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > settings.dedupeSeconds * 1000L) {
                    it.remove();
                }
            }
        }
        sink.send(a, format(a), sev == Severity.RED);
        return true;
    }

    private String format(Alert a) {
        StringBuilder b = new StringBuilder();
        if (held > 0) {
            b.append("(").append(held).append(" similar alerts were held back since the last message)\n");
            held = 0;
        }
        b.append('[').append(a.severity()).append("] ").append(settings.serverName).append(" | ").append(a.category());
        b.append('\n');
        if (!a.who().isEmpty()) {
            b.append(a.who()).append(' ');
        }
        b.append(a.title());
        if (!a.detail().isEmpty()) {
            b.append('\n').append(a.detail());
        }
        return b.toString();
    }

    /** What happened since the last call, then starts a fresh period. */
    synchronized Period takePeriod() {
        Period p = new Period(periodRed, periodYellow, periodInfo, periodHeld, new TreeMap<>(periodByCategory));
        periodRed = 0;
        periodYellow = 0;
        periodInfo = 0;
        periodHeld = 0;
        periodByCategory.clear();
        return p;
    }

    synchronized int heldBack() {
        return held;
    }

    record Period(int red, int yellow, int info, int held, Map<String, Integer> byCategory) {
    }
}
