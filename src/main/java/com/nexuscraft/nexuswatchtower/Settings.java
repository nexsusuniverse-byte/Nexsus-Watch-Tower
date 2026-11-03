package com.nexuscraft.nexuswatchtower;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Every setting in one place, with safe defaults and clamping so a typo in config.yml cannot break anything. */
final class Settings {
    String serverName = "Nexus Universe";
    String timezone = "UTC";
    ZoneId zone = ZoneId.of("UTC");

    /** Lower-case Minecraft names or UUIDs. Only these people may use /watchtower, and nothing here ever watches them. */
    Set<String> owners = new LinkedHashSet<>(List.of("realsociety5107"));

    String discordWebhook = "";
    /** Optional second channel for the busy activity feed. Empty means use the main channel. */
    String feedWebhook = "";
    String pingUserId = "";
    /** The Kai Ross Nexus app. Alerts only go there once {@link #appSecret} is set. */
    String appBase = "https://kai-core-genesis.base44.app";
    String appSecret = "";
    String appServerId = "main";
    boolean appPushYellow = true;
    boolean appLifecycle = true;
    /** Most app messages sent per minute. The app itself refuses more than 20. */
    int appPerMinute = 18;
    /** Extra command sender class names allowed to run /watchtower (for the app's http-commands bridge, if it is not the console). */
    Set<String> trustedSenders = new LinkedHashSet<>();
    int dedupeSeconds = 60;
    int maxYellowPerMinute = 12;

    int massWindowSeconds = 10;
    int massLimit = 300;
    int massWatchedLimit = 100;
    int tntLimit = 5;
    int tntWatchedLimit = 2;
    int lavaLimit = 4;
    int lavaWatchedLimit = 2;
    int fireLimit = 6;
    int fireWatchedLimit = 3;
    boolean alertCommandBlocks = true;
    boolean alertWither = true;

    Set<String> redCommands = set("op", "deop", "stop", "reload", "rl", "restart", "plugman", "plugin", "luckperms",
            "lp", "pex", "perms", "permissions", "save-off");
    Set<String> yellowCommands = set("gamerule", "whitelist", "ban-ip", "pardon", "pardon-ip", "difficulty",
            "defaultgamemode", "worldborder", "forceload", "data");
    Set<String> selectorCommands = set("kill", "clear", "give", "tp", "teleport", "effect", "gamemode", "xp",
            "experience", "enchant", "item", "replaceitem");
    /** Commands a guarded player may not run. "//" means every WorldEdit style double slash command. */
    Set<String> guardBlock = set("op", "deop", "stop", "reload", "rl", "restart", "plugman", "plugin", "luckperms",
            "lp", "pex", "perms", "permissions", "whitelist", "gamerule", "save-off", "execute", "fill", "setblock",
            "clone", "data", "forceload", "worldborder", "//");
    /** Private message commands: the target is logged, the message text is not. */
    Set<String> hideMessage = set("msg", "tell", "w", "whisper", "m", "t", "pm", "dm", "r", "reply");
    /** Password style commands: nothing after the command is logged. */
    Set<String> hideAll = set("login", "l", "register", "reg", "changepassword", "password");
    String guardDenyMessage = "You do not have permission to use this command.";

    boolean autoLockdown = true;
    String lockMessage = "You can't do that right now.";

    boolean notifyJoins = true;
    boolean streamCommands = true;
    int coordinationThreshold = 3;
    int feedSeconds = 30;
    int feedMaxLines = 15;

    List<String> phrases = new ArrayList<>();

    Set<String> expectedOps = new LinkedHashSet<>();
    int opCheckSeconds = 60;

    Set<String> criticalPlugins = set("nexuswatchtower", "nexusgate", "nexusshield", "nexusgatewatch", "nexusadmin",
            "nexusrealms", "nexusdenyblocks", "nexusserverrules", "nexuslogs", "nexussentinel", "nexusguardian",
            "nexusvault", "nexusspawnshield", "nexusintegrity");

    boolean tamperEnabled = true;
    int tamperMinutes = 30;
    List<String> tamperFiles = new ArrayList<>(List.of("server.properties", "bukkit.yml", "spigot.yml", "commands.yml",
            "permissions.yml", "config/paper-global.yml", "config/paper-world-defaults.yml"));

    boolean digestEnabled = true;
    int digestHours = 24;

    int journalKeepDays = 30;
    int journalMemory = 2000;

    /** True when alerts can be sent to the app: a valid https base address and a secret. */
    boolean appEnabled() {
        return !appBase.isEmpty() && !appSecret.isEmpty();
    }

