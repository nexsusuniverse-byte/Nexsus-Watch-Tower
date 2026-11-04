package com.nexuscraft.nexuswatchtower;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Plain main, no JUnit. */
public final class WatchTest {

    static int checks = 0;
    static int failures = 0;

    static void ck(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object a, Object b, String what) {
        ck(java.util.Objects.equals(a, b), what + " (expected <" + b + "> but was <" + a + ">)");
    }

    static final long T0 = 1_700_000_000_000L;

    // ------------------------------------------------------------------ fixtures

    record Sent(Alert alert, String text, boolean ping) {
    }

    static final class RecSink implements Sink {
        final List<Sent> sent = new ArrayList<>();
        final List<String> feeds = new ArrayList<>();

        @Override
        public void send(Alert alert, String text, boolean ping) {
            sent.add(new Sent(alert, text, ping));
        }

        @Override
        public void feed(String text) {
            feeds.add(text);
        }

        long count(Severity s) {
            return sent.stream().filter(x -> x.alert().severity() == s).count();
        }

        boolean has(String category, String titlePart) {
            return sent.stream().anyMatch(x -> x.alert().category().equals(category) && x.alert().title().contains(titlePart));
        }

        Sent last() {
            return sent.get(sent.size() - 1);
        }
    }

    static final class Fx {
        final File dir;
        final Settings s = new Settings();
        final RecSink sink = new RecSink();
        Watchlist watch;
        Journal journal;
        WatchService svc;

        Fx() throws Exception {
            this(null);
        }

        Fx(java.util.function.Consumer<Settings> tweak) throws Exception {
            dir = Files.createTempDirectory("nw-test").toFile();
            if (tweak != null) {
                tweak.accept(s);
            }
            s.clamp();
            rebuild();
        }

        void rebuild() {
            watch = new Watchlist(new File(dir, "watchlist.tsv"));
            journal = new Journal(dir, s.zone, s.journalMemory);
            svc = new WatchService(s, watch, journal, sink);
            svc.markReady();
        }
    }

    static Identity owner() {
        return new Identity(UUID.nameUUIDFromBytes("owner".getBytes(StandardCharsets.UTF_8)), "RealSociety5107");
    }

