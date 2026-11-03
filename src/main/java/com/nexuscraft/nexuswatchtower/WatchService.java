package com.nexuscraft.nexuswatchtower;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * All of the watching decisions, with no Bukkit in sight so every rule can be tested directly. The
 * Bukkit layer only turns server events into calls on this class and acts on the verdicts it returns.
 * Methods are synchronized because chat arrives on another thread.
 */
final class WatchService {

    static final Set<String> COMMAND_BLOCKS = Set.of("COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK");

    final Settings settings;
    final Watchlist watch;
    final Journal journal;
    final Alerts alerts;
    final Stats stats = new Stats();
    private final Sink sink;

    private final Map<String, Rate> rates = new HashMap<>();
    private final Set<UUID> onlineWatched = new HashSet<>();
    private final Map<UUID, String> onlineWatchedNames = new HashMap<>();
    private final List<String> feedLines = new ArrayList<>();
    private int feedDropped;
    private Map<UUID, String> prevOps;
    private boolean ready;
    private long lastCoordination = -1;
    private final Set<String> reportedTamper = new HashSet<>();
    private String tamperStatus = "not checked yet";
    private final Map<String, Integer> blocksChanged = new HashMap<>();

    WatchService(Settings settings, Watchlist watch, Journal journal, Sink sink) {
        this.settings = settings;
        this.watch = watch;
        this.journal = journal;
        this.sink = sink;
        this.alerts = new Alerts(settings, journal, sink);
    }

    // ---------------------------------------------------------------- basics

