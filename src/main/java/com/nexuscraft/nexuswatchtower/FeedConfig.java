package com.nexuscraft.nexuswatchtower;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The player-action feed's settings, checked and fixed up once, then never changed (reload makes a new one). */
final class FeedConfig {
    static final List<String> TYPES = List.of("chat", "player_join", "player_quit", "player_death", "mob_kill",
            "player_kill", "block_break", "block_place", "command", "advancement", "damage", "item_pickup");
    static final List<String> DEFAULT_OFF = List.of("damage", "item_pickup");
    /** Password commands that are always hidden, whatever commands.hide-everything says. */
    static final List<String> ALWAYS_HIDE = List.of("login", "l", "register", "reg", "changepassword", "changepass",
            "password", "passwd", "setpassword", "nexusgate", "gaterotate", "grot", "auth", "authme");
    /** Private messages whose text is hidden but whose reply has no person in it. */
    static final Set<String> NO_TARGET = Set.of("r", "reply");

    final String baseUrl;
    final String secret;
    final String serverId;
    final int batchSize;
    final int flushSeconds;
    final boolean debug;
    final Map<String, Boolean> events;
    /** Command name to the number of words after it that stay visible. */
    final Map<String, Integer> hideCommands;
    /** Why the address is unusable, or null when fine. */
    final String urlProblem;
    final String endpoint;

    FeedConfig(String baseUrl, String secret, String serverId, int batchSize, int flushSeconds, boolean debug,
               Map<String, Boolean> events, Map<String, Integer> hideCommands) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.baseUrl = b;
        this.secret = secret == null ? "" : secret.trim();
        String sid = serverId == null ? "" : FeedClean.text(serverId.trim(), 64);
        this.serverId = sid.isEmpty() ? "main" : sid;
        this.batchSize = Math.max(1, Math.min(50, batchSize));
        this.flushSeconds = Math.max(1, Math.min(60, flushSeconds));
        this.debug = debug;
        this.events = events;
        this.hideCommands = hideCommands;
        this.urlProblem = check(b);
        this.endpoint = b + "/functions/ingestGameEvent";
    }

    /** The secret is only ever sent over https (plain http is allowed for this computer only, for testing). */
    private static String check(String b) {
        if (b.isEmpty()) {
            return "app.base-url is missing or does not start with https://";
        }
        try {
            URI u = URI.create(b);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost();
            if (host == null) {
                return "app.base-url has no host name";
            }
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            if (scheme.equals("https") || (scheme.equals("http") && local)) {
                return null;
            }
            return "app.base-url must start with https:// (the secret is never sent over plain http)";
        } catch (IllegalArgumentException e) {
            return "app.base-url is not a valid address";
        }
    }

    boolean enabled(String type) {
        return events.getOrDefault(type, Boolean.FALSE);
    }

    boolean hasSecret() {
        return !secret.isEmpty();
    }

    /** Address, secret and server id are the app settings Watchtower already uses; the rest is the feed: section. */
    static FeedConfig from(Settings s) {
        Map<String, Boolean> ev = new LinkedHashMap<>();
        for (String t : TYPES) {
            ev.put(t, s.feedEvents.getOrDefault(t, !DEFAULT_OFF.contains(t)));
        }
        Map<String, Integer> hide = new LinkedHashMap<>();
        for (String h : s.hideMessage) {
            hide.put(h.toLowerCase(Locale.ROOT), NO_TARGET.contains(h.toLowerCase(Locale.ROOT)) ? 0 : 1);
        }
        for (String h : s.hideAll) {
            hide.put(h.toLowerCase(Locale.ROOT), 0);
        }
        for (String h : ALWAYS_HIDE) {
            hide.put(h, 0);
        }
        return new FeedConfig(s.appBase, s.appSecret, s.appServerId, s.feedBatch, s.feedFlushSeconds, s.feedDebug, ev, hide);
    }
}