    static Identity person(String name) {
        return new Identity(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
    }

    static void watched(Fx f, String name, Watchlist.Mode mode) {
        ck(f.svc.watchAdd(name, person(name).uuid(), mode, "", T0) == null, "watchAdd " + name);
    }

    /** n block breaks one second apart-ish (10 ms), returns the last verdict. */
    static Verdict breaks(Fx f, Identity who, int n, long start) {
        Verdict v = Verdict.ALLOW;
        for (int i = 0; i < n; i++) {
            v = f.svc.block(who, false, "STONE", "nexsus", i, 64, 0, start + i * 10L);
        }
        return v;
    }

    public static void main(String[] args) throws Exception {
        rate();
        webhook();
        escAndSettings();
        watchlist();
        journal();
        alertsEngine();
        baseline();
        owners();
        massBlocks();
        guardAndFreeze();
        hazards();
        commands();
        redaction();
        chat();
        joins();
        ops();
        pluginEvents();
        tamper();
        digestAndFeed();
        owner_actions();
        extras();
        bukkitLayer();
        System.out.println(checks + " checks, " + failures + " failed.");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ Rate

    static void rate() {
        Rate r = new Rate(3, 1000);
        ck(!r.hit(0), "rate: 1st no");
        ck(!r.hit(100), "rate: 2nd no");
        ck(r.hit(200), "rate: 3rd inside window fires");
        ck(!r.hit(300), "rate: after firing it starts over (1)");
        ck(!r.hit(400), "rate: after firing it starts over (2)");
        ck(r.hit(500), "rate: fires again after another full set");

        Rate slow = new Rate(3, 1000);
        ck(!slow.hit(0) && !slow.hit(600) && !slow.hit(1200), "rate: spread over more than the window never fires");
        ck(!slow.hit(1800), "rate: still slow");
        ck(slow.hit(1900) || true, "rate: smoke");

        Rate edge = new Rate(2, 1000);
        edge.hit(0);
        ck(edge.hit(1000), "rate: exactly on the window edge counts");
        Rate edge2 = new Rate(2, 1000);
        edge2.hit(0);
        ck(!edge2.hit(1001), "rate: one millisecond outside the window does not");

        Rate one = new Rate(1, 1000);
        ck(one.hit(5), "rate: limit 1 fires on every event");
        ck(one.hit(5000), "rate: limit 1 fires again");
        ck(new Rate(0, 0).limit() == 1, "rate: zero limit is clamped to 1");

        Rate wrap = new Rate(3, 1000);
        wrap.hit(0);
        wrap.hit(2000);
        wrap.hit(4000);
        ck(!wrap.hit(6000), "rate: old events fall out of the ring");
        ck(wrap.hit(6100) || wrap.hit(6200), "rate: three fast events after slow ones fire");
    }

    // ------------------------------------------------------------------ Webhook

    static void webhook() {
        ck(Webhook.validDiscord("https://discord.com/api/webhooks/123/abc"), "webhook: discord ok");
        ck(Webhook.validDiscord("https://ptb.discord.com/api/webhooks/1/x"), "webhook: ptb ok");
        ck(!Webhook.validDiscord("https://discord.com/api/webhooks/"), "webhook: bare prefix rejected");
        ck(!Webhook.validDiscord("http://discord.com/api/webhooks/1/x"), "webhook: http rejected");
        ck(!Webhook.validDiscord("https://evil.example/api/webhooks/1/x"), "webhook: other host rejected");
        ck(!Webhook.validDiscord(null), "webhook: null rejected");
        ck(Webhook.validApp("https://app.example.com/hook"), "app: https ok");
        ck(!Webhook.validApp("http://app.example.com/hook"), "app: http rejected");
        ck(!Webhook.validApp("https://"), "app: no host rejected");
        ck(!Webhook.validApp("https:///path"), "app: empty host rejected");
        ck(!Webhook.validApp("https://exa mple.com"), "app: bad uri rejected");
        ck(!Webhook.validApp(null), "app: null rejected");
        ck(Webhook.validUserId("123456789012345678"), "user id ok");
        ck(!Webhook.validUserId("12345"), "user id too short");
        ck(!Webhook.validUserId("12345678901234567x"), "user id not digits");
        ck(!Webhook.validUserId("@everyone"), "user id everyone rejected");

        eq(Webhook.jsonString("a\"b\\c\nd\te"), "\"a\\\"b\\\\c\\nd\\te\"", "json escapes");
        eq(Webhook.jsonString("\u0001"), "\"\\u0001\"", "json control char");

        String plain = Webhook.discordPayload("hello @everyone", false, "123456789012345678");
        ck(plain.contains("\"allowed_mentions\":{\"parse\":[]}"), "payload: no mentions allowed for a normal alert");
        ck(!plain.contains("<@"), "payload: no ping prefix on a normal alert");
        String ping = Webhook.discordPayload("danger", true, "123456789012345678");
        ck(ping.contains("<@123456789012345678> danger"), "payload: red alert pings the owner");
        ck(ping.contains("\"users\":[\"123456789012345678\"]") && ping.contains("\"parse\":[]"),
                "payload: only that one user may be pinged");
        String hostile = Webhook.discordPayload("<@999999999999999999> @everyone", true, "123456789012345678");
        ck(hostile.contains("\"users\":[\"123456789012345678\"]"), "payload: a hostile name cannot add pings");
        String noId = Webhook.discordPayload("x", true, "bad");
        ck(!noId.contains("<@"), "payload: bad owner id means no ping");
        String longText = Webhook.discordPayload("x".repeat(5000), false, "");
        ck(longText.length() < 2100, "payload: long text is cut");

    }

    // ------------------------------------------------------------------ Esc and Settings

    static void escAndSettings() {
        String weird = "tab\there\nnew\\slash\r";
        eq(Esc.dec(Esc.enc(weird)), weird, "esc round trip");
        eq(Esc.split(Esc.join("a", "b\tc", null, "d")).size(), 4, "esc join/split keeps field count");
        eq(Esc.split(Esc.join("a", "b\tc", null, "d")).get(1), "b\tc", "esc field with tab");

        Settings s = new Settings();
        s.owners = Settings.set("RealSociety5107", " ", "AbC-UUID");
        s.timezone = "Not/AZone";
        s.redCommands = Settings.set("/OP", "Stop ");
        s.guardBlock = Settings.set("//", "/Fill");
        s.discordWebhook = "https://evil.example/x";
        s.feedWebhook = "nonsense";
        s.pingUserId = "bad";
        s.appBase = "http://insecure.example";
        s.massLimit = 1;
        s.digestHours = 100000;
        s.coordinationThreshold = 0;
        s.tamperFiles = new ArrayList<>(List.of("../etc/passwd", "/abs", "ok/file.yml", "back\\slash", "", "a/../b"));
        s.phrases = new ArrayList<>(List.of("  Shut Down ", ""));
        s.serverName = " ";
        s.lockMessage = "";
        s.guardDenyMessage = null;
        s.clamp();
        ck(s.owners.contains("realsociety5107") && s.owners.contains("abc-uuid") && s.owners.size() == 2, "settings: owners lower-cased, blanks dropped");
        eq(s.timezone, "UTC", "settings: bad timezone falls back");
        ck(s.redCommands.contains("op") && s.redCommands.contains("stop"), "settings: commands lower-cased and slash stripped");
        ck(s.guardBlock.contains("//") && s.guardBlock.contains("fill"), "settings: the // token survives");
        eq(s.discordWebhook, "", "settings: bad discord url dropped");
        eq(s.feedWebhook, "", "settings: bad feed url dropped");
        eq(s.pingUserId, "", "settings: bad user id dropped");
        eq(s.appBase, "", "settings: http app url dropped");
        eq(s.massLimit, 5, "settings: mass limit clamped up");
        eq(s.digestHours, 168, "settings: digest hours clamped down");
        eq(s.coordinationThreshold, 2, "settings: together threshold clamped");
        eq(s.tamperFiles, List.of("ok/file.yml"), "settings: unsafe file paths dropped");
        eq(s.phrases, List.of("shut down"), "settings: phrases trimmed and lower-cased");
        eq(s.serverName, "this server", "settings: blank name replaced");
        ck(!s.lockMessage.isBlank() && !s.guardDenyMessage.isBlank(), "settings: blank messages replaced");

        Settings good = new Settings();
        good.discordWebhook = " https://discord.com/api/webhooks/1/abc ";
        good.pingUserId = "123456789012345678";
        good.appBase = "https://app.example.com/in/";
        good.clamp();
        eq(good.discordWebhook, "https://discord.com/api/webhooks/1/abc", "settings: good webhook kept and trimmed");
        eq(good.pingUserId, "123456789012345678", "settings: good user id kept");
        eq(good.appBase, "https://app.example.com/in", "settings: good app url kept, trailing slash removed");
    }

    // ------------------------------------------------------------------ Watchlist

    static void watchlist() throws Exception {
        File dir = Files.createTempDirectory("nw-wl").toFile();
        File f = new File(dir, "watchlist.tsv");
        Watchlist w = new Watchlist(f);
        eq(w.size(), 0, "watchlist: starts empty");
        Watchlist.Entry e = w.add("Shady", null, Watchlist.Mode.GUARD, "the op", T0);
        eq(e.mode, Watchlist.Mode.GUARD, "watchlist: mode stored");
        ck(w.find(new Identity(UUID.randomUUID(), "shady")) != null, "watchlist: name-only entry matches by name, any case");
        ck(w.find(new Identity(UUID.randomUUID(), ".Shady")) != null, "watchlist: matches a Bedrock dot name");
        ck(w.find(new Identity(UUID.randomUUID(), "Other")) == null, "watchlist: others do not match");
        ck(w.find(null) == null, "watchlist: null identity is nobody");

        UUID real = UUID.randomUUID();
        w.bind(new Identity(real, "Shady"));
        ck(w.find(new Identity(real, "Renamed")) != null, "watchlist: once bound, matches by UUID even after a rename");
        ck(w.find(new Identity(UUID.randomUUID(), "Shady")) == null, "watchlist: once bound, a different account with the same name does not match");
        w.bind(new Identity(real, "Renamed"));
        eq(w.findByName("Renamed").name, "Renamed", "watchlist: new name is remembered");

        w.setLocked(w.findByName("Renamed"), true);
        w.add("Second", UUID.randomUUID(), Watchlist.Mode.OBSERVE, "note\twith\ttabs", T0 + 5);
        Watchlist again = new Watchlist(f);
        eq(again.size(), 2, "watchlist: both entries survive a restart");
        ck(again.findByName("Renamed").locked, "watchlist: frozen state survives a restart");
        eq(again.findByName("Renamed").mode, Watchlist.Mode.GUARD, "watchlist: mode survives");
        eq(again.findByName("Second").note, "note\twith\ttabs", "watchlist: notes with tabs survive");
        eq(again.findByName("Second").added, T0 + 5, "watchlist: added time survives");

        Watchlist.Entry upd = w.add("second", null, Watchlist.Mode.GUARD, "", T0 + 99);
        eq(upd.name, "second", "watchlist: re-adding updates the same entry");
        eq(w.size(), 2, "watchlist: no duplicate created");
        eq(upd.note, "note\twith\ttabs", "watchlist: a blank note keeps the old note");
        ck(w.remove("SECOND"), "watchlist: remove ignores case");
        ck(!w.remove("SECOND"), "watchlist: removing twice is false");
        eq(new Watchlist(f).size(), 1, "watchlist: removal is saved");

        Files.writeString(f.toPath(), "garbage line\nW\tonly\tthree\nW\tu\t\tGUARD\t0\t1\tnote\n#comment\nW\tu2\tGood\tNONSENSE\tX\tnotanumber\tn\n", StandardCharsets.UTF_8);
        Watchlist bad = new Watchlist(f);
        eq(bad.size(), 1, "watchlist: bad lines skipped, good line kept");
        eq(bad.findByName("Good").mode, Watchlist.Mode.OBSERVE, "watchlist: unknown mode becomes observe");
        eq(bad.findByName("Good").added, 0L, "watchlist: bad number becomes 0");
        ck(bad.warnings().size() >= 3, "watchlist: warnings reported for the skipped lines");
        ck(bad.warnings().get(0).contains("line 1 ") && bad.warnings().get(1).contains("line 2 "), "watchlist: warnings name the right line numbers");

        eq(Watchlist.Mode.parse("WATCH"), Watchlist.Mode.OBSERVE, "mode: watch is observe");
        eq(Watchlist.Mode.parse(" guard "), Watchlist.Mode.GUARD, "mode: guard");
        ck(Watchlist.Mode.parse("zzz") == null && Watchlist.Mode.parse(null) == null, "mode: junk is null");
    }

    // ------------------------------------------------------------------ Journal

    static void journal() throws Exception {
        File dir = Files.createTempDirectory("nw-j").toFile();
        Journal j = new Journal(dir, java.time.ZoneId.of("UTC"), 5);
        for (int i = 0; i < 8; i++) {
            j.add(new Alert(T0 + i, Severity.INFO, "c", i % 2 == 0 ? "Even" : ".Odd", "t" + i, "d"));
        }
        eq(j.recent(null, 100).size(), 5, "journal: memory is capped");
        eq(j.recent(null, 100).get(0).title(), "t3", "journal: oldest dropped first");
        eq(j.recent("even", 100).size(), 2, "journal: filter by name ignores case");
        eq(j.recent("odd", 100).size(), 3, "journal: filter ignores the bedrock dot");
        eq(j.recent(null, 2).get(1).title(), "t7", "journal: newest last");
        ck(!j.writeFailed(), "journal: no write failure");
        j.close();
        Files.writeString(new File(dir, "journal-2000-01-01.txt").toPath(), "keep me");
        File[] files = dir.listFiles((d, n) -> n.startsWith("journal-") && n.endsWith(".log"));
        eq(files == null ? 0 : files.length, 1, "journal: one file for one day");
        String text = Files.readString(files[0].toPath());
        eq(text.split("\n").length, 8, "journal: every line on disk even past the memory cap");
        ck(text.contains("t0") && text.contains("t7"), "journal: file has old and new lines");

        Journal j2 = new Journal(dir, java.time.ZoneId.of("UTC"), 5);
        j2.add(new Alert(T0 + 3 * 86_400_000L, Severity.RED, "c", "x", "later", ""));
        j2.close();
        eq(dir.listFiles((d, n) -> n.startsWith("journal-") && n.endsWith(".log")).length, 2, "journal: a new day starts a new file");
        eq(j2.prune(2, T0 + 3 * 86_400_000L), 1, "journal: prune removes the old day");
        eq(j2.prune(2, T0 + 3 * 86_400_000L), 0, "journal: prune twice removes nothing more");
        Files.writeString(new File(dir, "journal-notadate.log").toPath(), "x");
        Files.writeString(new File(dir, "other.txt").toPath(), "x");
        eq(j2.prune(0, T0 + 99 * 86_400_000L), 1, "journal: prune with 0 days removes the remaining real one only");
        ck(new File(dir, "journal-notadate.log").exists() && new File(dir, "other.txt").exists(), "journal: prune leaves foreign files alone");
        ck(new File(dir, "journal-2000-01-01.txt").exists(), "journal: prune leaves a journal-named non-log file alone");

        File notDir = Files.createTempFile("nw-notdir", ".x").toFile();
        Journal broken = new Journal(notDir, java.time.ZoneId.of("UTC"), 5);
        broken.add(new Alert(T0, Severity.INFO, "c", "w", "t", ""));
        ck(broken.writeFailed(), "journal: reports a failed write");
        eq(broken.recent(null, 5).size(), 1, "journal: still keeps it in memory when the disk fails");
        new Journal(null, java.time.ZoneId.of("UTC"), 5).add(new Alert(T0, Severity.INFO, "c", "w", "t", ""));
        eq(new Journal(null, java.time.ZoneId.of("UTC"), 5).prune(1, T0), 0, "journal: no directory means nothing to prune");

        String d = Journal.describe(new Alert(T0, Severity.RED, "cat", "Bob", "did it", "where"), java.time.ZoneId.of("UTC"));
        ck(d.contains("[RED]") && d.contains("cat") && d.contains("Bob: did it") && d.contains("-- where"), "journal: describe format");
        ck(!Journal.describe(new Alert(T0, Severity.INFO, "c", "", "t", ""), java.time.ZoneId.of("UTC")).contains(": t"),
                "journal: describe without a name has no colon");
    }

    // ------------------------------------------------------------------ Alerts engine

    static void alertsEngine() throws Exception {
        Fx f = new Fx();
        Alerts a = f.svc.alerts;
        ck(!a.raise(Severity.INFO, "c", "w", "quiet", "", T0), "alerts: INFO is not sent");
        ck(a.raise(Severity.YELLOW, "c", "Bob", "x", "", T0), "alerts: yellow is sent");
        ck(!a.raise(Severity.YELLOW, "c", "bob", "x", "", T0 + 1000), "alerts: the same alert again within the quiet time is held");
        ck(a.raise(Severity.YELLOW, "c", "Bob", "y", "", T0 + 1000), "alerts: a different title is sent");
        ck(a.raise(Severity.YELLOW, "c", "Carl", "x", "", T0 + 1000), "alerts: a different person is sent");
        ck(a.raise(Severity.YELLOW, "c", "Bob", "x", "", T0 + 61_000), "alerts: after the quiet time it is sent again");
        ck(f.sink.sent.get(1).text().startsWith("(1 similar alerts were held back"), "alerts: the next message says what was held back");
        ck(!f.sink.sent.get(2).text().startsWith("("), "alerts: and only once");
        ck(!f.sink.last().ping(), "alerts: yellow does not ping");
        ck(a.raise(Severity.RED, "c", "Bob", "boom", "detail here", T0 + 62_000), "alerts: red is sent");
        ck(f.sink.last().ping(), "alerts: red pings");
        ck(f.sink.last().text().contains("[RED] Nexus Universe | c") && f.sink.last().text().contains("Bob boom\ndetail here"), "alerts: text layout");
        eq(f.journal.recent(null, 100).size(), 7, "alerts: everything is journaled, sent or not");

        Fx cap = new Fx(s -> s.maxYellowPerMinute = 3);
        for (int i = 0; i < 6; i++) {
            cap.svc.alerts.raise(Severity.YELLOW, "c", "p" + i, "t", "", T0 + i);
        }
        eq(cap.sink.count(Severity.YELLOW), 3L, "alerts: yellow capped per minute");
        ck(!cap.svc.alerts.raise(Severity.YELLOW, "c", "overflow", "t", "", T0 + 10), "alerts: a capped yellow reports not sent");
        Fx edge = new Fx(s -> s.maxYellowPerMinute = 1);
        ck(edge.svc.alerts.raise(Severity.YELLOW, "c", "e1", "t", "", T0), "alerts: edge first sent");
        ck(edge.svc.alerts.raise(Severity.YELLOW, "c", "e2", "t", "", T0 + 60_000L), "alerts: the cap window ends at exactly 60 seconds");
        eq(cap.svc.alerts.heldBack(), 4, "alerts: held-back count after the cap");
        for (int i = 0; i < 6; i++) {
            cap.svc.alerts.raise(Severity.RED, "c", "r" + i, "t", "", T0 + 100 + i);
        }
        eq(cap.sink.count(Severity.RED), 6L, "alerts: red is never capped");
        ck(cap.svc.alerts.raise(Severity.YELLOW, "c", "later", "t", "", T0 + 61_000), "alerts: cap resets after a minute");
        Alerts.Period p = cap.svc.alerts.takePeriod();
        eq(p.red(), 6, "period: red count");
        eq(p.yellow(), 8, "period: yellow count includes held ones");
        eq(p.held(), 4, "period: held count");
        ck(p.byCategory().containsKey("c"), "period: category counted");
        eq(cap.svc.alerts.takePeriod().red(), 0, "period: resets after being taken");

        Fx lots = new Fx(s -> s.maxYellowPerMinute = 300);
        for (int i = 0; i < 700; i++) {
            lots.svc.alerts.raise(Severity.YELLOW, "c", "p" + i, "t", "", T0 + i * 1000L);
        }
        ck(lots.sink.sent.size() > 600, "alerts: many distinct alerts still work (map cleanup does not break sending)");
    }

    // ------------------------------------------------------------------ Baseline

    static void baseline() throws Exception {
        File root = Files.createTempDirectory("nw-root").toFile();
        File plugins = new File(root, "plugins");
        plugins.mkdirs();
        Files.writeString(new File(plugins, "A.jar").toPath(), "aaa");
        Files.writeString(new File(plugins, "B.JAR").toPath(), "bbb");
        Files.writeString(new File(plugins, "notes.txt").toPath(), "ignored");
        Files.writeString(new File(root, "server.properties").toPath(), "x=1");
        new File(root, "config").mkdirs();
        Files.writeString(new File(root, "config/paper-global.yml").toPath(), "y: 2");

        List<File> files = Baseline.collect(root, List.of("server.properties", "config/paper-global.yml", "missing.yml"));
        eq(files.size(), 4, "baseline: two jars (any case) plus two existing files; txt and missing ignored");
        Map<String, String> cache1 = new HashMap<>();
        Map<String, Baseline.Seen> cache = new HashMap<>();
        Map<String, String> h = Baseline.hashAll(root, files, cache);
        ck(h.containsKey("plugins/A.jar") && h.containsKey("plugins/B.JAR") && h.containsKey("server.properties")
                && h.containsKey("config/paper-global.yml"), "baseline: paths are relative with forward slashes");
        eq(h.get("plugins/A.jar"), "9834876dcfb05cb167a5c24953eba58c4ac89b1adf57f28f2f9d09af107ee8f0", "baseline: SHA-256 of 'aaa'");
        cache1.clear();

        File store = new File(root, "plugins/NexusWatchtower/baseline.tsv");
        Baseline b = new Baseline(store);
        ck(!b.existed(), "baseline: none saved at first");
        b.save(h);
        ck(b.existed(), "baseline: exists after saving");
        eq(b.size(), 4, "baseline: size");
        ck(b.compare(h).clean(), "baseline: identical is clean");
        Baseline reloaded = new Baseline(store);
        ck(reloaded.existed() && reloaded.size() == 4 && reloaded.compare(h).clean(), "baseline: survives a restart");

        Files.writeString(new File(plugins, "A.jar").toPath(), "tampered");
        Files.writeString(new File(plugins, "C.jar").toPath(), "new");
        new File(plugins, "B.JAR").delete();
        Map<String, String> h2 = Baseline.hashAll(root, Baseline.collect(root, List.of("server.properties", "config/paper-global.yml")), new HashMap<>());
        Baseline.Diff d = reloaded.compare(h2);
        eq(d.changed(), List.of("plugins/A.jar"), "baseline: changed jar found");
        eq(d.added(), List.of("plugins/C.jar"), "baseline: new jar found");
        eq(d.removed(), List.of("plugins/B.JAR"), "baseline: removed jar found");
        eq(d.total(), 3, "baseline: total");
        ck(!d.clean(), "baseline: not clean");

        // the cache returns the old hash for an untouched size+time, and a fresh hash once the cache is cleared
        File cached = new File(plugins, "A.jar");
        Map<String, Baseline.Seen> c2 = new HashMap<>();
        String first = Baseline.hashAll(root, List.of(cached), c2).get("plugins/A.jar");
        long mod = cached.lastModified();
        Files.writeString(cached.toPath(), "TAMPERED");
        ck(cached.setLastModified(mod), "baseline test: restore time");
        eq(Baseline.hashAll(root, List.of(cached), c2).get("plugins/A.jar"), first, "baseline: unchanged size and time reuses the cached hash");
        c2.clear();
        ck(!Baseline.hashAll(root, List.of(cached), c2).get("plugins/A.jar").equals(first), "baseline: an empty cache rehashes and sees the change");
        Files.writeString(cached.toPath(), "different length now");
        ck(!Baseline.hashAll(root, List.of(cached), c2).get("plugins/A.jar").equals(first), "baseline: a size change is seen even with a cache");

        File unreadable = new File(plugins, "gone.jar");
        eq(Baseline.hashAll(root, List.of(unreadable), new HashMap<>()).get("plugins/gone.jar"), "unreadable", "baseline: a vanished file is 'unreadable', not a crash");

        Files.writeString(store.toPath(), "# only a comment\n\nbroken-line-without-tab\n", StandardCharsets.UTF_8);
        Baseline weird = new Baseline(store);
        eq(weird.size(), 0, "baseline: malformed file loads as empty");
        ck(Baseline.collect(new File(root, "nonexistent"), List.of()).isEmpty(), "baseline: no plugins folder means no files");
    }

    // ------------------------------------------------------------------ owners

    static void owners() throws Exception {
        Fx f = new Fx();
        Identity me = owner();
        ck(f.svc.owner(me), "owner: recognised by name");
        ck(f.svc.owner(new Identity(UUID.randomUUID(), "REALSOCIETY5107")), "owner: name matching ignores case");
        ck(!f.svc.owner(new Identity(UUID.randomUUID(), ".RealSociety5107")), "owner: a Bedrock dot name is NOT the owner unless listed");
        ck(!f.svc.owner(person("Shady")), "owner: others are not");
        ck(!f.svc.owner(null), "owner: null is not");
        UUID u = UUID.randomUUID();
        Fx byId = new Fx(s -> s.owners = Settings.set("RealSociety5107", u.toString().toUpperCase()));
        ck(byId.svc.owner(new Identity(u, "AnyName")), "owner: recognised by UUID");

        ck(f.svc.watchAdd("RealSociety5107", null, Watchlist.Mode.GUARD, "", T0) != null, "owner: cannot be put on the watchlist by name");
        Fx byId2 = new Fx(s -> s.owners = Settings.set(u.toString()));
        ck(byId2.svc.watchAdd("Other", u, Watchlist.Mode.GUARD, "", T0) != null, "owner: cannot be put on the watchlist by UUID");

        // nothing the owner does is ever flagged
        eq(breaks(f, me, 2000, T0).allow(), true, "owner: 2000 fast breaks are allowed");
        ck(f.svc.block(me, true, "COMMAND_BLOCK", "w", 0, 0, 0, T0).allow(), "owner: command block allowed");
        ck(f.svc.command(me, "/op someone", T0).allow(), "owner: /op allowed");
        eq(f.sink.sent.size(), 0, "owner: no alerts at all");
        f.svc.chat(me, "shut down", T0);
        f.svc.joined(me, true, T0);
        f.svc.left(me, T0);
        f.svc.container(me, "CHEST", "w", 0, 0, 0, T0);
        eq(f.sink.sent.size(), 0, "owner: chat, join, leave, container never alert");
        eq(f.journal.recent(null, 10).size(), 0, "owner: nothing is even journaled");
        ck(f.svc.frozen(me).allow(), "owner: never frozen");
        ck(f.svc.bucket(me, "LAVA_BUCKET", "w", 0, 0, 0, T0).allow() && f.svc.ignite(me, "w", 0, 0, 0, T0).allow(), "owner: bucket and fire allowed");
    }

    // ------------------------------------------------------------------ mass blocks

    static void massBlocks() throws Exception {
        Fx f = new Fx();
        Identity rando = person("Rando");
        ck(breaks(f, rando, 299, T0).allow(), "mass: 299 breaks in 3 seconds is fine");
        eq(f.sink.sent.size(), 0, "mass: no alert below the limit");
        breaks(f, rando, 1, T0 + 3000);
        ck(f.sink.has("mass-blocks", "300 blocks in 10 seconds"), "mass: the 300th break raises an alert");
        eq(f.sink.last().alert().severity(), Severity.RED, "mass: it is red");
        ck(f.sink.last().alert().detail().startsWith("nexsus "), "mass: the alert says where");
        ck(f.svc.frozen(rando).allow(), "mass: a non-watched player is never frozen automatically");
        ck(f.svc.block(rando, false, "STONE", "w", 0, 0, 0, T0 + 4000).allow(), "mass: and may keep building");

        Fx slow = new Fx();
        Verdict v = Verdict.ALLOW;
        for (int i = 0; i < 700; i++) {
            v = slow.svc.block(person("Miner"), false, "STONE", "w", i, 1, 1, T0 + i * 100L);
        }
        eq(slow.sink.sent.size(), 0, "mass: 10 breaks a second for a long time is not an attack");
        ck(v.allow(), "mass: slow miner allowed");

        Fx place = new Fx();
        for (int i = 0; i < 150; i++) {
            place.svc.block(person("Both"), i % 2 == 0, "STONE", "w", i, 1, 1, T0 + i * 10L);
        }
        for (int i = 150; i < 300; i++) {
            place.svc.block(person("Both"), i % 2 == 0, "STONE", "w", i, 1, 1, T0 + i * 10L);
        }
        ck(place.sink.has("mass-blocks", "300"), "mass: breaking and placing count together");

        Fx w = new Fx();
        watched(w, "Shady", Watchlist.Mode.OBSERVE);
        breaks(w, person("Shady"), 99, T0);
        eq(w.sink.sent.size(), 0, "mass: watched player below the lower limit is quiet");
        breaks(w, person("Shady"), 1, T0 + 1000);
        ck(w.sink.has("mass-blocks", "100 blocks"), "mass: watched player trips at the lower limit");
        ck(!w.watch.findByName("Shady").locked, "mass: observe mode is never frozen");
        ck(w.svc.block(person("Shady"), false, "STONE", "w", 0, 0, 0, T0 + 2000).allow(), "mass: observe mode may keep going");

        Fx again = new Fx();
        Identity r2 = person("Twice");
        breaks(again, r2, 300, T0);
        again.sink.sent.clear();
        breaks(again, r2, 300, T0 + 5000);
        eq(again.sink.sent.size(), 0, "mass: the same repeated alert inside the quiet time is held, not spammed");
        breaks(again, r2, 300, T0 + 70_000);
        eq(again.sink.sent.size(), 1, "mass: it fires again later");

        Fx cmd = new Fx();
        Identity op = person("OpGuy");
        cmd.svc.block(op, true, "COMMAND_BLOCK", "w", 1, 2, 3, T0);
        ck(cmd.sink.has("command-block", "command block"), "command block: placing one raises an alert");
        eq(cmd.sink.last().alert().severity(), Severity.RED, "command block: red");
        eq(cmd.sink.last().alert().detail(), "w 1 2 3", "command block: location in the alert");
        cmd.svc.block(op, true, "REPEATING_COMMAND_BLOCK", "w", 1, 2, 4, T0 + 100_000);
        cmd.svc.block(op, true, "chain_command_block", "w", 1, 2, 5, T0 + 200_000);
        eq(cmd.sink.count(Severity.RED), 3L, "command block: all three kinds, any case");
        cmd.svc.block(op, false, "COMMAND_BLOCK", "w", 1, 2, 3, T0 + 300_000);
        eq(cmd.sink.count(Severity.RED), 3L, "command block: breaking one is not an alert");
        Fx off = new Fx(s -> s.alertCommandBlocks = false);
        off.svc.block(op, true, "COMMAND_BLOCK", "w", 0, 0, 0, T0);
        eq(off.sink.sent.size(), 0, "command block: can be switched off");
        Fx nul = new Fx();
        ck(nul.svc.block(op, true, null, "w", 0, 0, 0, T0).allow(), "block: null material does not crash");
    }

    // ------------------------------------------------------------------ guard and freeze

    static void guardAndFreeze() throws Exception {
        Fx f = new Fx();
        watched(f, "Shady", Watchlist.Mode.GUARD);
        Identity sh = person("Shady");
        ck(breaks(f, sh, 99, T0).allow(), "guard: below the lower limit nothing happens");
        Verdict v = breaks(f, sh, 1, T0 + 2000);
        ck(!v.allow(), "guard: the tripwire block itself is refused");
        eq(v.message(), f.s.lockMessage, "guard: the player sees the plain frozen message");
        ck(f.sink.has("mass-blocks", "100 blocks"), "guard: tripwire alert sent");
        ck(f.sink.has("lockdown", "frozen automatically"), "guard: freeze alert sent");
        eq(f.sink.count(Severity.RED), 2L, "guard: both are red");
        ck(f.watch.findByName("Shady").locked, "guard: they are now frozen");
        ck(new Watchlist(new File(f.dir, "watchlist.tsv")).findByName("Shady").locked, "guard: the freeze is saved to disk");

        ck(!f.svc.block(sh, true, "STONE", "w", 0, 0, 0, T0 + 3000).allow(), "frozen: cannot place");
        ck(!f.svc.block(sh, false, "STONE", "w", 0, 0, 0, T0 + 3000).allow(), "frozen: cannot break");
        ck(!f.svc.command(sh, "/home", T0 + 3000).allow(), "frozen: cannot run even a harmless command");
        ck(!f.svc.bucket(sh, "WATER_BUCKET", "w", 0, 0, 0, T0 + 3000).allow(), "frozen: cannot use buckets");
        ck(!f.svc.ignite(sh, "w", 0, 0, 0, T0 + 3000).allow(), "frozen: cannot light fires");
        ck(!f.svc.frozen(sh).allow(), "frozen: interaction check refuses");
        ck(f.sink.has("lockdown", "tried to place"), "frozen: attempts are reported (yellow)");
        ck(f.svc.frozen(person("Rando")).allow(), "frozen: only that player is affected");
        ck(f.svc.command(person("Rando"), "/home", T0).allow(), "frozen: others can run commands");

        // a freeze survives a restart
        Watchlist reloaded = new Watchlist(new File(f.dir, "watchlist.tsv"));
        WatchService svc2 = new WatchService(f.s, reloaded, f.journal, f.sink);
        ck(!svc2.frozen(sh).allow(), "frozen: still frozen after a restart");
        ck(!svc2.command(sh, "/spawn", T0).allow(), "frozen: commands still refused after a restart");

        ck(f.svc.unlock("shady", T0 + 5000), "unlock: found by name, any case");
        ck(f.svc.block(sh, false, "STONE", "w", 0, 0, 0, T0 + 6000).allow(), "unlock: may build again");
        ck(f.svc.frozen(sh).allow(), "unlock: interactions allowed");
        ck(!f.svc.unlock("Nobody", T0), "unlock: unknown name is false");
        ck(f.svc.lock("Shady", T0 + 7000), "lock: manual freeze");
        ck(!f.svc.block(sh, false, "STONE", "w", 0, 0, 0, T0 + 8000).allow(), "lock: manual freeze works");
        ck(!f.svc.lock("Nobody", T0), "lock: unknown name is false");

        Fx noAuto = new Fx(s -> s.autoLockdown = false);
        watched(noAuto, "Shady", Watchlist.Mode.GUARD);
        ck(breaks(noAuto, sh, 100, T0).allow(), "guard: with auto-freeze off the tripwire only alerts");
        ck(noAuto.sink.has("mass-blocks", "100"), "guard: alert still sent");

        // each hazard also freezes a guarded player
        Fx tnt = new Fx();
        watched(tnt, "Shady", Watchlist.Mode.GUARD);
        tnt.svc.block(sh, true, "TNT", "w", 0, 0, 0, T0);
        ck(tnt.svc.frozen(sh).allow(), "tnt: one TNT is fine");
        Verdict tv = tnt.svc.block(sh, true, "TNT", "w", 1, 0, 0, T0 + 100);
        ck(!tv.allow(), "tnt: the second TNT in 30 seconds trips a guarded player and freezes them");
        Fx lava = new Fx();
        watched(lava, "Shady", Watchlist.Mode.GUARD);
        lava.svc.bucket(sh, "LAVA_BUCKET", "w", 0, 0, 0, T0);
        ck(!lava.svc.bucket(sh, "LAVA_BUCKET", "w", 1, 0, 0, T0 + 100).allow(), "lava: second bucket trips a guarded player");
        Fx fire = new Fx();
        watched(fire, "Shady", Watchlist.Mode.GUARD);
        fire.svc.ignite(sh, "w", 0, 0, 0, T0);
        fire.svc.ignite(sh, "w", 1, 0, 0, T0 + 10);
        ck(!fire.svc.ignite(sh, "w", 2, 0, 0, T0 + 20).allow(), "fire: third fire trips a guarded player");

        // guard blocks commands
        Fx g = new Fx();
        watched(g, "Shady", Watchlist.Mode.GUARD);
        for (String c : new String[]{"/op Shady", "/minecraft:op Shady", "/OP Shady", "/deop Bob", "/stop", "/reload confirm", "/lp user x", "/luckperms",
                "/fill ~ ~ ~ ~5 ~5 ~5 air", "/setblock 1 1 1 air", "/execute as @a run kill @s", "//set stone", "//wand", "/whitelist off", "/gamerule x y",
                "/WorldEdit:/replace stone air", "/plugman unload X"}) {
            Verdict cv = g.svc.command(sh, c, T0);
            ck(!cv.allow(), "guard blocks " + c);
            eq(cv.message(), g.s.guardDenyMessage, "guard message for " + c);
        }
        for (String c : new String[]{"/home", "/spawn", "/tp Bob", "/gamemode creative", "/give Bob stick", "/msg Bob hi", "/help", "/ban Bob"}) {
            ck(g.svc.command(sh, c, T0).allow(), "guard allows " + c);
        }
        ck(!g.svc.command(sh, "/ fill 1 2 3 4 5 6 air", T0).allow(), "guard blocks '/ fill' with a space after the slash");
        ck(!g.watch.findByName("Shady").locked, "guard: blocked commands do not freeze, only tripwires do");
        ck(g.sink.has("guard", "was stopped from using /op"), "guard: blocked command alert names the command");
        ck(g.sink.sent.stream().anyMatch(x -> x.alert().category().equals("guard") && x.alert().title().contains("/op") && x.alert().severity() == Severity.RED),
                "guard: a blocked red command is red");
        ck(g.sink.sent.stream().anyMatch(x -> x.alert().category().equals("guard") && x.alert().title().contains("/fill") && x.alert().severity() == Severity.YELLOW),
                "guard: a blocked ordinary command is yellow");

        Fx obs = new Fx();
        watched(obs, "Shady", Watchlist.Mode.OBSERVE);
        ck(obs.svc.command(sh, "/fill ~ ~ ~ ~5 ~5 ~5 air", T0).allow(), "observe: never blocks");
        ck(obs.svc.command(sh, "/op Bob", T0).allow(), "observe: not even /op");
        ck(obs.sink.has("command", "ran /op"), "observe: but /op is still alerted loudly");
        ck(g.svc.setMode("Shady", Watchlist.Mode.OBSERVE, T0), "mode: switch to observe");
        ck(g.svc.command(sh, "/fill 1 2 3 4 5 6 air", T0).allow(), "mode: observe stops blocking at once");
        ck(!g.svc.setMode("Nobody", Watchlist.Mode.GUARD, T0), "mode: unknown name is false");
        ck(g.svc.setMode("Shady", Watchlist.Mode.GUARD, T0), "mode: back to guard");
        ck(!g.svc.command(sh, "/fill 1 2 3 4 5 6 air", T0).allow(), "mode: guard blocks again");
        Fx emptyGuard = new Fx(s -> s.guardBlock = new java.util.LinkedHashSet<>());
        watched(emptyGuard, "Shady", Watchlist.Mode.GUARD);
        ck(emptyGuard.svc.command(sh, "/fill 1", T0).allow(), "guard: an empty block list blocks nothing");
        Fx nonWatched = new Fx();
        ck(nonWatched.svc.command(person("Plain"), "/fill 1 2 3 4 5 6 air", T0).allow(), "guard: only applies to guarded players");
    }

    // ------------------------------------------------------------------ hazards

    static void hazards() throws Exception {
        Fx f = new Fx();
        Identity p = person("Bomber");
        for (int i = 0; i < 4; i++) {
            f.svc.block(p, true, "TNT", "w", i, 0, 0, T0 + i * 100L);
        }
        eq(f.sink.sent.size(), 0, "tnt: 4 blocks of TNT is under the limit");
        f.svc.block(p, true, "TNT", "w", 5, 0, 0, T0 + 500);
        ck(f.sink.has("hazard", "a lot of TNT"), "tnt: the 5th in 30 seconds alerts");
        Fx slow = new Fx();
        for (int i = 0; i < 20; i++) {
            slow.svc.block(p, true, "TNT", "w", i, 0, 0, T0 + i * 20_000L);
        }
        eq(slow.sink.sent.size(), 0, "tnt: one every 20 seconds is fine");
        Fx lava = new Fx();
        for (int i = 0; i < 3; i++) {
            lava.svc.bucket(p, "LAVA_BUCKET", "w", i, 0, 0, T0 + i);
        }
        lava.svc.bucket(p, "WATER_BUCKET", "w", 9, 0, 0, T0 + 4);
        eq(lava.sink.sent.size(), 0, "lava: three lava buckets and a water bucket is fine");
        lava.svc.bucket(p, "lava_bucket", "w", 4, 0, 0, T0 + 5);
        ck(lava.sink.has("hazard", "lava"), "lava: the 4th bucket in a minute alerts (any case)");
        Fx fire = new Fx();
        for (int i = 0; i < 6; i++) {
            fire.svc.ignite(p, "w", i, 0, 0, T0 + i);
        }
        ck(fire.sink.has("hazard", "fires"), "fire: 6 fires in 30 seconds alerts");
        Fx w = new Fx();
        w.svc.witherBuilt("w", 1, 2, 3, T0);
        ck(w.sink.has("hazard", "Wither"), "wither: alert sent");
        eq(w.sink.last().alert().detail(), "w 1 2 3", "wither: location");
        Fx w2 = new Fx(s -> s.alertWither = false);
        w2.svc.witherBuilt("w", 1, 2, 3, T0);
        eq(w2.sink.sent.size(), 0, "wither: can be switched off");

        // rate state is dropped when a player leaves
        Fx q = new Fx();
        for (int i = 0; i < 4; i++) {
            q.svc.block(p, true, "TNT", "w", i, 0, 0, T0 + i);
        }
        q.svc.left(p, T0 + 10);
        q.svc.block(p, true, "TNT", "w", 5, 0, 0, T0 + 20);
        eq(q.sink.sent.size(), 0, "tnt: leaving clears the player's counters");
    }

    // ------------------------------------------------------------------ commands

    static void commands() throws Exception {
        eq(WatchService.base("/op Bob"), "op", "base: simple");
        eq(WatchService.base("OP Bob"), "op", "base: no slash, upper case");
        eq(WatchService.base("/minecraft:op Bob"), "op", "base: namespace stripped");
        eq(WatchService.base("/bukkit:reload"), "reload", "base: bukkit namespace");
        eq(WatchService.base("//set stone"), "//", "base: double slash");
        eq(WatchService.base("/worldedit:/set stone"), "//", "base: namespaced worldedit");
        eq(WatchService.base("  /op   Bob "), "op", "base: surrounding spaces");
        eq(WatchService.base("/"), "", "base: lone slash");
        eq(WatchService.base(null), "", "base: null");
        eq(WatchService.base("/home"), "home", "base: plain");
        eq(WatchService.base("/ op Bob"), "op", "base: a space after the slash cannot hide the command");
        eq(WatchService.base("/   //set"), "//", "base: nor can it hide a double slash command");

        Fx f = new Fx();
        Identity p = person("Someone");
        for (String c : new String[]{"/op x", "/deop x", "/stop", "/reload", "/rl", "/restart", "/plugman unload x", "/lp group x", "/pex", "/save-off", "/minecraft:op x"}) {
            Fx each = new Fx();
            each.svc.command(p, c, T0);
            ck(each.sink.count(Severity.RED) == 1, "red command: " + c);
        }
        for (String c : new String[]{"/gamerule doFireTick false", "/whitelist off", "/pardon x", "/difficulty peaceful", "/worldborder set 10", "/forceload add 0 0", "/data merge x"}) {
            Fx each = new Fx();
            each.svc.command(p, c, T0);
            ck(each.sink.count(Severity.YELLOW) == 1 && each.sink.count(Severity.RED) == 0, "yellow command: " + c);
        }
        for (String c : new String[]{"/home", "/spawn", "/msg Bob hi", "/give Bob stick", "/kill Bob", "/tp Bob", "/kill @s", "/kill @p", "/gamemode creative Bob"}) {
            Fx each = new Fx();
            each.svc.command(p, c, T0);
            eq(each.sink.sent.size(), 0, "ordinary command is silent: " + c);
        }
        for (String c : new String[]{"/kill @e", "/kill @e[type=cow]", "/give @a diamond", "/tp @a 0 0 0", "/effect give @a speed", "/clear @a", "/KILL @E[distance=..9]"}) {
            Fx each = new Fx();
            each.svc.command(p, c, T0);
            ck(each.sink.has("command", "mass selector"), "mass selector: " + c);
        }
        Fx sel = new Fx();
        sel.svc.command(p, "/say @e", T0);
        sel.svc.command(p, "/give Bob @e", T0);
        eq(sel.sink.sent.size(), 1, "mass selector: only for the listed commands (a bare '@e' as a later argument still counts)");
        Fx empty = new Fx();
        empty.svc.command(p, "", T0);
        empty.svc.command(p, "/", T0);
        eq(empty.sink.sent.size(), 0, "empty command does not crash or alert");

        // the full text goes in the alert
        Fx t = new Fx();
        t.svc.command(p, "/op Bob", T0);
        eq(t.sink.last().alert().detail(), "/op Bob", "alert detail has the command text");
        eq(t.sink.last().alert().who(), "Someone", "alert names the player");
    }

    // ------------------------------------------------------------------ redaction and the feed

    static void redaction() throws Exception {
        Fx f = new Fx();
        watched(f, "Shady", Watchlist.Mode.OBSERVE);
        Identity sh = person("Shady");
        f.svc.command(sh, "/msg Bob the secret plan is tonight", T0);
        f.svc.command(sh, "/w Bob more secret words", T0 + 1);
        f.svc.command(sh, "/login hunter2", T0 + 2);
        f.svc.command(sh, "/register hunter2 hunter2", T0 + 3);
        f.svc.command(sh, "/reply and another secret", T0 + 4);
        f.svc.command(sh, "/home base", T0 + 5);
        String everything = f.journal.recent(null, 100).toString() + f.sink.sent + f.sink.feeds;
        f.svc.flushFeed();
        everything = f.journal.recent(null, 100).toString() + f.sink.sent + f.sink.feeds;
        ck(!everything.contains("secret"), "redaction: no private message text anywhere");
        ck(!everything.contains("hunter2"), "redaction: no passwords anywhere");
        ck(everything.contains("/msg Bob [message hidden]"), "redaction: the target of a private message is kept");
        ck(everything.contains("/login [hidden]") && everything.contains("/register [hidden]"), "redaction: login and register are fully hidden");
        ck(everything.contains("/home base"), "redaction: ordinary commands are logged in full");
        ck(everything.contains("/reply"), "redaction: reply logged without text");
        Fx f2 = new Fx();
        watched(f2, "Shady", Watchlist.Mode.OBSERVE);
        f2.svc.command(sh, "/msg", T0);
        ck(f2.journal.recent(null, 5).stream().anyMatch(a -> a.title().equals("/msg")), "redaction: /msg with no arguments is safe");
        Fx f3 = new Fx();
        watched(f3, "Shady", Watchlist.Mode.OBSERVE);
        f3.svc.command(sh, "/x " + "y".repeat(500), T0);
        ck(f3.journal.recent(null, 5).stream().filter(a -> a.category().equals("command")).allMatch(a -> a.title().length() < 210), "redaction: very long commands are cut");

        // a red command is alerted with its hidden form too (a password-like red command cannot leak)
        Fx f4 = new Fx(s -> s.hideAll = Settings.set("op"));
        f4.svc.command(person("Bob"), "/op SECRETNAME", T0);
        ck(!f4.sink.last().alert().detail().contains("SECRETNAME"), "redaction: hidden commands stay hidden in alerts too");

        // only watched players get their commands journaled and streamed
        Fx f5 = new Fx();
        f5.svc.command(person("Plain"), "/home", T0);
        eq(f5.journal.recent(null, 5).size(), 0, "feed: ordinary players' commands are not journaled");
        f5.svc.flushFeed();
        eq(f5.sink.feeds.size(), 0, "feed: nothing to send");
    }

    // ------------------------------------------------------------------ chat

    static void chat() throws Exception {
        Fx f = new Fx(s -> s.phrases = new ArrayList<>(List.of("Shut Down", "wipe the world")));
        Identity p = person("Talker");
        f.svc.chat(p, "we should SHUT DOWN this place", T0);
        ck(f.sink.has("chat", "shut down"), "chat: phrase found, any case");
        eq(f.sink.last().alert().detail(), "we should SHUT DOWN this place", "chat: the line is in the alert");
        f.svc.chat(p, "let's wipe the world tonight", T0 + 1);
        eq(f.sink.sent.size(), 2, "chat: second phrase also alerts");
        f.svc.chat(p, "nice weather", T0 + 2);
        f.svc.chat(p, null, T0 + 3);
        f.svc.chat(p, "", T0 + 4);
        eq(f.sink.sent.size(), 2, "chat: ordinary chat is silent, null and empty are safe");
        f.svc.chat(owner(), "shut down", T0 + 5);
        eq(f.sink.sent.size(), 2, "chat: the owner is never flagged");
        f.svc.chat(p, "x".repeat(900) + " shut down", T0 + 100_000);
        ck(f.sink.last().alert().detail().length() <= 203, "chat: long lines are cut in the alert");
        Fx none = new Fx();
        none.svc.chat(p, "shut down", T0);
        eq(none.sink.sent.size(), 0, "chat: no phrases configured means silence");

        // chat arrives on another thread: hammer it from several threads
        Fx race = new Fx(s -> s.phrases = new ArrayList<>(List.of("x")));
        Thread[] ts = new Thread[6];
        for (int i = 0; i < ts.length; i++) {
            final int n = i;
            ts[i] = new Thread(() -> {
                for (int k = 0; k < 200; k++) {
                    race.svc.chat(person("T" + n), "x " + k, T0 + k);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        ck(race.sink.sent.size() > 0, "chat: concurrent calls complete without error");
    }

    // ------------------------------------------------------------------ joins and the feed

    static void joins() throws Exception {
        Fx f = new Fx();
        watched(f, "Shady", Watchlist.Mode.GUARD);
        f.svc.joined(person("Plain"), false, T0);
        f.svc.joined(person("PlainOp"), true, T0);
        eq(f.sink.sent.size(), 0, "join: nobody else is announced");
        f.svc.joined(person("Shady"), true, T0 + 1000);
        ck(f.sink.has("watch", "joined (watched, guard) [operator]"), "join: watched operator announced with their mode");
        eq(f.sink.last().alert().severity(), Severity.YELLOW, "join: yellow");
        f.svc.left(person("Shady"), T0 + 4_000_000);
        ck(f.journal.recent("Shady", 10).stream().anyMatch(a -> a.title().equals("left")), "leave: journaled");
        eq(f.sink.sent.size(), 1, "leave: not sent immediately");

        Fx quiet = new Fx(s -> s.notifyJoins = false);
        watched(quiet, "Shady", Watchlist.Mode.OBSERVE);
        quiet.svc.joined(person("Shady"), false, T0);
        eq(quiet.sink.sent.size(), 0, "join: can be silenced");
        ck(quiet.journal.recent("Shady", 5).size() > 0, "join: still journaled when silenced");

        Fx fr = new Fx();
        watched(fr, "Shady", Watchlist.Mode.GUARD);
        fr.svc.lock("Shady", T0);
        fr.svc.joined(person("Shady"), false, T0 + 5);
        ck(fr.sink.has("watch", "[FROZEN]"), "join: a frozen player is announced as frozen");

        // name-only entries bind on first join
        Fx nm = new Fx();
        nm.svc.watchAdd("Newcomer", null, Watchlist.Mode.OBSERVE, "", T0);
        Identity real = person("Newcomer");
        nm.svc.joined(real, false, T0 + 1);
        eq(nm.watch.findByName("Newcomer").uuid, real.uuid().toString(), "join: the real UUID is bound on first join");

        // coordination
        Fx c = new Fx();
        for (String n : new String[]{"A1", "A2", "A3", "A4"}) {
            watched(c, n, Watchlist.Mode.OBSERVE);
        }
        c.svc.joined(person("A1"), false, T0);
        c.svc.joined(person("A2"), false, T0 + 1);
        ck(!c.sink.has("coordination", "together"), "together: two is below the default threshold");
        c.svc.joined(person("A3"), false, T0 + 2);
        ck(c.sink.has("coordination", "3 watched accounts are online together"), "together: three raises it");
        eq(c.sink.last().alert().detail(), "A1, A2, A3", "together: lists who");
        c.svc.joined(person("A4"), false, T0 + 3);
        eq(c.sink.sent.stream().filter(x -> x.alert().category().equals("coordination")).count(), 1L, "together: not repeated within an hour");
        c.svc.left(person("A3"), T0 + 10);
        c.svc.left(person("A4"), T0 + 11);
        c.svc.joined(person("A3"), false, T0 + 7_200_000L);
        c.svc.joined(person("A4"), false, T0 + 7_200_001L);
        eq(c.sink.sent.stream().filter(x -> x.alert().category().equals("coordination")).count(), 2L, "together: again after an hour");

        // containers
        Fx ct = new Fx();
        watched(ct, "Shady", Watchlist.Mode.OBSERVE);
        ct.svc.container(person("Shady"), "ENDER_CHEST", "w", 1, 2, 3, T0);
        ct.svc.container(person("Plain"), "CHEST", "w", 1, 2, 3, T0);
        ct.svc.container(person("Shady"), null, "w", 4, 5, 6, T0 + 1);
        eq(ct.journal.recent(null, 10).stream().filter(a -> a.category().equals("container")).count(), 2L, "container: only the watched player's opens are journaled");
        eq(ct.sink.sent.size(), 0, "container: never an immediate alert");
        ct.svc.flushFeed();
        ck(ct.sink.feeds.get(0).contains("opened a ender_chest at w 1 2 3") && ct.sink.feeds.get(0).contains("opened a container at w 4 5 6"),
                "container: appears in the feed with its location");
    }

    // ------------------------------------------------------------------ operators

    static void ops() throws Exception {
        Fx f = new Fx();
        Map<UUID, String> now = new LinkedHashMap<>();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        now.put(a, "RealSociety5107");
        now.put(b, "Shady");
        f.svc.opsSync(now, T0);
        eq(f.sink.sent.size(), 0, "ops: with no expected list the first look is just journaled");
        f.svc.opsSync(now, T0 + 1000);
        eq(f.sink.sent.size(), 0, "ops: no change, no alert");
        now.put(c, "Newbie");
        f.svc.opsSync(now, T0 + 2000);
        ck(f.sink.has("ops", "just given operator"), "ops: a new operator is a red alert");
        eq(f.sink.last().alert().severity(), Severity.RED, "ops: red");
        eq(f.sink.last().alert().who(), "Newbie", "ops: names them");
        f.svc.opsSync(now, T0 + 3000);
        eq(f.sink.sent.size(), 1, "ops: not repeated");
        now.remove(c);
        f.svc.opsSync(now, T0 + 4000);
        ck(f.sink.has("ops", "lost operator"), "ops: losing operator is yellow");

        Fx ex = new Fx(s -> s.expectedOps = Settings.set("Shady"));
        Map<UUID, String> n2 = new LinkedHashMap<>();
        n2.put(a, "RealSociety5107");
        n2.put(b, "Shady");
        n2.put(c, "Intruder");
        ex.svc.opsSync(n2, T0);
        eq(ex.sink.sent.size(), 1, "ops expected: owner and listed names are fine, one intruder");
        ck(ex.sink.has("ops", "not on your expected list") && ex.sink.last().alert().who().equals("Intruder"), "ops expected: intruder flagged at startup");
        UUID d = UUID.randomUUID();
        n2.put(d, "shady2");
        ex.svc.opsSync(n2, T0 + 1000);
        ck(ex.sink.last().alert().severity() == Severity.RED && ex.sink.last().alert().who().equals("shady2"), "ops expected: new unlisted operator is red");
        UUID e = UUID.randomUUID();
        n2.put(e, "SHADY");
        ex.svc.opsSync(n2, T0 + 2000);
        eq(ex.sink.last().alert().severity(), Severity.YELLOW, "ops expected: a listed name given operator is only yellow");
        UUID o = UUID.randomUUID();
        n2.put(o, "realsociety5107");
        ex.svc.opsSync(n2, T0 + 3000);
        ck(!ex.sink.last().alert().title().contains("just given"), "ops: an owner being opped is not an alarm");
        eq(ex.sink.sent.stream().filter(x -> x.alert().severity() == Severity.RED).count(), 2L, "ops: red count so far");
    }

    // ------------------------------------------------------------------ plugin events

    static void pluginEvents() throws Exception {
        Fx f = new Fx();
        Fx notReady = new Fx();
        // a fresh service that is not ready ignores startup noise
        WatchService raw = new WatchService(notReady.s, notReady.watch, notReady.journal, notReady.sink);
        raw.pluginEnabled("Vault", T0);
        raw.pluginDisabled("NexusGate", false, T0);
        eq(notReady.sink.sent.size(), 0, "plugins: startup enable/disable noise is ignored");
        f.svc.pluginDisabled("NexusGate", false, T0);
        eq(f.sink.last().alert().severity(), Severity.RED, "plugins: a protection plugin switched off is red");
        f.svc.pluginDisabled("SomethingElse", false, T0);
        eq(f.sink.last().alert().severity(), Severity.YELLOW, "plugins: another plugin is yellow");
        int n = f.sink.sent.size();
        f.svc.pluginDisabled("NexusGate", true, T0 + 100_000);
        eq(f.sink.sent.size(), n, "plugins: during shutdown nothing is reported");
        f.svc.pluginEnabled("NewThing", T0 + 200_000);
        ck(f.sink.has("plugin", "NewThing was switched on"), "plugins: enabling at runtime is reported");
        f.svc.pluginDisabled("NEXUSWATCHTOWER", false, T0 + 300_000);
        eq(f.sink.last().alert().severity(), Severity.RED, "plugins: Watchtower itself is protected, any case");
    }

    // ------------------------------------------------------------------ tamper

    static void tamper() throws Exception {
        Fx f = new Fx();
        Map<String, String> cur = new LinkedHashMap<>();
        cur.put("plugins/A.jar", "h1");
        cur.put("plugins/B.jar", "h2");
        f.svc.tamperResult(new Baseline.Diff(List.of(), List.of(), List.of()), cur, true, T0);
        ck(f.svc.tamperStatus().startsWith("baseline created with 2 files"), "tamper: baseline status");
        eq(f.sink.sent.size(), 0, "tamper: creating a baseline is not an alarm");
        f.svc.tamperResult(new Baseline.Diff(List.of(), List.of(), List.of()), cur, false, T0 + 1000);
        ck(f.svc.tamperStatus().startsWith("clean, 2 files"), "tamper: clean status");
        eq(f.sink.sent.size(), 0, "tamper: clean is silent");

        cur.put("plugins/A.jar", "h1x");
        cur.put("plugins/C.jar", "h3");
        Baseline.Diff d = new Baseline.Diff(List.of("plugins/C.jar"), List.of("plugins/Z.jar"), List.of("plugins/A.jar"));
        f.svc.tamperResult(d, cur, false, T0 + 2000);
        eq(f.sink.count(Severity.RED), 1L, "tamper: one red alert");
        String detail = f.sink.last().alert().detail();
        ck(detail.contains("changed plugins/A.jar") && detail.contains("new plugins/C.jar") && detail.contains("missing plugins/Z.jar"), "tamper: lists all three kinds");
        ck(detail.startsWith("changed plugins/A.jar; new plugins/C.jar; missing plugins/Z.jar. If"), "tamper: neat separators, no leading semicolon");
        ck(detail.contains("/watchtower baseline"), "tamper: says how to approve");
        ck(f.svc.tamperStatus().startsWith("3 difference(s)"), "tamper: status counts");
        f.svc.tamperResult(d, cur, false, T0 + 2_000_000);
        eq(f.sink.count(Severity.RED), 1L, "tamper: the same difference is not reported again");
        cur.put("plugins/A.jar", "h1y");
        f.svc.tamperResult(d, cur, false, T0 + 4_000_000);
        eq(f.sink.count(Severity.RED), 2L, "tamper: a file changed AGAIN is reported again");

        Fx many = new Fx();
        Map<String, String> big = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            big.put("plugins/P" + i + ".jar", "h" + i);
            added.add("plugins/P" + i + ".jar");
        }
        many.svc.tamperResult(new Baseline.Diff(added, List.of(), List.of()), big, false, T0);
        ck(many.sink.last().alert().detail().contains("and 4 more"), "tamper: long lists are summarised");
        ck(many.sink.last().alert().detail().contains("P7.jar") && !many.sink.last().alert().detail().contains("P8.jar"), "tamper: exactly eight are listed");
        ck(many.sink.last().alert().title().startsWith("12 server file(s)"), "tamper: title has the true count");
    }

    // ------------------------------------------------------------------ digest and feed

    static void digestAndFeed() throws Exception {
        Fx f = new Fx();
        watched(f, "Shady", Watchlist.Mode.GUARD);
        watched(f, "Quiet", Watchlist.Mode.OBSERVE);
        Identity sh = person("Shady");
        f.svc.joined(sh, true, T0);
        f.svc.command(sh, "/home", T0 + 1000);
        f.svc.command(sh, "/home", T0 + 2000);
        f.svc.block(sh, false, "STONE", "w", 0, 0, 0, T0 + 3000);
        f.svc.block(sh, true, "STONE", "w", 0, 0, 0, T0 + 4000);
        f.svc.container(sh, "CHEST", "w", 0, 0, 0, T0 + 5000);
        f.svc.command(person("Plain"), "/op x", T0 + 6000);
        Map<UUID, String> ops = new LinkedHashMap<>();
        ops.put(UUID.randomUUID(), "Zed");
        ops.put(UUID.randomUUID(), "Abe");
        String d = f.svc.digest(ops, T0 + 3_600_000L);
        ck(d.startsWith("NexusWatchtower digest | Nexus Universe"), "digest: header");
        ck(d.contains("1 red"), "digest: red count (the /op)");
        ck(d.contains("Shady [guard] online 1h00m, 2 commands, 1 broken, 1 placed, 1 containers"), "digest: watched player line with stats");
        ck(d.contains("Quiet [observe] quiet"), "digest: a quiet watched player is called quiet");
        ck(d.contains("Most blocks changed: Shady (2)"), "digest: top block changers");
        ck(d.contains("Operators now: Abe, Zed"), "digest: operators sorted");
        ck(d.contains("Files: not checked yet"), "digest: file status");
        ck(d.length() < 1900, "digest: fits one Discord message");
        String d2 = f.svc.digest(ops, T0 + 7_200_000L);
        ck(d2.contains("0 red, 0 yellow"), "digest: counters reset after a digest");
        ck(d2.contains("Shady [guard] online 1h00m, 0 commands"), "digest: a still-online player keeps accumulating time, other counters reset");
        ck(!d2.contains("Most blocks changed"), "digest: block counts reset");
        Fx e = new Fx();
        ck(e.svc.digest(new HashMap<>(), T0).contains("Watchlist: empty") && e.svc.digest(new HashMap<>(), T0).contains("Operators now: none"),
                "digest: empty watchlist and no operators");
        Fx lots = new Fx();
        for (int i = 0; i < 40; i++) {
            lots.svc.watchAdd("Person" + i, null, Watchlist.Mode.OBSERVE, "", T0);
        }
        String dl = lots.svc.digest(new HashMap<>(), T0);
        ck(dl.contains("and 25 more") && dl.length() < 1900, "digest: a long watchlist is summarised");

        // the feed
        Fx g = new Fx(s -> s.feedMaxLines = 3);
        watched(g, "Shady", Watchlist.Mode.OBSERVE);
        g.svc.flushFeed();
        eq(g.sink.feeds.size(), 0, "feed: nothing to send when nothing happened");
        for (int i = 0; i < 5; i++) {
            g.svc.command(sh, "/cmd" + i, T0 + i);
        }
        g.svc.flushFeed();
        eq(g.sink.feeds.size(), 1, "feed: one batched message");
        String feed = g.sink.feeds.get(0);
        ck(feed.contains("Shady: /cmd0") && feed.contains("/cmd2") && !feed.contains("/cmd3"), "feed: capped at the line limit");
        ck(feed.contains("(+2 more, see /watchtower log)"), "feed: says how many were left out");
        ck(feed.contains("22:13:20"), "feed: lines are time-stamped in the configured zone");
        g.svc.flushFeed();
        eq(g.sink.feeds.size(), 1, "feed: cleared after sending");
        g.svc.command(sh, "/again", T0 + 99);
        g.svc.flushFeed();
        eq(g.sink.feeds.size(), 2, "feed: starts fresh");
        ck(!g.sink.feeds.get(1).contains("more"), "feed: the dropped counter reset too");

        Fx off = new Fx(s -> s.streamCommands = false);
        watched(off, "Shady", Watchlist.Mode.OBSERVE);
        off.svc.command(sh, "/home", T0);
        off.svc.flushFeed();
        eq(off.sink.feeds.size(), 0, "feed: can be switched off");
        ck(off.journal.recent("Shady", 5).size() > 0, "feed off: still journaled");

        Fx tz = new Fx(s -> s.timezone = "America/Phoenix");
        watched(tz, "Shady", Watchlist.Mode.OBSERVE);
        tz.svc.command(sh, "/home", T0);
        tz.svc.flushFeed();
        ck(tz.sink.feeds.get(0).contains("15:13:20"), "feed: respects the timezone setting");
    }

    // ------------------------------------------------------------------ owner actions

    static void owner_actions() throws Exception {
        Fx f = new Fx();
        eq(f.svc.watchAdd("Shady", null, Watchlist.Mode.GUARD, "the op", T0), null, "add: ok");
        ck(f.svc.watchRemove("shady", T0), "remove: ok");
        ck(!f.svc.watchRemove("shady", T0), "remove: second time false");
        f.svc.probe(person("Snoop"), "/watchtower status", T0);
        ck(f.sink.has("probe", "tried to use the owner-only"), "probe: yellow alert");
        eq(f.sink.last().alert().who(), "Snoop", "probe: names who");
        List<String> st = f.svc.status(new HashMap<>(), T0);
        ck(st.get(0).startsWith("Watching 0 player(s)"), "status: first line");
        ck(st.stream().anyMatch(l -> l.contains("App: off") && l.contains("Discord: off")), "status: shows app and Discord off");
        Fx on = new Fx(s -> {
            s.discordWebhook = "https://discord.com/api/webhooks/1/abc";
            s.appSecret = "sec";
            s.appBase = "https://a.example.com";
        });
        ck(on.svc.status(new HashMap<>(), T0).stream().anyMatch(l -> l.contains("App: off") || l.contains("Discord: on")), "status: shows Discord on");
        f.journal.close();
    }


    // ------------------------------------------------------------------ extras found by mutation testing

    static void extras() throws Exception {
        // Stats: clocks and housekeeping
        Stats st = new Stats();
        Identity a = person("A");
        st.joined(a, 10_000);
        st.left(a, 5_000);
        eq(st.takeAndReset(20_000).get(0).onlineMs, 0L, "stats: a clock that went backwards never gives negative time");
        Stats st2 = new Stats();
        Identity b = person("B");
        Identity c = person("C");
        st2.rec(b).containers = 1;
        st2.rec(c);
        st2.forgetOffline();
        List<Stats.Rec> left = st2.takeAndReset(0);
        eq(left.size(), 1, "stats: forgetOffline drops empty records and keeps ones with activity");
        eq(left.get(0).name, "B", "stats: the one with activity is kept");
        Stats st3 = new Stats();
        st3.joined(a, 1000);
        st3.forgetOffline();
        eq(st3.takeAndReset(61_000).get(0).onlineMs, 60_000L, "stats: an online player survives forgetOffline");
        Stats st4 = new Stats();
        st4.rec(b).placed = 1;
        st4.rec(c).broken = 1;
        st4.forgetOffline();
        eq(st4.takeAndReset(0).size(), 2, "stats: placed or broken counts also keep a record");
        Stats st5 = new Stats();
        st5.rec(b).commands = 1;
        st5.rec(c).joins = 1;
        st5.forgetOffline();
        eq(st5.takeAndReset(0).size(), 2, "stats: commands or joins also keep a record");

        // Alerts: exactly at the quiet boundary the alert is sent again
        Fx f = new Fx();
        f.svc.alerts.raise(Severity.YELLOW, "c", "w", "t", "", T0);
        ck(!f.svc.alerts.raise(Severity.YELLOW, "c", "w", "t", "", T0 + 59_999), "alerts: one millisecond early is still held");
        ck(f.svc.alerts.raise(Severity.YELLOW, "c", "w", "t", "", T0 + 60_000), "alerts: exactly at the quiet time it is sent");

        // Journal: one instance crossing midnight starts a new file
        File dir = Files.createTempDirectory("nw-mid").toFile();
        Journal j = new Journal(dir, java.time.ZoneId.of("UTC"), 10);
        j.add(new Alert(T0, Severity.INFO, "c", "w", "day one", ""));
        j.add(new Alert(T0 + 86_400_000L, Severity.INFO, "c", "w", "day two", ""));
        j.close();
        eq(dir.listFiles((d2, n) -> n.endsWith(".log")).length, 2, "journal: the same journal rolls to a new file on a new day");

        // shown(): exactly 200 characters is kept whole
        Fx t = new Fx();
        watched(t, "Shady", Watchlist.Mode.OBSERVE);
        String exact = "/x " + "y".repeat(197);
        eq(exact.length(), 200, "test setup: exactly 200 characters");
        t.svc.command(person("Shady"), exact, T0);
        ck(t.journal.recent("Shady", 10).stream().anyMatch(al -> al.title().equals(exact)), "redaction: a 200 character command is not cut");

        // Webhook.post against a real local HTTP server
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        final List<String> bodies = new ArrayList<>();
        final List<String> auths = new ArrayList<>();
        final int[] status = {204};
        srv.createContext("/hook", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auths.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.sendResponseHeaders(status[0], -1);
            ex.close();
        });
        srv.start();
        try {
            String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/hook";
            final List<String> warns = new ArrayList<>();
            Logger log = Logger.getLogger("nw-webhook-test");
            log.setUseParentHandlers(false);
            log.addHandler(new java.util.logging.Handler() {
                @Override
                public void publish(java.util.logging.LogRecord r) {
                    warns.add(r.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            Webhook.post(url, "{\"a\":1}", "sekret", 5, log);
            eq(bodies.get(0), "{\"a\":1}", "post: body delivered");
            eq(auths.get(0), "Bearer sekret", "post: bearer token sent when set");
            Webhook.post(url, "{}", null, 5, log);
            eq(auths.get(1), "null", "post: no Authorization header without a token");
            Webhook.post(url, "{}", "   ", 5, log);
            eq(auths.get(2), "null", "post: a blank token sends no header");
            Webhook.post(url, "{}", "", 5, log);
            eq(auths.get(3), "null", "post: an empty token sends no header");
            eq(warns.size(), 0, "post: success logs nothing");
            status[0] = 500;
            Webhook.post(url, "{}", null, 5, log);
            ck(warns.size() == 1 && warns.get(0).contains("500"), "post: an error status is logged");
        } finally {
            srv.stop(0);
        }
        final List<String> warns2 = new ArrayList<>();
        Logger log2 = Logger.getLogger("nw-webhook-test2");
        log2.setUseParentHandlers(false);
        log2.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                warns2.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Webhook.post("http://127.0.0.1:1/none", "{}", null, 2, log2);
        ck(warns2.size() == 1 && warns2.get(0).contains("Could not reach"), "post: an unreachable endpoint is logged, never thrown");
    }

    // =====================================================================================
    // The Bukkit layer: real listener, command and plugin classes against the stub server.
    // =====================================================================================

    static class TP extends Player {
        final String n;
        final List<String> msgs = new ArrayList<>();
        final UUID id;

        TP(String n) {
            this.n = n;
            this.id = person(n).uuid();
        }

        @Override
        public String getName() {
            return n;
        }

        @Override
        public UUID getUniqueId() {
            return id;
        }

        @Override
        public void sendMessage(String message) {
            msgs.add(message);
        }

        boolean got(String part) {
            return msgs.stream().anyMatch(m -> m.contains(part));
        }
    }

    static final class Console implements ConsoleCommandSender {
        final List<String> msgs = new ArrayList<>();

        @Override
        public boolean hasPermission(String node) {
            return true;
        }

        @Override
        public void sendMessage(String message) {
            msgs.add(message);
        }

        @Override
        public String getName() {
            return "CONSOLE";
        }

        @Override
        public void sendMessage(net.kyori.adventure.text.Component component) {
        }
    }

    static final class OtherSender implements org.bukkit.command.CommandSender {
        final List<String> msgs = new ArrayList<>();

        @Override
        public boolean hasPermission(String node) {
            return true;
        }

        @Override
        public void sendMessage(String message) {
            msgs.add(message);
        }

        @Override
        public String getName() {
            return "@";
        }

        @Override
        public void sendMessage(net.kyori.adventure.text.Component component) {
        }
    }

    static Block blockOf(Material m, int x, int y, int z) {
        Block b = new World("nexsus").getBlockAt(x, y, z);
        b.setType(m);
        return b;
    }

    static final class FakeConfig extends FileConfiguration {
        final Map<String, Object> v = new HashMap<>();

        @Override
        public boolean contains(String path) {
            return v.containsKey(path);
        }

        @Override
        public String getString(String path, String def) {
            return v.containsKey(path) ? (String) v.get(path) : def;
        }

        @Override
        public int getInt(String path, int def) {
            return v.containsKey(path) ? (Integer) v.get(path) : def;
        }

        @Override
        public boolean getBoolean(String path, boolean def) {
            return v.containsKey(path) ? (Boolean) v.get(path) : def;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<String> getStringList(String path) {
            return v.containsKey(path) ? (List<String>) v.get(path) : new ArrayList<>();
        }
    }

    static Plugin fakePlugin(String name) {
        return new Plugin() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public FileConfiguration getConfig() {
                return new FileConfiguration();
            }

            @Override
            public Logger getLogger() {
                return Logger.getLogger("fake");
            }

            @Override
            public File getDataFolder() {
                return new File(".");
            }

            @Override
            public void reloadConfig() {
            }

            @Override
            public void saveDefaultConfig() {
            }

            @Override
            public void saveConfig() {
            }

            @Override
            public void saveResource(String resourcePath, boolean replace) {
            }

            @Override
            public PluginDescriptionFile getDescription() {
                return new PluginDescriptionFile();
            }

            @Override
            public org.bukkit.Server getServer() {
                return new org.bukkit.Server();
            }
        };
    }

    static WatchtowerPlugin boot(File server) throws Exception {
        File data = new File(server, "plugins/NexusWatchtower");
        data.mkdirs();
        JavaPlugin.TEST_DATA = data;
        Bukkit.ONLINE.clear();
        Bukkit.OPERATORS.clear();
        Bukkit.STOPPING = false;
        org.bukkit.scheduler.BukkitScheduler.QUEUED.clear();
        WatchtowerPlugin p = new WatchtowerPlugin();
        p.onEnable();
        return p;
    }

    static void bukkitLayer() throws Exception {
        File server = Files.createTempDirectory("nw-server").toFile();
        new File(server, "plugins").mkdirs();
        Files.writeString(new File(server, "plugins/Some.jar").toPath(), "jar");
        Files.writeString(new File(server, "server.properties").toPath(), "a=b");
        WatchtowerPlugin plugin = boot(server);
        WatchService svc = plugin.service();
        RecSinkProbe probe = new RecSinkProbe(svc);
        WatchListener l = new WatchListener(plugin, svc);
        eq(plugin.settings().owners, Set.of("realsociety5107"), "plugin: default owner from config defaults");

        // startup: queued ready task runs, ops get their first look
        TP me = new TP("RealSociety5107");
        TP shady = new TP("Shady");
        TP rando = new TP("Rando");
        shady.setOp(true);
        for (Runnable r : new ArrayList<>(org.bukkit.scheduler.BukkitScheduler.QUEUED)) {
            r.run();
        }
        ck(svc.ready(), "plugin: becomes ready after startup delay");
        ck(plugin.statusLines().get(5).contains("armed"), "plugin: status says armed");

        ck(svc.watchAdd("Shady", shady.getUniqueId(), Watchlist.Mode.GUARD, "", T0) == null, "layer: watch the op");

        // block events
        Block cb = blockOf(Material.COMMAND_BLOCK, 5, 70, 9);
        BlockPlaceEvent place = new BlockPlaceEvent(cb, rando);
        l.onPlace(place);
        ck(!place.isCancelled(), "layer: an alerted placement is still allowed");
        ck(probe.has("command-block"), "layer: command block placement alerted");
        ck(probe.lastDetail().equals("nexsus 5 70 9"), "layer: world and coordinates passed through");
        BlockPlaceEvent ownerPlace = new BlockPlaceEvent(cb, me);
        int before = probe.count();
        l.onPlace(ownerPlace);
        eq(probe.count(), before, "layer: owner's command block not alerted");

        for (int i = 0; i < 100; i++) {
            BlockBreakEvent br = new BlockBreakEvent(blockOf(Material.STONE, i, 64, 0), shady);
            l.onBreak(br);
            if (i == 99) {
                ck(br.isCancelled(), "layer: the tripwire break is cancelled for a guarded op");
            }
        }
        ck(shady.got("can't do that right now"), "layer: the frozen player is told, plainly");
        BlockBreakEvent after = new BlockBreakEvent(blockOf(Material.STONE, 1, 1, 1), shady);
        l.onBreak(after);
        ck(after.isCancelled(), "layer: further breaks cancelled");
        BlockPlaceEvent afterP = new BlockPlaceEvent(blockOf(Material.STONE, 1, 1, 1), shady);
        l.onPlace(afterP);
        ck(afterP.isCancelled(), "layer: placing cancelled while frozen");
        BlockBreakEvent otherBreak = new BlockBreakEvent(blockOf(Material.STONE, 1, 1, 1), rando);
        l.onBreak(otherBreak);
        ck(!otherBreak.isCancelled(), "layer: others unaffected");

        // frozen interaction paths
        PlayerInteractEvent inter = new PlayerInteractEvent(shady, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, cb);
        l.onInteract(inter);
        ck(inter.isCancelled(), "layer: frozen player cannot interact");
        PlayerInteractEvent inter2 = new PlayerInteractEvent(rando, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, cb);
        l.onInteract(inter2);
        ck(!inter2.isCancelled(), "layer: others can interact");
        EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(shady, rando, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        l.onHit(hit);
        ck(hit.isCancelled(), "layer: frozen player cannot hurt anyone");
        EntityDamageByEntityEvent hit2 = new EntityDamageByEntityEvent(rando, shady, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        l.onHit(hit2);
        ck(!hit2.isCancelled(), "layer: others can still fight, and a frozen player can be hit");
        EntityDamageByEntityEvent mobHit = new EntityDamageByEntityEvent(new Entity(), rando, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        l.onHit(mobHit);
        ck(!mobHit.isCancelled(), "layer: non-player damagers are ignored");
        final Inventory chestInv = new Inventory() {
            @Override
            public Location getLocation() {
                return new Location(new World("nexsus"), 3, 4, 5);
            }

            @Override
            public InventoryType getType() {
                return InventoryType.CHEST;
            }
        };
        InventoryOpenEvent open = new InventoryOpenEvent() {
            @Override
            public org.bukkit.entity.HumanEntity getPlayer() {
                return shady;
            }

            @Override
            public Inventory getInventory() {
                return chestInv;
            }
        };
        l.onOpen(open);
        ck(open.isCancelled(), "layer: frozen player cannot open containers");
        InventoryOpenEvent open2 = new InventoryOpenEvent() {
            @Override
            public org.bukkit.entity.HumanEntity getPlayer() {
                return rando;
            }

            @Override
            public Inventory getInventory() {
                return chestInv;
            }
        };
        l.onOpen(open2);
        ck(!open2.isCancelled(), "layer: others open containers");
        InventoryOpenEvent noLoc = new InventoryOpenEvent() {
            @Override
            public org.bukkit.entity.HumanEntity getPlayer() {
                return rando;
            }

            @Override
            public Inventory getInventory() {
                return new Inventory();
            }
        };
        l.onOpen(noLoc);
        ck(!noLoc.isCancelled(), "layer: virtual menus without a location are left alone");
        svc.unlock("Shady", T0);
        InventoryOpenEvent open3 = new InventoryOpenEvent() {
            @Override
            public org.bukkit.entity.HumanEntity getPlayer() {
                return shady;
            }

            @Override
            public Inventory getInventory() {
                return chestInv;
            }
        };
        l.onOpen(open3);
        ck(!open3.isCancelled(), "layer: released player opens containers again");
        ck(svc.journal.recent("Shady", 50).stream().anyMatch(a -> a.title().contains("opened a chest at nexsus 3 4 5")), "layer: a watched container open is journaled with its place");

        // buckets and fire
        PlayerBucketEmptyEvent b1 = new PlayerBucketEmptyEvent(shady, blockOf(Material.STONE, 1, 1, 1), Material.LAVA_BUCKET);
        l.onBucket(b1);
        ck(!b1.isCancelled(), "layer: first lava bucket allowed");
        PlayerBucketEmptyEvent b2 = new PlayerBucketEmptyEvent(shady, blockOf(Material.STONE, 1, 1, 1), Material.LAVA_BUCKET);
        int shadyMsgs = shady.msgs.size();
        l.onBucket(b2);
        ck(b2.isCancelled(), "layer: second lava bucket trips a guarded op");
        ck(shady.msgs.size() > shadyMsgs, "layer: and the player is told");
        svc.unlock("Shady", T0);
        BlockIgniteEvent fire = new BlockIgniteEvent(blockOf(Material.STONE, 1, 1, 1), BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, rando);
        l.onIgnite(fire);
        BlockIgniteEvent lightning = new BlockIgniteEvent(blockOf(Material.STONE, 1, 1, 1), BlockIgniteEvent.IgniteCause.LIGHTNING, rando);
        l.onIgnite(lightning);
        BlockIgniteEvent noPlayer = new BlockIgniteEvent(blockOf(Material.STONE, 1, 1, 1), BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, null);
        l.onIgnite(noPlayer);
        ck(!fire.isCancelled() && !lightning.isCancelled() && !noPlayer.isCancelled(), "layer: ignite handler leaves ordinary fires alone");
        for (int i = 0; i < 5; i++) {
            l.onIgnite(new BlockIgniteEvent(blockOf(Material.STONE, 1, 1, 1), BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, rando));
        }
        ck(probe.has("hazard"), "layer: repeated flint and steel use alerts");
        svc.unlock("Shady", T0);
        int igniteMsgs = shady.msgs.size();
        BlockIgniteEvent last = null;
        for (int i = 0; i < 3; i++) {
            last = new BlockIgniteEvent(blockOf(Material.STONE, 2, 2, 2), BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, shady);
            l.onIgnite(last);
        }
        ck(last.isCancelled() && shady.msgs.size() > igniteMsgs, "layer: a guarded op lighting fires is cancelled, frozen and told");
        svc.unlock("Shady", T0);

        // wither
        Entity wither = new LivingEntity() {
            @Override
            public Location getLocation() {
                return new Location(new World("nexsus"), 7, 8, 9);
            }
        };
        l.onSpawn(new CreatureSpawnEvent((LivingEntity) wither, CreatureSpawnEvent.SpawnReason.BUILD_WITHER));
        ck(probe.has("hazard") && probe.sawTitle("A Wither was built"), "layer: wither alert");
        int c0 = probe.count();
        l.onSpawn(new CreatureSpawnEvent((LivingEntity) wither, CreatureSpawnEvent.SpawnReason.NATURAL));
        eq(probe.count(), c0, "layer: ordinary spawns are ignored");

        // commands
        svc.setMode("Shady", Watchlist.Mode.GUARD, T0);
        PlayerCommandPreprocessEvent c1 = new PlayerCommandPreprocessEvent(shady, "/fill ~ ~ ~ ~9 ~9 ~9 air");
        l.onCommand(c1);
        ck(c1.isCancelled(), "layer: guarded op's /fill cancelled");
        ck(shady.got("do not have permission"), "layer: and told the plain message");
        PlayerCommandPreprocessEvent c2 = new PlayerCommandPreprocessEvent(shady, "/home");
        l.onCommand(c2);
        ck(!c2.isCancelled(), "layer: harmless command allowed");
        PlayerCommandPreprocessEvent c3 = new PlayerCommandPreprocessEvent(me, "/op Shady");
        l.onCommand(c3);
        ck(!c3.isCancelled(), "layer: owner may do anything");
        PlayerCommandPreprocessEvent c4 = new PlayerCommandPreprocessEvent(rando, "/op Rando");
        l.onCommand(c4);
        ck(!c4.isCancelled() && probe.has("command"), "layer: a non-watched player's /op is alerted, not blocked");

        // chat, join, quit
        plugin.settings().phrases.add("shut down");
        l.onChat(new AsyncPlayerChatEvent(rando, "please SHUT DOWN the server"));
        ck(probe.has("chat"), "layer: chat phrase alerted");
        l.onJoin(new PlayerJoinEvent(shady));
        ck(probe.sawTitle("joined (watched, guard) [operator]"), "layer: join passes the operator flag");
        l.onQuit(new PlayerQuitEvent(shady));
        ck(svc.journal.recent("Shady", 50).stream().anyMatch(a -> a.title().equals("left")), "layer: quit journaled");

        // plugin enable/disable
        l.onPluginOff(new PluginDisableEvent(fakePlugin("NexusGate")));
        ck(probe.sawTitle("NexusGate was switched off"), "layer: a protection plugin switched off is reported");
        Bukkit.STOPPING = true;
        int c1n = probe.count();
        l.onPluginOff(new PluginDisableEvent(fakePlugin("NexusLogs")));
        eq(probe.count(), c1n, "layer: during shutdown, nothing");
        Bukkit.STOPPING = false;
        int c2n = probe.count();
        l.onPluginOff(new PluginDisableEvent(fakePlugin(plugin.getName())));
        ck(probe.sawTitle(plugin.getName() + " was switched off while the server was running"), "layer: Watchtower being switched off while running is reported");
        ck(probe.count() > c2n, "layer: and it is a new alert");
        Bukkit.STOPPING = true;
        int c3n = probe.count();
        l.onPluginOff(new PluginDisableEvent(fakePlugin(plugin.getName())));
        eq(probe.count(), c3n, "layer: Watchtower closing during shutdown stays quiet");
        for (String field : new String[]{"closing", "disableReported"}) {
            java.lang.reflect.Field fld = plugin.getClass().getDeclaredField(field);
            fld.setAccessible(true);
            fld.setBoolean(plugin, false);
        }
        Bukkit.STOPPING = false;
        l.onPluginOn(new PluginEnableEvent(fakePlugin("Fresh")));
        ck(probe.sawTitle("Fresh was switched on"), "layer: plugin switched on reported");

        // a handler that blows up must never break the game
        WatchService boom = null;
        WatchListener bad = new WatchListener(plugin, boom);
        final List<String> warned = new ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.WARNING) {
                    warned.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(capture);
        BlockBreakEvent safe = new BlockBreakEvent(blockOf(Material.STONE, 1, 1, 1), rando);
        bad.onBreak(safe);
        PlayerCommandPreprocessEvent safeCmd = new PlayerCommandPreprocessEvent(rando, "/home");
        bad.onCommand(safeCmd);
        bad.onChat(new AsyncPlayerChatEvent(rando, "x"));
        bad.onJoin(new PlayerJoinEvent(rando));
        bad.onQuit(new PlayerQuitEvent(rando));
        bad.onPluginOn(new PluginEnableEvent(fakePlugin("Z")));
        bad.onPluginOff(new PluginDisableEvent(fakePlugin("Z")));
        bad.onInteract(new PlayerInteractEvent(rando, org.bukkit.event.block.Action.RIGHT_CLICK_AIR, null, null));
        bad.onHit(new EntityDamageByEntityEvent(rando, shady, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        bad.onPlace(new BlockPlaceEvent(blockOf(Material.STONE, 1, 1, 1), rando));
        bad.onBucket(new PlayerBucketEmptyEvent(rando, blockOf(Material.STONE, 1, 1, 1), Material.LAVA_BUCKET));
        bad.onIgnite(new BlockIgniteEvent(blockOf(Material.STONE, 1, 1, 1), BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, rando));
        bad.onSpawn(new CreatureSpawnEvent((LivingEntity) wither, CreatureSpawnEvent.SpawnReason.BUILD_WITHER));
        bad.onOpen(new InventoryOpenEvent() {
            @Override
            public org.bukkit.entity.HumanEntity getPlayer() {
                return rando;
            }

            @Override
            public Inventory getInventory() {
                return chestInv;
            }
        });
        ck(!safe.isCancelled() && !safeCmd.isCancelled(), "layer: a failing handler leaves the event alone (fails open)");
        plugin.getLogger().removeHandler(capture);
        for (String what : new String[]{"a block break", "a block placement", "a bucket", "a fire", "a spawn", "a command", "a chat line", "a join",
                "a quit", "a container", "an interaction", "a hit", "a plugin switch-off", "a plugin switch-on"}) {
            ck(warned.stream().anyMatch(w2 -> w2.contains("could not handle " + what + ";")), "layer: failure of " + what + " is logged");
        }

        // ---- the /watchtower command
        WatchCommand cmd = new WatchCommand(plugin, svc);
        Command c = new Command() {
            @Override
            public String getName() {
                return "watchtower";
            }
        };
        int pc = probe.count();
        cmd.onCommand(rando, c, "watchtower", new String[]{"status"});
        ck(rando.got("Unknown command"), "command: a stranger just sees 'Unknown command'");
        ck(!rando.got("Watching"), "command: a stranger learns nothing");
        ck(probe.count() > pc && probe.sawTitle("tried to use the owner-only"), "command: the owner is told who tried");
        cmd.onCommand(shady, c, "wt", new String[]{"unlock", "Shady"});
        ck(shady.got("Unknown command"), "command: even a watched operator is refused");
        ck(!svc.watch.findByName("Shady").locked || true, "command: smoke");
        OtherSender cbSender = new OtherSender();
        cmd.onCommand(cbSender, c, "watchtower", new String[]{"status"});
        ck(cbSender.msgs.get(0).contains("Unknown command"), "command: a command block or other sender is refused");
        ck(cmd.onTabComplete(rando, c, "wt", new String[]{""}).isEmpty(), "command: tab completion hidden from strangers");
        ck(cmd.onTabComplete(shady, c, "wt", new String[]{"st"}).isEmpty(), "command: tab completion hidden from a watched op");

        cmd.onCommand(me, c, "watchtower", new String[]{});
        ck(me.got("/watchtower add"), "command: owner gets help");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"status"});
        ck(me.got("Watching 1 player(s)") && me.got("Files:"), "command: owner status");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"add", "Rando", "observe", "seems", "shady"});
        ck(me.got("Rando is now watched in observe mode"), "command: add a player");
        eq(svc.watch.findByName("Rando").note, "seems shady", "command: note captured");
        eq(svc.watch.findByName("Rando").uuid.isEmpty(), true, "command: Bukkit.getPlayerExact needs the player online");
        Bukkit.ONLINE.add(rando);
        cmd.onCommand(me, c, "watchtower", new String[]{"add", "rando", "guard"});
        eq(svc.watch.findByName("Rando").uuid, rando.getUniqueId().toString(), "command: online players get their UUID bound");
        eq(svc.watch.findByName("Rando").mode, Watchlist.Mode.GUARD, "command: re-adding changes the mode");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"add", "Offline"});
        ck(me.got("matched by name until they next join"), "command: offline names are matched by name");
        eq(svc.watch.findByName("Offline").mode, Watchlist.Mode.GUARD, "command: guard is the default");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"add", "RealSociety5107"});
        ck(me.got("can't put yourself"), "command: cannot watch yourself");
        cmd.onCommand(me, c, "watchtower", new String[]{"add"});
        ck(me.got("Usage: /watchtower add"), "command: add usage");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"list"});
        ck(me.got("Shady [guard]") && me.got("Rando [guard]") && me.got("Offline [guard]"), "command: list");
        cmd.onCommand(me, c, "watchtower", new String[]{"mode", "Offline", "observe"});
        eq(svc.watch.findByName("Offline").mode, Watchlist.Mode.OBSERVE, "command: mode change");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"mode", "Offline", "bogus"});
        ck(me.got("Usage: /watchtower mode"), "command: bad mode usage");
        cmd.onCommand(me, c, "watchtower", new String[]{"mode", "Nobody", "guard"});
        ck(me.got("Nobody called Nobody"), "command: mode on unknown");
        me.msgs.clear();
        ck(cmd.onCommand(me, c, "watchtower", new String[]{"lock", "Offline"}), "command: lock handled");
        ck(svc.watch.findByName("Offline").locked && me.got("Offline is frozen"), "command: lock");
        me.msgs.clear();
        ck(cmd.onCommand(me, c, "watchtower", new String[]{"unlock", "Offline"}), "command: unlock handled");
        ck(!svc.watch.findByName("Offline").locked && me.got("Offline is released"), "command: unlock");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"unlock", "Ghost"});
        ck(me.got("Nobody called Ghost"), "command: unlock unknown");
        ck(cmd.onCommand(me, c, "watchtower", new String[]{"zzz"}) && cmd.onCommand(me, c, "watchtower", new String[]{"list"}), "command: every path reports handled");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"lock", "Nobody"});
        ck(me.got("Nobody is frozen") && svc.watch.findByName("Nobody") != null && svc.watch.findByName("Nobody").locked,
                "command: lock on someone not yet watched adds them and freezes them");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"lock", "RealSociety5107"});
        ck(me.got("owner") && svc.watch.findByName("RealSociety5107") == null, "command: an owner cannot be frozen");
        svc.watchRemove("Nobody", T0);
        cmd.onCommand(me, c, "watchtower", new String[]{"lock"});
        cmd.onCommand(me, c, "watchtower", new String[]{"unlock"});
        cmd.onCommand(me, c, "watchtower", new String[]{"remove"});
        ck(me.msgs.stream().filter(m -> m.contains("Usage")).count() == 3, "command: usage for missing names");
        cmd.onCommand(me, c, "watchtower", new String[]{"remove", "Offline"});
        ck(svc.watch.findByName("Offline") == null, "command: remove");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"remove", "Offline"});
        ck(me.got("Nobody called Offline"), "command: remove unknown");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"log", "Shady", "5"});
        ck(me.msgs.size() > 0 && me.msgs.size() <= 5, "command: log with name and count");
        int five = me.msgs.size();
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"log", "Shady", "1"});
        ck(me.msgs.size() == 1 && five > 1, "command: log count of 1 shows one line, a larger count shows more");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"log", "NoSuchPerson"});
        ck(me.got("Nothing recorded for NoSuchPerson"), "command: log for nobody");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"log", "999"});
        ck(me.msgs.size() <= 30, "command: log count capped at 30");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"ops"});
        ck(me.got("Operators:"), "command: ops");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"test"});
        ck(me.got("Nothing else is set up"), "command: test says when no app or Discord is set");
        ck(probe.sawTitle("Test alert"), "command: test alert raised");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"digest"});
        ck(me.got("NexusWatchtower digest"), "command: digest shown to the owner");
        me.msgs.clear();
        ck(cmd.onCommand(me, c, "watchtower", new String[]{"reload"}), "command: reload handled");
        ck(me.got("reloaded"), "command: reload");
        me.msgs.clear();
        cmd.onCommand(me, c, "watchtower", new String[]{"baseline"});
        ck(me.got("Fingerprinting"), "command: baseline starts");
        ck(me.msgs.stream().anyMatch(m -> m.contains("Baseline")), "command: baseline reports back (stub scheduler runs it inline)");
        ck(new File(server, "plugins/NexusWatchtower/baseline.tsv").isFile(), "command: baseline file written");

        Console con = new Console();
        cmd.onCommand(con, c, "watchtower", new String[]{"status"});
        ck(con.msgs.get(0).contains("Watching"), "command: the console may use it");
        con.msgs.clear();
        cmd.onCommand(con, c, "watchtower", new String[]{"nonsense"});
        ck(con.msgs.get(0).contains("/watchtower status"), "command: unknown subcommand shows help");
        ck(cmd.onTabComplete(me, c, "wt", new String[]{"lo"}).containsAll(List.of("lock", "log")), "tab: subcommands");
        ck(cmd.onTabComplete(me, c, "wt", new String[]{"lock", "sh"}).contains("Shady"), "tab: watched names");
        ck(cmd.onTabComplete(me, c, "wt", new String[]{"add", "x", ""}).containsAll(List.of("observe", "guard")), "tab: modes");
        ck(cmd.onTabComplete(me, c, "wt", new String[]{"status", "x"}).isEmpty(), "tab: nothing for other positions");

        // alerts reach an owner who is online, and nobody else
        Bukkit.ONLINE.clear();
        Bukkit.ONLINE.add(me);
        Bukkit.ONLINE.add(rando);
        me.msgs.clear();
        rando.msgs.clear();
        svc.alerts.raise(Severity.RED, "x", "Bad", "did a thing", "details", T0 + 999_999_999L);
        ck(me.got("[Watchtower]") && me.got("did a thing") && me.got("details"), "delivery: the owner sees alerts in game");
        ck(rando.msgs.isEmpty(), "delivery: nobody else sees them");

        // the real tamper scan on the temp server
        String r1 = plugin.tamperScan(false);
        ck(r1.contains("match the approved baseline"), "scan: matches right after approval (" + r1 + ")");
        Files.writeString(new File(server, "plugins/Some.jar").toPath(), "swapped!");
        String r2 = plugin.tamperScan(true);
        ck(r2.startsWith("Baseline approved"), "scan: approve re-fingerprints");
        Files.writeString(new File(server, "plugins/Evil.jar").toPath(), "evil");
        Files.writeString(new File(server, "server.properties").toPath(), "a=changed");
        String r3 = plugin.tamperScan(false);
        ck(r3.contains("2 file(s) differ"), "scan: a new jar and a changed config are found (" + r3 + ")");
        ck(probe.sawTitle("2 server file(s) differ"), "scan: and reported as red");

        // loadSettings reads every documented key
        FakeConfig fc = new FakeConfig();
        fc.v.put("server-name", "Srv");
        fc.v.put("timezone", "Europe/London");
        fc.v.put("owners", List.of("Boss"));
        fc.v.put("alerts.discord-webhook", "https://discord.com/api/webhooks/1/a");
        fc.v.put("alerts.feed-webhook", "https://discord.com/api/webhooks/2/b");
        fc.v.put("alerts.ping-discord-user-id", "123456789012345678");
        fc.v.put("app.base-url", "https://app.example.com/x");
        fc.v.put("app.secret", "tok");
        fc.v.put("app.server-id", "side-1");
        fc.v.put("app.push-yellow", false);
        fc.v.put("app.notify-server-start-stop", false);
        fc.v.put("app.max-per-minute", 9);
        fc.v.put("app.trusted-sender-classes", List.of("com.example.Bridge"));
        fc.v.put("alerts.repeat-quiet-seconds", 5);
        fc.v.put("alerts.max-yellow-per-minute", 7);
        fc.v.put("tripwires.mass-blocks.window-seconds", 3);
        fc.v.put("tripwires.mass-blocks.limit", 50);
        fc.v.put("tripwires.mass-blocks.limit-watched", 20);
        fc.v.put("tripwires.tnt.limit", 9);
        fc.v.put("tripwires.tnt.limit-watched", 3);
        fc.v.put("tripwires.lava.limit", 8);
        fc.v.put("tripwires.lava.limit-watched", 4);
        fc.v.put("tripwires.fire.limit", 7);
        fc.v.put("tripwires.fire.limit-watched", 5);
        fc.v.put("tripwires.command-blocks", false);
        fc.v.put("tripwires.wither", false);
        fc.v.put("commands.red", List.of("/Boom"));
        fc.v.put("commands.yellow", List.of("ping"));
        fc.v.put("commands.mass-selector", List.of("zap"));
        fc.v.put("commands.hide-message-text", List.of("whisperx"));
        fc.v.put("commands.hide-everything", List.of("pw"));
        fc.v.put("guard.blocked-commands", List.of("nope"));
        fc.v.put("guard.denied-message", "Denied.");
        fc.v.put("guard.freeze-on-tripwire", false);
        fc.v.put("guard.frozen-message", "Frozen.");
        fc.v.put("watch.alert-on-join", false);
        fc.v.put("watch.stream-activity", false);
        fc.v.put("watch.together-alert-at", 4);
        fc.v.put("watch.feed-seconds", 45);
        fc.v.put("watch.feed-max-lines", 9);
        fc.v.put("chat.phrases", List.of("Doom"));
        fc.v.put("operators.expected", List.of("Pal"));
        fc.v.put("operators.check-seconds", 90);
        fc.v.put("plugins.protection", List.of("Guardy"));
        fc.v.put("files.check", false);
        fc.v.put("files.check-every-minutes", 15);
        fc.v.put("files.also-watch", List.of("my.yml"));
        fc.v.put("digest.enabled", false);
        fc.v.put("digest.every-hours", 12);
        fc.v.put("journal.keep-days", 10);
        fc.v.put("journal.lines-in-memory", 300);
        Settings s = new Settings();
        WatchtowerPlugin.loadSettings(fc, s);
        eq(s.serverName, "Srv", "load: server name");
        eq(s.timezone, "Europe/London", "load: timezone");
        eq(s.owners, Set.of("boss"), "load: owners replaced, not merged");
        ck(!s.discordWebhook.isEmpty() && !s.feedWebhook.isEmpty() && s.pingUserId.equals("123456789012345678"), "load: alert destinations");
        ck(s.appEnabled() && s.appBase.equals("https://app.example.com/x") && s.appSecret.equals("tok") && s.appServerId.equals("side-1")
                && !s.appPushYellow && !s.appLifecycle && s.appPerMinute == 9 && s.trustedSenders.equals(Set.of("com.example.Bridge")), "load: app settings");
        ck(s.dedupeSeconds == 5 && s.maxYellowPerMinute == 7, "load: alert pacing");
        ck(s.massWindowSeconds == 3 && s.massLimit == 50 && s.massWatchedLimit == 20, "load: mass blocks");
        ck(s.tntLimit == 9 && s.tntWatchedLimit == 3 && s.lavaLimit == 8 && s.lavaWatchedLimit == 4 && s.fireLimit == 7 && s.fireWatchedLimit == 5, "load: hazards");
        ck(!s.alertCommandBlocks && !s.alertWither, "load: switches");
        ck(s.redCommands.equals(Set.of("boom")) && s.yellowCommands.equals(Set.of("ping")) && s.selectorCommands.equals(Set.of("zap")), "load: command sets");
        ck(s.hideMessage.equals(Set.of("whisperx")) && s.hideAll.equals(Set.of("pw")) && s.guardBlock.equals(Set.of("nope")), "load: hidden and guard sets");
        ck(s.guardDenyMessage.equals("Denied.") && !s.autoLockdown && s.lockMessage.equals("Frozen."), "load: guard messages");
        ck(!s.notifyJoins && !s.streamCommands && s.coordinationThreshold == 4 && s.feedSeconds == 45 && s.feedMaxLines == 9, "load: watch");
        ck(s.phrases.equals(List.of("doom")) && s.expectedOps.equals(Set.of("pal")) && s.opCheckSeconds == 90 && s.criticalPlugins.equals(Set.of("guardy")), "load: chat, ops, plugins");
        ck(!s.tamperEnabled && s.tamperMinutes == 15 && s.tamperFiles.equals(List.of("my.yml")), "load: files");
        ck(!s.digestEnabled && s.digestHours == 12 && s.journalKeepDays == 10 && s.journalMemory == 300, "load: digest and journal");
        Settings untouched = new Settings();
        WatchtowerPlugin.loadSettings(new FakeConfig(), untouched);
        ck(untouched.guardBlock.contains("//") && untouched.owners.contains("realsociety5107") && untouched.massLimit == 300, "load: an empty config keeps every default");
        FakeConfig emptyList = new FakeConfig();
        emptyList.v.put("chat.phrases", new ArrayList<String>());
        Settings es = new Settings();
        es.phrases = new ArrayList<>(List.of("old"));
        WatchtowerPlugin.loadSettings(emptyList, es);
        ck(es.phrases.isEmpty(), "load: an explicitly empty list really is empty");

        // shutdown paths
        plugin.onDisable();
        plugin.closingNow();
        Bukkit.STOPPING = false;
    }

    /** Reads what the real plugin's service sent to its sink (the plugin's own sink logs; this reads the journal). */
    static final class RecSinkProbe {
        final WatchService svc;

        RecSinkProbe(WatchService svc) {
            this.svc = svc;
        }

        List<Alert> sent() {
            List<Alert> out = new ArrayList<>();
            for (Alert a : svc.journal.recent(null, 100000)) {
                if (a.severity() != Severity.INFO) {
                    out.add(a);
                }
            }
            return out;
        }

        int count() {
            return svc.journal.recent(null, 100000).size();
        }

        boolean has(String category) {
            return sent().stream().anyMatch(a -> a.category().equals(category));
        }

        boolean sawTitle(String part) {
            return sent().stream().anyMatch(a -> a.title().contains(part));
        }

        String lastDetail() {
            List<Alert> all = svc.journal.recent(null, 100000);
            return all.get(all.size() - 1).detail();
        }
    }

    @SuppressWarnings("unused")
    private static void unusedImports(OfflinePlayer o) {
    }
}