    boolean owner(Identity who) {
        if (who == null) {
            return false;
        }
        if (who.name() != null && settings.owners.contains(who.name().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return who.uuid() != null && settings.owners.contains(who.uuid().toString().toLowerCase(Locale.ROOT));
    }

    synchronized void markReady() {
        ready = true;
    }

    synchronized boolean ready() {
        return ready;
    }

    private static String nm(Identity who) {
        return who.name() == null ? "unknown" : who.name();
    }

    private String hms(long now) {
        return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).format(Instant.ofEpochMilli(now).atZone(settings.zone));
    }

    /** Frozen individually, or everybody who is not an owner is frozen by a server-wide lockdown. */
    private boolean locked(Identity who, Watchlist.Entry w) {
        return (w != null && w.locked) || (watch.lockdown() && !owner(who));
    }

    private String where(String world, int x, int y, int z) {
        return world + " " + x + " " + y + " " + z;
    }

    private boolean rate(Identity who, String kind, int limit, long windowMs, long now) {
        String key = who.uuid() + ":" + kind + ":" + limit + ":" + windowMs;
        Rate r = rates.get(key);
        if (r == null) {
            r = new Rate(limit, windowMs);
            rates.put(key, r);
        }
        return r.hit(now);
    }

    private void escalate(Watchlist.Entry w, Identity who, long now) {
        if (w != null && w.mode == Watchlist.Mode.GUARD && settings.autoLockdown && !w.locked) {
            watch.setLocked(w, true);
            alerts.raise(Severity.RED, "lockdown", nm(who), "was frozen automatically after a tripwire fired",
                    "They keep operator but cannot build, break, run commands or fight until you run /watchtower unlock " + w.name + ".", now);
        }
    }

    private void feedLine(String line, long now) {
        if (!settings.streamCommands) {
            return;
        }
        if (feedLines.size() >= settings.feedMaxLines) {
            feedDropped++;
            return;
        }
        feedLines.add(hms(now) + " " + line);
    }

    /** Sends the batched activity feed, if there is anything to say. */
    synchronized void flushFeed() {
        if (feedLines.isEmpty() && feedDropped == 0) {
            return;
        }
        StringBuilder b = new StringBuilder("Watched activity | ").append(settings.serverName).append('\n');
        for (String l : feedLines) {
            b.append(l).append('\n');
        }
        if (feedDropped > 0) {
            b.append("(+").append(feedDropped).append(" more, see /watchtower log)\n");
        }
        feedLines.clear();
        feedDropped = 0;
        sink.feed(b.toString().stripTrailing());
    }

    // ---------------------------------------------------------------- blocks and hazards

    /** A block was broken or placed. Returns a denial only for a locked player. */
    synchronized Verdict block(Identity who, boolean place, String material, String world, int x, int y, int z, long now) {
        if (owner(who)) {
            return Verdict.ALLOW;
        }
        Watchlist.Entry w = watch.find(who);
        if (locked(who, w)) {
            alerts.raise(Severity.YELLOW, "lockdown", nm(who), "tried to " + (place ? "place" : "break") + " a block while frozen",
                    where(world, x, y, z), now);
            return Verdict.deny(settings.lockMessage);
        }
        Stats.Rec rec = stats.rec(who);
        if (place) {
            rec.placed++;
        } else {
            rec.broken++;
        }
        blocksChanged.merge(nm(who), 1, Integer::sum);
        String mat = material == null ? "" : material.toUpperCase(Locale.ROOT);
        String at = where(world, x, y, z);
        if (place && settings.alertCommandBlocks && COMMAND_BLOCKS.contains(mat)) {
            alerts.raise(Severity.RED, "command-block", nm(who), "placed a command block (" + mat.toLowerCase(Locale.ROOT) + ")", at, now);
        }
        if (place && mat.equals("TNT")) {
            int lim = w != null ? settings.tntWatchedLimit : settings.tntLimit;
            if (rate(who, "tnt", lim, 30_000L, now)) {
                alerts.raise(Severity.RED, "hazard", nm(who), "is placing a lot of TNT", at, now);
                escalate(w, who, now);
            }
        }
        int lim = w != null ? settings.massWatchedLimit : settings.massLimit;
        if (rate(who, "blocks", lim, settings.massWindowSeconds * 1000L, now)) {
            alerts.raise(Severity.RED, "mass-blocks", nm(who),
                    "changed " + lim + " blocks in " + settings.massWindowSeconds + " seconds", at, now);
            escalate(w, who, now);
        }
        return locked(who, w) ? Verdict.deny(settings.lockMessage) : Verdict.ALLOW;
    }

    synchronized Verdict bucket(Identity who, String bucketMaterial, String world, int x, int y, int z, long now) {
        if (owner(who)) {
            return Verdict.ALLOW;
        }
        Watchlist.Entry w = watch.find(who);
        if (locked(who, w)) {
            return Verdict.deny(settings.lockMessage);
        }
        if ("LAVA_BUCKET".equalsIgnoreCase(bucketMaterial)) {
            int lim = w != null ? settings.lavaWatchedLimit : settings.lavaLimit;
            if (rate(who, "lava", lim, 60_000L, now)) {
                alerts.raise(Severity.RED, "hazard", nm(who), "is pouring out a lot of lava", where(world, x, y, z), now);
                escalate(w, who, now);
            }
        }
        return locked(who, w) ? Verdict.deny(settings.lockMessage) : Verdict.ALLOW;
    }

    synchronized Verdict ignite(Identity who, String world, int x, int y, int z, long now) {
        if (owner(who)) {
            return Verdict.ALLOW;
        }
        Watchlist.Entry w = watch.find(who);
        if (locked(who, w)) {
            return Verdict.deny(settings.lockMessage);
        }
        int lim = w != null ? settings.fireWatchedLimit : settings.fireLimit;
        if (rate(who, "fire", lim, 30_000L, now)) {
            alerts.raise(Severity.RED, "hazard", nm(who), "is setting a lot of fires", where(world, x, y, z), now);
            escalate(w, who, now);
        }
        return locked(who, w) ? Verdict.deny(settings.lockMessage) : Verdict.ALLOW;
    }

    synchronized void witherBuilt(String world, int x, int y, int z, long now) {
        if (settings.alertWither) {
            alerts.raise(Severity.YELLOW, "hazard", "", "A Wither was built", where(world, x, y, z), now);
        }
    }

    /** Opening or attacking things while frozen. */
    synchronized Verdict frozen(Identity who) {
        if (owner(who)) {
            return Verdict.ALLOW;
        }
        return locked(who, watch.find(who)) ? Verdict.deny(settings.lockMessage) : Verdict.ALLOW;
    }

    synchronized void container(Identity who, String type, String world, int x, int y, int z, long now) {
        if (owner(who)) {
            return;
        }
        Watchlist.Entry w = watch.find(who);
        if (w == null) {
            return;
        }
        stats.rec(who).containers++;
        String line = "opened a " + (type == null ? "container" : type.toLowerCase(Locale.ROOT)) + " at " + where(world, x, y, z);
        alerts.raise(Severity.INFO, "container", nm(who), line, "", now);
        feedLine(nm(who) + " " + line, now);
    }

    // ---------------------------------------------------------------- commands and chat

    /** "/minecraft:op x" and "/OP x" both give "op"; any WorldEdit style "//set" gives "//". */
    static String base(String message) {
        if (message == null) {
            return "";
        }
        String m = message.trim();
        if (m.startsWith("/")) {
            m = m.substring(1).stripLeading();
        }
        if (m.startsWith("/")) {
            return "//";
        }
        int sp = 0;
        while (sp < m.length() && !Character.isWhitespace(m.charAt(sp))) {
            sp++;
        }
        String t = m.substring(0, sp).toLowerCase(Locale.ROOT);
        int colon = t.lastIndexOf(':');
        if (colon >= 0) {
            t = t.substring(colon + 1);
        }
        if (t.startsWith("/")) {
            return "//";
        }
        return t;
    }

    private String shown(String message, String base) {
        String m = message.trim();
        if (settings.hideAll.contains(base)) {
            return firstToken(m) + " [hidden]";
        }
        if (settings.hideMessage.contains(base)) {
            String[] parts = m.split("\\s+");
            if (parts.length >= 2) {
                return parts[0] + " " + parts[1] + " [message hidden]";
            }
            return parts[0];
        }
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }

    private static String firstToken(String m) {
        int sp = m.indexOf(' ');
        return sp < 0 ? m : m.substring(0, sp);
    }

    private static boolean massSelector(String message) {
        for (String t : message.trim().split("\\s+")) {
            String l = t.toLowerCase(Locale.ROOT);
            if (l.equals("@e") || l.equals("@a") || l.startsWith("@e[") || l.startsWith("@a[")) {
                return true;
            }
        }
        return false;
    }

    synchronized Verdict command(Identity who, String message, long now) {
        if (owner(who)) {
            return Verdict.ALLOW;
        }
        Watchlist.Entry w = watch.find(who);
        String base = base(message);
        String shown = shown(message, base);
        stats.rec(who).commands++;
        boolean loginCommand = settings.hideAll.contains(base);
        if (locked(who, w) && !(loginCommand && !(w != null && w.locked))) {
            alerts.raise(Severity.YELLOW, "lockdown", nm(who), "tried a command while frozen", shown, now);
            return Verdict.deny(settings.lockMessage);
        }
        if (w != null) {
            alerts.raise(Severity.INFO, "command", nm(who), shown, "", now);
            feedLine(nm(who) + ": " + shown, now);
        }
        boolean red = settings.redCommands.contains(base);
        if (w != null && w.mode == Watchlist.Mode.GUARD && settings.guardBlock.contains(base)) {
            alerts.raise(red ? Severity.RED : Severity.YELLOW, "guard", nm(who), "was stopped from using /" + (base.equals("//") ? "/..." : base), shown, now);
            return Verdict.deny(settings.guardDenyMessage);
        }
        if (red) {
            alerts.raise(Severity.RED, "command", nm(who), "ran /" + base, shown, now);
        } else if (settings.yellowCommands.contains(base)) {
            alerts.raise(Severity.YELLOW, "command", nm(who), "ran /" + base, shown, now);
        } else if (settings.selectorCommands.contains(base) && massSelector(message)) {
            alerts.raise(Severity.YELLOW, "command", nm(who), "used a mass selector with /" + base, shown, now);
        }
        return Verdict.ALLOW;
    }

    synchronized void chat(Identity who, String message, long now) {
        if (owner(who) || settings.phrases.isEmpty() || message == null) {
            return;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String p : settings.phrases) {
            if (lower.contains(p)) {
                String line = message.length() > 200 ? message.substring(0, 200) + "..." : message;
                alerts.raise(Severity.YELLOW, "chat", nm(who), "said a watched phrase (\"" + p + "\")", line, now);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- joins and leaves

    synchronized void joined(Identity who, boolean op, long now) {
        if (owner(who)) {
            return;
        }
        stats.joined(who, now);
        Watchlist.Entry w = watch.find(who);
        if (w == null) {
            return;
        }
        watch.bind(who);
        onlineWatched.add(who.uuid());
        onlineWatchedNames.put(who.uuid(), nm(who));
        String tag = "(watched, " + w.mode.name().toLowerCase(Locale.ROOT) + ")" + (op ? " [operator]" : "") + (w.locked ? " [FROZEN]" : "");
        alerts.raise(settings.notifyJoins ? Severity.YELLOW : Severity.INFO, "watch", nm(who), "joined " + tag, "", now);
        feedLine(nm(who) + " joined", now);
        if (onlineWatched.size() >= settings.coordinationThreshold
                && (lastCoordination < 0 || now - lastCoordination >= 3_600_000L)) {
            lastCoordination = now;
            alerts.raise(Severity.YELLOW, "coordination", "", onlineWatched.size() + " watched accounts are online together",
                    String.join(", ", new TreeSet<>(onlineWatchedNames.values())), now);
        }
    }

    synchronized void left(Identity who, long now) {
        String prefix = who.uuid() + ":";
        rates.keySet().removeIf(k -> k.startsWith(prefix));
        if (owner(who)) {
            return;
        }
        stats.left(who, now);
        if (onlineWatched.remove(who.uuid())) {
            onlineWatchedNames.remove(who.uuid());
            alerts.raise(Severity.INFO, "watch", nm(who), "left", "", now);
            feedLine(nm(who) + " left", now);
        }
    }

    // ---------------------------------------------------------------- operators, plugins, files

    /** Called on a timer with the server's current operators. */
    synchronized void opsSync(Map<UUID, String> current, long now) {
        if (prevOps == null) {
            prevOps = new HashMap<>(current);
            if (settings.expectedOps.isEmpty()) {
                alerts.raise(Severity.INFO, "ops", "", "Operators at startup: " + String.join(", ", new TreeSet<>(current.values())), "", now);
                return;
            }
            for (String name : new TreeSet<>(current.values())) {
                if (!settings.expectedOps.contains(name.toLowerCase(Locale.ROOT)) && !settings.owners.contains(name.toLowerCase(Locale.ROOT))) {
                    alerts.raise(Severity.RED, "ops", name, "is an operator but is not on your expected list", "", now);
                }
            }
            return;
        }
        for (Map.Entry<UUID, String> e : current.entrySet()) {
            if (!prevOps.containsKey(e.getKey())) {
                String name = e.getValue();
                String low = name.toLowerCase(Locale.ROOT);
                if (settings.owners.contains(low)) {
                    alerts.raise(Severity.INFO, "ops", name, "was given operator (owner)", "", now);
                } else if (!settings.expectedOps.isEmpty() && settings.expectedOps.contains(low)) {
                    alerts.raise(Severity.YELLOW, "ops", name, "was given operator (on your expected list)", "", now);
                } else {
                    alerts.raise(Severity.RED, "ops", name, "was just given operator", "If this was not you, deop them and check who ran /op.", now);
                }
            }
        }
        for (Map.Entry<UUID, String> e : prevOps.entrySet()) {
            if (!current.containsKey(e.getKey())) {
                alerts.raise(Severity.YELLOW, "ops", e.getValue(), "lost operator", "", now);
            }
        }
        prevOps = new HashMap<>(current);
    }

    synchronized void pluginDisabled(String name, boolean serverStopping, long now) {
        if (!ready || serverStopping) {
            return;
        }
        boolean critical = settings.criticalPlugins.contains(name.toLowerCase(Locale.ROOT));
        alerts.raise(critical ? Severity.RED : Severity.YELLOW, "plugin", "", name + " was switched off while the server was running",
                critical ? "This is one of your protection plugins." : "", now);
    }

    synchronized void pluginEnabled(String name, long now) {
        if (!ready) {
            return;
        }
        alerts.raise(Severity.YELLOW, "plugin", "", name + " was switched on while the server was running", "", now);
    }

    synchronized void tamperResult(Baseline.Diff diff, Map<String, String> current, boolean baselineCreated, long now) {
        String when = hms(now);
        if (baselineCreated) {
            tamperStatus = "baseline created with " + current.size() + " files at " + when;
            alerts.raise(Severity.INFO, "files", "", "Fingerprinted " + current.size() + " files as the approved baseline", "", now);
            return;
        }
        if (diff.clean()) {
            tamperStatus = "clean, " + current.size() + " files checked at " + when;
            return;
        }
        tamperStatus = diff.total() + " difference(s) found at " + when;
        List<String> fresh = new ArrayList<>();
        for (String p : diff.changed()) {
            if (reportedTamper.add("changed:" + p + ":" + current.get(p))) {
                fresh.add("changed " + p);
            }
        }
        for (String p : diff.added()) {
            if (reportedTamper.add("added:" + p + ":" + current.get(p))) {
                fresh.add("new " + p);
            }
        }
        for (String p : diff.removed()) {
            if (reportedTamper.add("removed:" + p)) {
                fresh.add("missing " + p);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < fresh.size() && i < 8; i++) {
            d.append(i > 0 ? "; " : "").append(fresh.get(i));
        }
        if (fresh.size() > 8) {
            d.append("; and ").append(fresh.size() - 8).append(" more");
        }
        d.append(". If this was you, run /watchtower baseline to approve it.");
        alerts.raise(Severity.RED, "files", "", fresh.size() + " server file(s) differ from your approved baseline", d.toString(), now);
    }

    synchronized String tamperStatus() {
        return tamperStatus;
    }

    // ---------------------------------------------------------------- owner actions

    /** Returns an error message, or null when the entry was added or updated. */
    synchronized String watchAdd(String name, UUID uuid, Watchlist.Mode mode, String note, long now) {
        if (settings.owners.contains(name.toLowerCase(Locale.ROOT))
                || (uuid != null && settings.owners.contains(uuid.toString().toLowerCase(Locale.ROOT)))) {
            return "You are an owner, so you can't put yourself on the watchlist.";
        }
        Watchlist.Entry e = watch.add(name, uuid, mode, note, now);
        alerts.raise(Severity.INFO, "watch", e.name, "added to the watchlist in " + mode.name().toLowerCase(Locale.ROOT) + " mode", "", now);
        return null;
    }

    synchronized boolean watchRemove(String name, long now) {
        Watchlist.Entry e = watch.findByName(name);
        if (e == null) {
            return false;
        }
        String shownName = e.name;
        watch.remove(name);
        alerts.raise(Severity.INFO, "watch", shownName, "removed from the watchlist", "", now);
        return true;
    }

    synchronized boolean setMode(String name, Watchlist.Mode mode, long now) {
        Watchlist.Entry e = watch.findByName(name);
        if (e == null) {
            return false;
        }
        watch.setMode(e, mode);
        alerts.raise(Severity.INFO, "watch", e.name, "is now in " + mode.name().toLowerCase(Locale.ROOT) + " mode", "", now);
        return true;
    }

    synchronized boolean lock(String name, long now) {
        Watchlist.Entry e = watch.findByName(name);
        if (e == null) {
            return false;
        }
        watch.setLocked(e, true);
        alerts.raise(Severity.INFO, "lockdown", e.name, "was frozen by the owner", "", now);
        return true;
    }

    synchronized boolean unlock(String name, long now) {
        Watchlist.Entry e = watch.findByName(name);
        if (e == null) {
            return false;
        }
        watch.setLocked(e, false);
        alerts.raise(Severity.INFO, "lockdown", e.name, "was released by the owner", "", now);
        return true;
    }

    /** Server-wide lockdown: every non-owner is frozen (log-in commands still work). Returns false if nothing changed. */
    synchronized boolean setLockdown(boolean on, long now) {
        if (watch.lockdown() == on) {
            return false;
        }
        watch.setLockdown(on);
        alerts.raise(Severity.INFO, "lockdown", "", on ? "SERVER LOCKDOWN ON: everyone except the owners is frozen"
                : "Server lockdown lifted", "", now);
        return true;
    }

    synchronized boolean lockdown() {
        return watch.lockdown();
    }

    /** Where the app channel's state is reported from (set by the plugin). */
    volatile java.util.function.Supplier<String> appInfo = () -> "off";

    /** One line of JSON describing the current state, for the app's panel. */
    synchronized String snapshot(Map<UUID, String> opsNow, long now) {
        Set<String> onlineClean = new HashSet<>();
        for (String n : onlineWatchedNames.values()) {
            onlineClean.add(Identity.clean(n));
        }
        StringBuilder b = new StringBuilder("{\"server\":").append(Webhook.jsonString(settings.serverName))
                .append(",\"time\":").append(now)
                .append(",\"armed\":").append(ready)
                .append(",\"lockdown\":").append(watch.lockdown())
                .append(",\"watched\":[");
        boolean first = true;
        for (Watchlist.Entry e : watch.all()) {
            b.append(first ? "" : ",").append("{\"name\":").append(Webhook.jsonString(e.name))
                    .append(",\"mode\":").append(Webhook.jsonString(e.mode.name().toLowerCase(Locale.ROOT)))
                    .append(",\"frozen\":").append(e.locked)
                    .append(",\"online\":").append(onlineClean.contains(Identity.clean(e.name))).append('}');
            first = false;
        }
        b.append("],\"operators\":[");
        first = true;
        List<String> unexpected = new ArrayList<>();
        List<Map.Entry<UUID, String>> sortedOps = new ArrayList<>(opsNow.entrySet());
        sortedOps.sort(Map.Entry.<UUID, String>comparingByValue(String.CASE_INSENSITIVE_ORDER).thenComparing(Map.Entry::getKey));
        for (Map.Entry<UUID, String> op : sortedOps) {
            String name = op.getValue();
            b.append(first ? "" : ",").append(Webhook.jsonString(name));
            first = false;
            if (!owner(new Identity(op.getKey(), name)) && !settings.expectedOps.contains(name.toLowerCase(Locale.ROOT))
                    && !settings.expectedOps.contains(op.getKey().toString().toLowerCase(Locale.ROOT))) {
                unexpected.add(name);
            }
        }
        b.append("],\"unexpectedOperators\":[");
        first = true;
        for (String u : unexpected) {
            b.append(first ? "" : ",").append(Webhook.jsonString(u));
            first = false;
        }
        b.append("],\"heldBack\":").append(alerts.heldBack())
                .append(",\"files\":").append(Webhook.jsonString(tamperStatus))
                .append(",\"app\":").append(Webhook.jsonString(appInfo.get())).append('}');
        return b.toString();
    }

    /** A non-owner tried to use the owner command. Looks like an unknown command to them, loud to the owner. */
    synchronized void probe(Identity who, String message, long now) {
        alerts.raise(Severity.YELLOW, "probe", nm(who), "tried to use the owner-only /watchtower command", shown(message, base(message)), now);
    }

    // ---------------------------------------------------------------- reports

    private static String duration(long ms) {
        long m = ms / 60_000L;
        return (m / 60) + "h" + String.format("%02d", m % 60) + "m";
    }

    /** The periodic summary. Resets the counters it reports. */
    synchronized String digest(Map<UUID, String> opsNow, long now) {
        Alerts.Period p = alerts.takePeriod();
        List<Stats.Rec> recs = stats.takeAndReset(now);
        Map<String, Stats.Rec> byName = new HashMap<>();
        for (Stats.Rec r : recs) {
            if (r.name != null) {
                byName.put(Identity.clean(r.name), r);
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("NexusWatchtower digest | ").append(settings.serverName).append(" | ").append(hms(now)).append('\n');
        b.append("Alerts: ").append(p.red()).append(" red, ").append(p.yellow()).append(" yellow, ").append(p.info())
                .append(" logged only, ").append(p.held()).append(" held back\n");
        if (!p.byCategory().isEmpty()) {
            b.append("Where: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : p.byCategory().entrySet()) {
                b.append(first ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
                first = false;
            }
            b.append('\n');
        }
        List<Watchlist.Entry> list = watch.all();
        if (list.isEmpty()) {
            b.append("Watchlist: empty\n");
        } else {
            b.append("Watchlist:\n");
            int shown = 0;
            for (Watchlist.Entry e : list) {
                if (shown++ >= 15) {
                    b.append("- and ").append(list.size() - 15).append(" more\n");
                    break;
                }
                Stats.Rec r = byName.get(Identity.clean(e.name));
                b.append("- ").append(e.name).append(" [").append(e.mode.name().toLowerCase(Locale.ROOT)).append(e.locked ? ", FROZEN" : "").append("] ");
                if (r == null) {
                    b.append("quiet\n");
                } else {
                    b.append("online ").append(duration(r.onlineMs)).append(", ").append(r.commands).append(" commands, ")
                            .append(r.broken).append(" broken, ").append(r.placed).append(" placed, ").append(r.containers).append(" containers\n");
                }
            }
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(blocksChanged.entrySet());
        top.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        if (!top.isEmpty()) {
            b.append("Most blocks changed:");
            for (int i = 0; i < top.size() && i < 5; i++) {
                b.append(i == 0 ? " " : ", ").append(top.get(i).getKey()).append(" (").append(top.get(i).getValue()).append(')');
            }
            b.append('\n');
        }
        blocksChanged.clear();
        stats.forgetOffline();
        b.append("Operators now: ").append(opsNow.isEmpty() ? "none" : String.join(", ", new TreeSet<>(opsNow.values()))).append('\n');
        b.append("Files: ").append(tamperStatus);
        return b.toString();
    }

    /** A few lines for /watchtower status. */
    synchronized List<String> status(Map<UUID, String> opsNow, long now) {
        List<String> out = new ArrayList<>();
        int locked = 0;
        for (Watchlist.Entry e : watch.all()) {
            if (e.locked) {
                locked++;
            }
        }
        out.add("Watching " + watch.size() + " player(s), " + locked + " frozen, " + onlineWatched.size() + " online now.");
        out.add("Operators: " + (opsNow.isEmpty() ? "none" : String.join(", ", new TreeSet<>(opsNow.values()))));
        out.add("Files: " + tamperStatus);
        out.add("Alerts held back since the last message: " + alerts.heldBack());
        out.add("Journal file: " + (journal.writeFailed() ? "WRITE FAILED, check disk space" : "ok"));
        out.add("App: " + appInfo.get() + ", Discord: " + (settings.discordWebhook.isEmpty() ? "off" : "on")
                + ", " + (ready ? "armed" : "starting up") + (watch.lockdown() ? ", SERVER LOCKDOWN ON" : ""));
        return out;
    }
}
