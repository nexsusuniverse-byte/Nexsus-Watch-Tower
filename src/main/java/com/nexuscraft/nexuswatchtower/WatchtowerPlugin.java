package com.nexuscraft.nexuswatchtower;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NexusWatchtower -- the owner's private early-warning system. It watches what people DO on the server
 * (mass destruction, fire and TNT, command blocks, operator and permission changes, switched-off or
 * swapped-out plugins, chosen phrases in public chat), keeps a private journal, and sends alerts to a
 * private Discord channel and optionally the owner's own app. One watched operator can be put in guard
 * mode: they keep operator, but their commands are logged, dangerous ones are blocked, and a big
 * tripwire freezes them until the owner releases them.
 */
public final class WatchtowerPlugin extends JavaPlugin implements Sink {

    private final Settings settings = new Settings();
    private WatchService service;
    private Journal journal;
    private Baseline baseline;
    private final Map<String, Baseline.Seen> hashCache = Baseline.newCache();
    private volatile boolean closing;
    private boolean disableReported;
    private int scans;
    private AppClient app;
    private Feed feed;
    private java.util.concurrent.ScheduledExecutorService feedWorker;
    private java.util.concurrent.ScheduledFuture<?> feedJob;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings(getConfig(), settings);
        File dir = getDataFolder();
        long now = System.currentTimeMillis();

        Watchlist watch = new Watchlist(new File(dir, "watchlist.tsv"));
        for (String w : watch.warnings()) {
            getLogger().warning(w);
        }
        journal = new Journal(dir, settings.zone, settings.journalMemory);
        int pruned = journal.prune(settings.journalKeepDays, now);
        baseline = new Baseline(new File(dir, "baseline.tsv"));
        service = new WatchService(settings, watch, journal, this);
        if (settings.appEnabled()) {
            app = new AppClient(settings.appBase, settings.appSecret, settings.appServerId,
                    "NexusWatchtower v" + getDescription().getVersion(), settings.appPerMinute, getLogger());
            app.start();
        }
        service.appInfo = () -> app == null ? "OFF (set app.secret in config.yml)" : app.status();
        startFeed();