    static Set<String> set(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    static Set<String> lower(Iterable<String> in, boolean stripSlash) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : in) {
            if (c != null && !c.isBlank()) {
                String t = c.trim().toLowerCase(Locale.ROOT);
                if (stripSlash && t.startsWith("/") && !t.equals("//")) {
                    t = t.substring(1);
                }
                out.add(t);
            }
        }
        return out;
    }

    void clamp() {
        if (serverName == null || serverName.isBlank()) {
            serverName = "this server";
        }
        try {
            zone = ZoneId.of(timezone == null ? "UTC" : timezone.trim());
        } catch (DateTimeException e) {
            timezone = "UTC";
            zone = ZoneId.of("UTC");
        }
        owners = lower(owners, false);
        redCommands = lower(redCommands, true);
        yellowCommands = lower(yellowCommands, true);
        selectorCommands = lower(selectorCommands, true);
        guardBlock = lower(guardBlock, true);
        hideMessage = lower(hideMessage, true);
        hideAll = lower(hideAll, true);
        expectedOps = lower(expectedOps, false);
        criticalPlugins = lower(criticalPlugins, false);
        List<String> ph = new ArrayList<>();
        for (String p : phrases) {
            if (p != null && !p.isBlank()) {
                ph.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        phrases = ph;
        List<String> files = new ArrayList<>();
        for (String f : tamperFiles) {
            if (f != null && !f.isBlank() && !f.contains("..") && !f.startsWith("/") && !f.contains("\\")) {
                files.add(f.trim());
            }
        }
        tamperFiles = files;

        discordWebhook = discordWebhook == null ? "" : discordWebhook.trim();
        if (!Webhook.validDiscord(discordWebhook)) {
            discordWebhook = "";
        }
        feedWebhook = feedWebhook == null ? "" : feedWebhook.trim();
        if (!Webhook.validDiscord(feedWebhook)) {
            feedWebhook = "";
        }
        pingUserId = pingUserId == null ? "" : pingUserId.trim();
        if (!Webhook.validUserId(pingUserId)) {
            pingUserId = "";
        }
        appBase = appBase == null ? "" : appBase.trim();
        while (appBase.endsWith("/")) {
            appBase = appBase.substring(0, appBase.length() - 1);
        }
        if (!Webhook.validApp(appBase)) {
            appBase = "";
        }
        appSecret = appSecret == null ? "" : appSecret.trim();
        if (appServerId == null || !appServerId.matches("[A-Za-z0-9_-]{1,32}")) {
            appServerId = "main";
        }
        appPerMinute = range(appPerMinute, 1, 20);
        Set<String> senders = new LinkedHashSet<>();
        for (String c : trustedSenders) {
            if (c != null && c.trim().matches("[A-Za-z0-9_.$]{1,200}")) {
                senders.add(c.trim());
            }
        }
        trustedSenders = senders;

        dedupeSeconds = range(dedupeSeconds, 0, 3600);
        maxYellowPerMinute = range(maxYellowPerMinute, 1, 300);
        massWindowSeconds = range(massWindowSeconds, 1, 120);
        massLimit = range(massLimit, 5, 100000);
        massWatchedLimit = range(massWatchedLimit, 5, 100000);
        tntLimit = range(tntLimit, 1, 1000);
        tntWatchedLimit = range(tntWatchedLimit, 1, 1000);
        lavaLimit = range(lavaLimit, 1, 1000);
        lavaWatchedLimit = range(lavaWatchedLimit, 1, 1000);
        fireLimit = range(fireLimit, 1, 1000);
        fireWatchedLimit = range(fireWatchedLimit, 1, 1000);
        coordinationThreshold = range(coordinationThreshold, 2, 100);
        feedSeconds = range(feedSeconds, 5, 3600);
        feedMaxLines = range(feedMaxLines, 1, 40);
        opCheckSeconds = range(opCheckSeconds, 5, 3600);
        tamperMinutes = range(tamperMinutes, 1, 1440);
        digestHours = range(digestHours, 1, 168);
        journalKeepDays = range(journalKeepDays, 1, 365);
        journalMemory = range(journalMemory, 100, 20000);
        if (guardDenyMessage == null || guardDenyMessage.isBlank()) {
            guardDenyMessage = "You do not have permission to use this command.";
        }
        if (lockMessage == null || lockMessage.isBlank()) {
            lockMessage = "You can't do that right now.";
        }
    }

    private static int range(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