        getServer().getPluginManager().registerEvents(new WatchListener(this, service), this);
        WatchCommand executor = new WatchCommand(this, service);
        var cmd = getCommand("watchtower");
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        var sched = Bukkit.getScheduler();
        sched.runTaskLater(this, () -> {
            service.markReady();
            service.opsSync(operators(), System.currentTimeMillis());
            if (app != null && settings.appLifecycle) {
                app.offerNotice("Server started", settings.serverName + " is up and Watchtower is armed.", "info", "server", true, null);
            }
        }, 100L);
        sched.runTaskTimer(this, () -> service.opsSync(operators(), System.currentTimeMillis()),
                100L + settings.opCheckSeconds * 20L, settings.opCheckSeconds * 20L);
        sched.runTaskTimer(this, service::flushFeed, settings.feedSeconds * 20L, settings.feedSeconds * 20L);
        if (settings.digestEnabled) {
            long period = settings.digestHours * 72_000L;
            sched.runTaskTimer(this, this::sendDigest, period, period);
        }
        if (settings.tamperEnabled) {
            sched.runTaskTimerAsynchronously(this, () -> tamperScan(false), 200L, settings.tamperMinutes * 1200L);
        }
        service.alerts.raise(Severity.INFO, "server", "", "Watchtower started", "", now);
        getLogger().info("Player-action feed: " + (feed == null ? "OFF (feed.enabled is false)" : "ON, reporting every player, owners and operators included, to the app"
                + (settings.appEnabled() ? "" : " once app.secret is set")) + ".");
        getLogger().info("NexusWatchtower enabled. " + watch.size() + " watched player(s), "
                + settings.owners.size() + " owner(s), "
                + (app == null ? "app alerts OFF (set app.secret)" : "app alerts on") + ", "
                + (settings.discordWebhook.isEmpty() ? "Discord off" : "Discord on")
                + (pruned > 0 ? ", removed " + pruned + " old journal file(s)" : "") + ".");
    }

    // ---------------------------------------------------------------- player-action feed

    private void startFeed() {
        if (!settings.feedEnabled) {
            return;
        }
        FeedConfig cfg = FeedConfig.from(settings);
        feed = new Feed(cfg, new FeedHttp(), new Feed.Log() {
            @Override
            public void info(String s) {
                getLogger().info(s);
            }

            @Override
            public void warn(String s) {
                getLogger().warning(s);
            }
        }, Thread::sleep, System::currentTimeMillis);
        feedWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NexusWatchtower-feed");
            t.setDaemon(true);
            return t;
        });
        scheduleFeed(cfg);
        getServer().getPluginManager().registerEvents(new FeedListener(feed), this);
        if (cfg.urlProblem != null) {
            getLogger().warning("Player-action feed is not sending: " + cfg.urlProblem + ".");
        } else if (!cfg.hasSecret()) {
            getLogger().warning("Player-action feed is waiting: app.secret is empty in config.yml, so nothing is sent yet.");
        }
    }

    private void scheduleFeed(FeedConfig cfg) {
        if (feedJob != null) {
            feedJob.cancel(false);
        }
        feedJob = feedWorker.scheduleWithFixedDelay(this::safeFeedFlush, 2, cfg.flushSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void safeFeedFlush() {
        try {
            feed.flushAll(false);
        } catch (RuntimeException e) {
            getLogger().warning("Player-action feed: sending failed (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** Asks the feed's worker thread to send what is queued right now. */
    void feedFlushSoon() {
        if (feedWorker != null && !feedWorker.isShutdown()) {
            feedWorker.execute(this::safeFeedFlush);
        }
    }

    Feed feed() {
        return feed;
    }

    /** A few readable lines about the feed for /watchtower feed and /watchtower status. */
    List<String> feedLines(long now) {
        List<String> out = new ArrayList<>();
        if (feed == null) {
            out.add("Player-action feed: OFF (feed.enabled is false in config.yml).");
            return out;
        }
        FeedConfig c = feed.config();
        String host;
        try {
            host = java.net.URI.create(c.baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        out.add("Player-action feed: sending to " + (host == null ? "(no valid app.base-url)" : host) + " as \"" + c.serverId + "\"; secret "
                + (c.hasSecret() ? "set" : "NOT SET") + (feed.halted() ? "; STOPPED (the app refused the secret)" : "") + ".");
        out.add("  queue " + feed.queued() + " waiting; accepted " + feed.accepted() + ", sent " + feed.sent() + " (phone pushes accepted "
                + feed.pushed() + "), dropped " + feed.dropped() + ".");
        String ago = feed.lastOkAt() == 0 ? "never" : ((now - feed.lastOkAt()) / 1000) + " s ago";
        out.add("  last HTTP code " + (feed.lastHttp() == 0 ? "none yet" : String.valueOf(feed.lastHttp())) + ", last success " + ago
                + ", last error: " + (feed.lastError().isEmpty() ? "none" : feed.lastError()) + ".");
        List<String> on = new ArrayList<>();
        for (String t : FeedConfig.TYPES) {
            if (c.enabled(t)) {
                on.add(t);
            }
        }
        out.add("  every " + c.flushSeconds + " s, up to " + c.batchSize + " per request. On: " + String.join(", ", on) + ".");
        return out;
    }

    @Override
    public void onDisable() {
        closing = true;
        if (feedWorker != null) {
            try {
                // one quick last send, done by the feed's own thread so the game thread never does network work
                feedWorker.submit(() -> feed.flushAll(true)).get(6, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                getLogger().warning("The last feed events could not be sent before shutdown.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                feedWorker.shutdownNow();
            }
        }
        if (service != null && !disableReported && !Bukkit.isStopping()) {
            disableReported = true;
            service.pluginDisabled(getName(), false, System.currentTimeMillis());
        }
        if (service != null) {
            service.flushFeed();
        }
        if (app != null) {
            if (Bukkit.isStopping() && settings.appLifecycle) {
                app.offerNotice("Server stopping", settings.serverName + " is shutting down.", "info", "server", true, null);
            }
            app.shutdown(4000L);
        }
        if (journal != null) {
            journal.close();
        }
    }

    /** Called by the listener when this plugin is being switched off mid-run, so the alert is sent before we go quiet. */
    void closingNow() {
        closing = true;
        disableReported = true;
    }

    void reload() {
        reloadConfig();
        loadSettings(getConfig(), settings);
        if (feed != null) {
            FeedConfig cfg = FeedConfig.from(settings);
            feed.reconfigure(cfg);
            scheduleFeed(cfg);
        }
    }

    // ---------------------------------------------------------------- settings

    static void loadSettings(FileConfiguration c, Settings s) {
        s.serverName = c.getString("server-name", s.serverName);
        s.timezone = c.getString("timezone", s.timezone);
        s.owners = new java.util.LinkedHashSet<>(list(c, "owners", s.owners));
        s.discordWebhook = c.getString("alerts.discord-webhook", "");
        s.feedWebhook = c.getString("alerts.feed-webhook", "");
        s.pingUserId = c.getString("alerts.ping-discord-user-id", "");
        s.appBase = c.getString("app.base-url", s.appBase);
        s.appSecret = c.getString("app.secret", "");
        s.appServerId = c.getString("app.server-id", s.appServerId);
        s.appPushYellow = c.getBoolean("app.push-yellow", s.appPushYellow);
        s.appLifecycle = c.getBoolean("app.notify-server-start-stop", s.appLifecycle);
        s.appPerMinute = c.getInt("app.max-per-minute", s.appPerMinute);
        s.trustedSenders = new java.util.LinkedHashSet<>(list(c, "app.trusted-sender-classes", s.trustedSenders));
        s.dedupeSeconds = c.getInt("alerts.repeat-quiet-seconds", s.dedupeSeconds);
        s.maxYellowPerMinute = c.getInt("alerts.max-yellow-per-minute", s.maxYellowPerMinute);

        s.massWindowSeconds = c.getInt("tripwires.mass-blocks.window-seconds", s.massWindowSeconds);
        s.massLimit = c.getInt("tripwires.mass-blocks.limit", s.massLimit);
        s.massWatchedLimit = c.getInt("tripwires.mass-blocks.limit-watched", s.massWatchedLimit);
        s.tntLimit = c.getInt("tripwires.tnt.limit", s.tntLimit);
        s.tntWatchedLimit = c.getInt("tripwires.tnt.limit-watched", s.tntWatchedLimit);
        s.lavaLimit = c.getInt("tripwires.lava.limit", s.lavaLimit);
        s.lavaWatchedLimit = c.getInt("tripwires.lava.limit-watched", s.lavaWatchedLimit);
        s.fireLimit = c.getInt("tripwires.fire.limit", s.fireLimit);
        s.fireWatchedLimit = c.getInt("tripwires.fire.limit-watched", s.fireWatchedLimit);
        s.alertCommandBlocks = c.getBoolean("tripwires.command-blocks", s.alertCommandBlocks);
        s.alertWither = c.getBoolean("tripwires.wither", s.alertWither);

        s.redCommands = new java.util.LinkedHashSet<>(list(c, "commands.red", s.redCommands));
        s.yellowCommands = new java.util.LinkedHashSet<>(list(c, "commands.yellow", s.yellowCommands));
        s.selectorCommands = new java.util.LinkedHashSet<>(list(c, "commands.mass-selector", s.selectorCommands));
        s.guardBlock = new java.util.LinkedHashSet<>(list(c, "guard.blocked-commands", s.guardBlock));
        s.hideMessage = new java.util.LinkedHashSet<>(list(c, "commands.hide-message-text", s.hideMessage));
        s.hideAll = new java.util.LinkedHashSet<>(list(c, "commands.hide-everything", s.hideAll));
        s.guardDenyMessage = c.getString("guard.denied-message", s.guardDenyMessage);
        s.autoLockdown = c.getBoolean("guard.freeze-on-tripwire", s.autoLockdown);
        s.lockMessage = c.getString("guard.frozen-message", s.lockMessage);

        s.notifyJoins = c.getBoolean("watch.alert-on-join", s.notifyJoins);
        s.streamCommands = c.getBoolean("watch.stream-activity", s.streamCommands);
        s.coordinationThreshold = c.getInt("watch.together-alert-at", s.coordinationThreshold);
        s.feedSeconds = c.getInt("watch.feed-seconds", s.feedSeconds);
        s.feedMaxLines = c.getInt("watch.feed-max-lines", s.feedMaxLines);

        s.phrases = new ArrayList<>(list(c, "chat.phrases", s.phrases));
        s.expectedOps = new java.util.LinkedHashSet<>(list(c, "operators.expected", s.expectedOps));
        s.opCheckSeconds = c.getInt("operators.check-seconds", s.opCheckSeconds);
        s.criticalPlugins = new java.util.LinkedHashSet<>(list(c, "plugins.protection", s.criticalPlugins));

        s.tamperEnabled = c.getBoolean("files.check", s.tamperEnabled);
        s.tamperMinutes = c.getInt("files.check-every-minutes", s.tamperMinutes);
        s.tamperFiles = new ArrayList<>(list(c, "files.also-watch", s.tamperFiles));

        s.feedEnabled = c.getBoolean("feed.enabled", s.feedEnabled);
        s.feedBatch = c.getInt("feed.batch-size", s.feedBatch);
        s.feedFlushSeconds = c.getInt("feed.flush-seconds", s.feedFlushSeconds);
        s.feedDebug = c.getBoolean("feed.debug", s.feedDebug);
        s.feedEvents = new LinkedHashMap<>();
        for (String t : FeedConfig.TYPES) {
            if (c.contains("feed.events." + t)) {
                s.feedEvents.put(t, c.getBoolean("feed.events." + t, !FeedConfig.DEFAULT_OFF.contains(t)));
            }
        }

        s.digestEnabled = c.getBoolean("digest.enabled", s.digestEnabled);
        s.digestHours = c.getInt("digest.every-hours", s.digestHours);
        s.journalKeepDays = c.getInt("journal.keep-days", s.journalKeepDays);
        s.journalMemory = c.getInt("journal.lines-in-memory", s.journalMemory);
        s.clamp();
    }

    private static java.util.Collection<String> list(FileConfiguration c, String path, java.util.Collection<String> current) {
        return c.contains(path) ? c.getStringList(path) : current;
    }

    // ---------------------------------------------------------------- operators, digest, files

    Map<UUID, String> operators() {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (OfflinePlayer op : Bukkit.getOperators()) {
            out.put(op.getUniqueId(), op.getName() == null ? op.getUniqueId().toString() : op.getName());
        }
        return out;
    }

    void sendDigest() {
        String text = service.digest(operators(), System.currentTimeMillis());
        postRaw(text, false);
    }

    /**
     * Fingerprints the plugins folder and key files. With {@code approve} the result becomes the new
     * trusted baseline; otherwise it is compared with the saved one (and saved if there is none yet).
     * Returns a short result for the command. Runs off the main thread.
     */
    synchronized String tamperScan(boolean approve) {
        File root = getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
        long now = System.currentTimeMillis();
        try {
            if (approve || scans++ % 6 == 0) {
                hashCache.clear();
            }
            Map<String, String> current = Baseline.hashAll(root, Baseline.collect(root, settings.tamperFiles), hashCache);
            if (approve || !baseline.existed()) {
                boolean first = !baseline.existed();
                baseline.save(current);
                service.tamperResult(new Baseline.Diff(List.of(), List.of(), List.of()), current, true, now);
                return (first ? "Baseline created" : "Baseline approved") + " with " + current.size() + " files.";
            }
            Baseline.Diff diff = baseline.compare(current);
            service.tamperResult(diff, current, false, now);
            return diff.clean() ? "All " + current.size() + " files match the approved baseline."
                    : diff.total() + " file(s) differ from the baseline (changed " + diff.changed().size() + ", new "
                    + diff.added().size() + ", missing " + diff.removed().size() + ").";
        } catch (RuntimeException e) {
            getLogger().warning("File check failed: " + e.getClass().getSimpleName());
            return "The file check failed; see the console.";
        }
    }

    /** Runs the file check off the main thread and tells {@code who} the result. */
    void tamperScanAsync(boolean approve, CommandSender who) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String result = tamperScan(approve);
            Bukkit.getScheduler().runTask(this, () -> who.sendMessage(who instanceof Player ? ChatColor.GRAY + result : result));
        });
    }

    // ---------------------------------------------------------------- alert delivery

    @Override
    public void send(Alert alert, String text, boolean ping) {
        String plain = text.replace('\n', ' ');
        getLogger().info("ALERT " + plain);
        Runnable inGame = () -> {
            ChatColor color = alert.severity() == Severity.RED ? ChatColor.RED : ChatColor.YELLOW;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (service.owner(new Identity(p.getUniqueId(), p.getName()))) {
                    p.sendMessage(color + "[Watchtower] " + ChatColor.WHITE + alert.who() + (alert.who().isEmpty() ? "" : " ")
                            + alert.title() + (alert.detail().isEmpty() ? "" : ChatColor.GRAY + " (" + alert.detail() + ")"));
                }
            }
        };
        if (!closing) {
            if (Bukkit.isPrimaryThread()) {
                inGame.run();
            } else {
                Bukkit.getScheduler().runTask(this, inGame);
            }
        }
        if (app != null && alert.severity() != Severity.INFO) {
            app.offerAlert(alert, settings.appPushYellow);
        }
        String discord = settings.discordWebhook;
        String body = discord.isEmpty() ? "" : Webhook.discordPayload(text, ping, settings.pingUserId);
        Runnable out = () -> Webhook.post(discord, body, null, closing ? 5 : 10, getLogger());
        if (body.isEmpty()) {
            return;
        }
        if (closing) {
            out.run();
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, out);
        }
    }

    @Override
    public void feed(String text) {
        String url = settings.feedWebhook.isEmpty() ? settings.discordWebhook : settings.feedWebhook;
        if (app != null) {
            app.offerFeed(text);
        }
        if (url.isEmpty()) {
            if (app == null) {
                for (String line : text.split("\n")) {
                    getLogger().info("WATCH " + line);
                }
            }
            return;
        }
        postTo(url, text, false);
    }

    private void postRaw(String text, boolean ping) {
        if (app != null) {
            app.offerNotice("Watchtower digest", text, "info", "system", false, null);
        }
        if (settings.discordWebhook.isEmpty()) {
            if (app == null) {
                for (String line : text.split("\n")) {
                    getLogger().info("DIGEST " + line);
                }
            }
            return;
        }
        postTo(settings.discordWebhook, text, ping);
    }

    private void postTo(String url, String text, boolean ping) {
        String body = Webhook.discordPayload(text, ping, settings.pingUserId);
        Runnable out = () -> Webhook.post(url, body, null, closing ? 5 : 10, getLogger());
        if (closing) {
            out.run();
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, out);
        }
    }

    /** Sends the digest text to the owner who asked for it, and to the channel. */
    void digestNow(CommandSender who) {
        String text = service.digest(operators(), System.currentTimeMillis());
        for (String line : text.split("\n")) {
            who.sendMessage(ChatColor.GRAY + line);
        }
        postRaw(text, false);
    }

    List<String> statusLines() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>(service.status(operators(), now));
        out.addAll(feedLines(now));
        return out;
    }

    Settings settings() {
        return settings;
    }

    AppClient app() {
        return app;
    }

    WatchService service() {
        return service;
    }

    Journal journal() {
        return journal;
    }

}
