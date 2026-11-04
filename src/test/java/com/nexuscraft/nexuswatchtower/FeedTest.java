package com.nexuscraft.nexuswatchtower;

import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Plain main, no JUnit. */
public final class FeedTest {

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

    static final String SECRET = "sEcReT-vAlUe-123";

    // ------------------------------------------------------------------ fixtures

    /** Builds the settings the way config.yml would, but skips Settings.clamp so a local http address can be used in tests. */
    static FeedConfig cfg(Map<String, Object> o) {
        Settings st = new Settings();
        if (o.containsKey("base-url")) {
            st.appBase = (String) o.get("base-url");
        }
        if (o.containsKey("webhook-secret")) {
            st.appSecret = (String) o.get("webhook-secret");
        }
        if (o.containsKey("server-id")) {
            st.appServerId = (String) o.get("server-id");
        }
        if (o.containsKey("batch-size")) {
            st.feedBatch = (Integer) o.get("batch-size");
        }
        if (o.containsKey("flush-seconds")) {
            st.feedFlushSeconds = (Integer) o.get("flush-seconds");
        }
        if (o.containsKey("debug")) {
            st.feedDebug = (Boolean) o.get("debug");
        }
        for (Map.Entry<String, Object> e : o.entrySet()) {
            if (e.getKey().startsWith("events.")) {
                st.feedEvents.put(e.getKey().substring(7), (Boolean) e.getValue());
            }
        }
        return FeedConfig.from(st);
    }

    static FeedConfig goodCfg() {
        Map<String, Object> m = new HashMap<>();
        m.put("webhook-secret", SECRET);
        return cfg(m);
    }

    static final class Rec implements FeedTransport {
        final List<String[]> calls = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        final Deque<Object> script = new ArrayDeque<>();
        String reply = "{\"status\":\"ingested\"}";

        @Override
        public Reply post(String url, String json, String secret, int timeoutSeconds) throws IOException {
            calls.add(new String[]{url, json, secret});
            timeouts.add(timeoutSeconds);
            Object next = script.isEmpty() ? (Object) 200 : script.poll();
            if (next instanceof IOException io) {
                throw io;
            }
            int code = (Integer) next;
            return new Reply(code, code == 200 ? reply : "body-" + code);
        }
    }

    static final class Logs implements Feed.Log {
        final List<String> info = new ArrayList<>();
        final List<String> warn = new ArrayList<>();

        @Override
        public void info(String s) {
            info.add(s);
        }

        @Override
        public void warn(String s) {
            warn.add(s);
        }

        String all() {
            return String.join("\n", info) + "\n" + String.join("\n", warn);
        }
    }

    static final class Rig {
        final Rec net = new Rec();
        final Logs logs = new Logs();
        final List<Long> slept = new ArrayList<>();
        final AtomicLong now = new AtomicLong(1_000_000L);
        final Feed feed;

        Rig(FeedConfig c) {
            feed = new Feed(c, net, logs, slept::add, now::get);
        }
    }

    static FeedEvent ev(String type, String who, String text) {
        return new FeedEvent(type, who, text, "", "");
    }

    static void load(Rig r, int n) {
        for (int i = 0; i < n; i++) {
            ck(r.feed.offer(ev("chat", "Steve", "m" + i)), "queued " + i);
        }
    }

    // ------------------------------------------------------------------ tiny JSON reader (tests only)

    static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') {
                return str();
            }
            int st = i;
            while (i < s.length() && ",]} \n".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String t = s.substring(st, i);
            if (t.equals("true")) {
                return Boolean.TRUE;
            }
            if (t.equals("false")) {
                return Boolean.FALSE;
            }
            return Double.parseDouble(t);
        }

        String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            i++;
            return b.toString();
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalStateException("expected " + c + " at " + i + " in " + s);
            }
            i++;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) {
        P p = new P(json);
        Object v = p.value();
        p.ws();
        if (p.i != json.length()) {
            throw new IllegalStateException("trailing text");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> events(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("events");
    }

    public static void main(String[] args) throws Exception {
        clean();
        json();
        config();
        configFile();
        feedQueue();
        feedSending();
        feedFailures();
        listener();
        wire();
        plugin();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ FeedClean

    static void clean() {
        eq(FeedClean.text("\u00a7aHello \u00a7l\u00a7nWorld\u00a7r!", 400), "Hello World!", "colour codes removed");
        eq(FeedClean.text("\u00a7x\u00a71\u00a72\u00a73\u00a74\u00a75\u00a76hi", 400), "hi", "hex colour codes removed");
        eq(FeedClean.text("trailing\u00a7", 400), "trailing", "dangling section sign removed");
        eq(FeedClean.text("a\nb\tc\u0000d\u007fe", 400), "a b c d e", "control characters become blanks");
        eq(FeedClean.text("a   b", 400), "a b", "runs of blanks shrink");
        eq(FeedClean.text("  padded  ", 400), "padded", "trimmed");
        eq(FeedClean.text(null, 400), "", "null is empty");
        eq(FeedClean.text("a\u2028b\u2029c", 400), "a b c", "line separators removed");
        eq(FeedClean.text("abcdef", 3), "abc", "capped");
        eq(FeedClean.text("abc", 3), "abc", "exactly at the cap is kept");
        eq(FeedClean.text("x".repeat(500), 400).length(), 400, "long text capped at 400");
        eq(FeedClean.cap("ab\ud83d\ude00", 3), "ab", "a cap never splits an emoji");
        eq(FeedClean.cap("ab\ud83d\ude00", 4), "ab\ud83d\ude00", "an emoji that fits is kept");

        eq(FeedClean.text("my ip is 192.168.1.20 ok", 400), "my ip is [ip] ok", "IPv4 hidden");
        eq(FeedClean.text("join 203.0.113.9:25565 now", 400), "join [ip] now", "IPv4 with port hidden");
        eq(FeedClean.text("ip 8.8.8.8.", 400), "ip [ip].", "IPv4 at end of sentence hidden");
        eq(FeedClean.text("/ban-ip 10.0.0.1", 400), "/ban-ip [ip]", "IPv4 in a command hidden");
        eq(FeedClean.text("a 1.1.1.1 and 2.2.2.2", 400), "a [ip] and [ip]", "two addresses");
        eq(FeedClean.text("fe80:0:0:0:1:2:3:4 here", 400), "[ip] here", "full IPv6 hidden");
        eq(FeedClean.text("addr 2001:db8::ff00:42:8329 x", 400), "addr [ip] x", "compressed IPv6 hidden");
        eq(FeedClean.text("lo ::1 end", 400), "lo [ip] end", "loopback IPv6 hidden");
        eq(FeedClean.text("version 1.20.4 is out", 400), "version 1.20.4 is out", "version numbers are not addresses");
        eq(FeedClean.text("meet at 10:30 or 10:30:15", 400), "meet at 10:30 or 10:30:15", "times are not addresses");
        eq(FeedClean.text("at 132 64 -88 world", 400), "at 132 64 -88 world", "coordinates are not addresses");
        eq(FeedClean.text("scores 3.5 4.5 5.5", 400), "scores 3.5 4.5 5.5", "decimals are not addresses");
        eq(FeedClean.text("v1.2.3.4abc", 400), "v1.2.3.4abc", "a number glued to letters is left alone");

        eq(FeedClean.name("Steve"), "Steve", "normal name");
        eq(FeedClean.name(".RealSociety5107"), ".RealSociety5107", "bedrock dot kept");
        eq(FeedClean.name("Bad Name!"), "Bad_Name_", "bad characters become underscores");
        eq(FeedClean.name("Ab"), "Ab_", "short name padded to 3");
        eq(FeedClean.name(""), "___", "empty name padded");
        eq(FeedClean.name(null), "___", "null name padded");
        eq(FeedClean.name("A".repeat(30)), "A".repeat(17), "long name cut to 17");
        eq(FeedClean.name("a.b_c9"), "a.b_c9", "dots, underscores and digits are fine");
        ck(FeedClean.name("Zoë Ünï").matches("^[A-Za-z0-9_.]{3,17}$"), "result always matches the app's rule");

        Map<String, Integer> hide = new HashMap<>(Map.of("login", 0, "nexusgate", 0, "l", 0, "msg", 1, "r", 0));
        eq(FeedClean.command("/gamemode creative", hide), "/gamemode creative", "ordinary command unchanged");
        eq(FeedClean.command("/login hunter2", hide), "/login [hidden]", "login password hidden");
        eq(FeedClean.command("/LOGIN Hunter2", hide), "/LOGIN [hidden]", "any capitals");
        eq(FeedClean.command("/nexusgate:login 123456", hide), "/nexusgate:login [hidden]", "plugin prefix handled");
        eq(FeedClean.command("/nexusgate setpassword java 482915", hide), "/nexusgate [hidden]", "nexusgate arguments hidden");
        eq(FeedClean.command("/l abc", hide), "/l [hidden]", "short alias hidden");
        eq(FeedClean.command("/login", hide), "/login", "no arguments, nothing to hide");
        eq(FeedClean.command("/loginx abc", hide), "/loginx abc", "a different command is not hidden");
        eq(FeedClean.command("/login   a   b", hide), "/login [hidden]", "extra spaces");
        eq(FeedClean.command("/msg Bob hello there", hide), "/msg Bob [hidden]", "private message: the person is shown, the text is not");
        eq(FeedClean.command("/MSG Bob hello", hide), "/MSG Bob [hidden]", "any capitals");
        eq(FeedClean.command("/minecraft:msg Bob hi", hide), "/minecraft:msg Bob [hidden]", "plugin prefix handled");
        eq(FeedClean.command("/msg Bob", hide), "/msg Bob", "nothing after the person: nothing to hide");
        eq(FeedClean.command("/msg", hide), "/msg", "bare command");
        eq(FeedClean.command("/r thanks a lot", hide), "/r [hidden]", "a reply has no person, so all of it is hidden");
        eq(FeedClean.command("home", hide), "/home", "a missing slash is added");
        eq(FeedClean.command("", hide), "", "empty stays empty");
        eq(FeedClean.command("/tp 1.2.3.4", hide), "/tp [ip]", "addresses hidden in commands");
        eq(FeedClean.command("/say \u00a7ahi", hide), "/say hi", "colour codes removed in commands");
    }

    // ------------------------------------------------------------------ FeedJson

    static void json() {
        eq(FeedJson.quote("a\"b\\c"), "\"a\\\"b\\\\c\"", "quote and backslash escaped");
        eq(FeedJson.quote("l1\nl2\r\t"), "\"l1\\nl2\\r\\t\"", "newline, return, tab escaped");
        eq(FeedJson.quote("\u0001"), "\"\\u0001\"", "control character escaped");
        eq(FeedJson.quote("\u007f\u2028"), "\"\\u007f\\u2028\"", "delete and line separator escaped");
        eq(FeedJson.quote("caf\u00e9 \ud83d\ude00"), "\"caf\u00e9 \ud83d\ude00\"", "ordinary and emoji text kept");

        List<FeedEvent> evs = List.of(
                new FeedEvent("chat", "RealSociety5107", "hello \"world\"", "", ""),
                new FeedEvent("player_join", "Notch", "", "", ""),
                new FeedEvent("block_break", "Notch", "broke it", "minecraft:diamond_ore", "world 132 64 -88"));
        String body = FeedJson.batch("main", evs);
        Map<String, Object> m = parse(body);
        eq(m.get("server_id"), "main", "server id");
        eq(m.get("source"), "plugin", "source");
        eq(events(m).size(), 3, "three events");
        eq(events(m).get(0).get("event_type"), "chat", "type");
        eq(events(m).get(0).get("player_name"), "RealSociety5107", "player");
        eq(events(m).get(0).get("description"), "hello \"world\"", "description round-trips");
        ck(!events(m).get(1).containsKey("description"), "empty description left out");
        ck(!events(m).get(1).containsKey("target_name") && !events(m).get(1).containsKey("location"), "empty target and location left out");
        eq(events(m).get(2).get("target_name"), "minecraft:diamond_ore", "target");
        eq(events(m).get(2).get("location"), "world 132 64 -88", "location");
        ck(!body.contains("fire_kai"), "fire_kai is never sent");
        eq(parse(FeedJson.batch("x\"y", List.of())).get("server_id"), "x\"y", "server id escaped");
        eq(events(parse(FeedJson.batch("main", List.of()))).size(), 0, "empty batch is a valid empty list");
    }

    // ------------------------------------------------------------------ config

    static void config() {
        FeedConfig d = cfg(new HashMap<>());
        eq(d.baseUrl, "https://kai-core-genesis.base44.app", "default address");
        eq(d.endpoint, "https://kai-core-genesis.base44.app/functions/ingestGameEvent", "endpoint");
        eq(d.serverId, "main", "default server id");
        eq(d.batchSize, 40, "default batch size");
        eq(d.flushSeconds, 2, "default flush seconds");
        ck(!d.debug, "debug off");
        ck(!d.hasSecret(), "no secret by default");
        eq(d.urlProblem, null, "default address is fine");
        for (String t : List.of("chat", "player_join", "player_quit", "player_death", "mob_kill", "player_kill",
                "block_break", "block_place", "command", "advancement")) {
            ck(d.enabled(t), t + " on by default");
        }
        ck(!d.enabled("damage") && !d.enabled("item_pickup"), "damage and pickup off by default");
        ck(!d.enabled("nonsense"), "unknown type is off");
        eq(d.events.size(), 12, "twelve event types");
        eq(d.hideCommands.get("login"), 0, "login arguments hidden by default");
        eq(d.hideCommands.get("nexusgate"), 0, "nexusgate arguments hidden by default");
        eq(d.hideCommands.get("setpassword"), 0, "setpassword hidden by default");
        eq(d.hideCommands.get("msg"), 1, "private messages: person shown, text hidden");
        eq(d.hideCommands.get("r"), 0, "replies: all hidden");
        eq(d.hideCommands.get("reply"), 0, "replies: all hidden (long form)");
        eq(d.hideCommands.get("tell"), 1, "tell");

        Map<String, Object> m = new HashMap<>();
        m.put("batch-size", 500);
        m.put("flush-seconds", 0);
        eq(cfg(m).batchSize, 50, "batch size never above 50");
        eq(cfg(m).flushSeconds, 1, "flush at least every... no faster than 1 s");
        m.put("batch-size", 0);
        m.put("flush-seconds", 9999);
        eq(cfg(m).batchSize, 1, "batch size at least 1");
        eq(cfg(m).flushSeconds, 60, "flush at most 60 s");
        m.put("batch-size", 50);
        eq(cfg(m).batchSize, 50, "50 is allowed");

        m.clear();
        m.put("base-url", "https://example.com///");
        eq(cfg(m).baseUrl, "https://example.com", "trailing slashes trimmed");
        eq(cfg(m).endpoint, "https://example.com/functions/ingestGameEvent", "endpoint from trimmed address");
        m.put("base-url", "http://example.com");
        ck(cfg(m).urlProblem != null && cfg(m).urlProblem.contains("https"), "plain http to a real host refused");
        m.put("base-url", "http://localhost:8080");
        eq(cfg(m).urlProblem, null, "plain http to localhost allowed (testing)");
        m.put("base-url", "http://127.0.0.1:8080");
        eq(cfg(m).urlProblem, null, "plain http to 127.0.0.1 allowed (testing)");
        m.put("base-url", "ftp://example.com");
        ck(cfg(m).urlProblem != null, "other schemes refused");
        m.put("base-url", "");
        ck(cfg(m).urlProblem != null, "empty address refused");
        m.put("base-url", "not a url");
        ck(cfg(m).urlProblem != null, "nonsense address refused");
        m.put("base-url", "https://");
        ck(cfg(m).urlProblem != null, "no host refused");
        m.put("base-url", "  https://example.com  ");
        eq(cfg(m).baseUrl, "https://example.com", "spaces trimmed");

        m.clear();
        m.put("webhook-secret", "  abc  ");
        eq(cfg(m).secret, "abc", "secret trimmed");
        ck(cfg(m).hasSecret(), "has secret");
        m.put("server-id", "  ");
        eq(cfg(m).serverId, "main", "blank server id becomes main");
        m.put("server-id", "survival");
        eq(cfg(m).serverId, "survival", "custom server id");
        m.put("events.chat", false);
        m.put("events.damage", true);
        ck(!cfg(m).enabled("chat") && cfg(m).enabled("damage"), "event switches read");
        Settings custom = new Settings();
        custom.hideAll = new java.util.LinkedHashSet<>(List.of("foo"));
        custom.hideMessage = new java.util.LinkedHashSet<>(List.of("pm", "reply"));
        FeedConfig cc = FeedConfig.from(custom);
        eq(cc.hideCommands.get("foo"), 0, "commands.hide-everything feeds the hide list");
        eq(cc.hideCommands.get("pm"), 1, "commands.hide-message-text feeds the hide list (person shown)");
        eq(cc.hideCommands.get("reply"), 0, "reply has no person to show");
        eq(cc.hideCommands.get("login"), 0, "built-in password commands stay hidden even if the list is changed");
        eq(cc.hideCommands.get("msg"), null, "a command taken off the list is not hidden");
        m.put("debug", true);
        ck(cfg(m).debug, "debug read");
    }

    static void configFile() {
        FakeConfig fc = new FakeConfig(new HashMap<>());
        Settings st = new Settings();
        WatchtowerPlugin.loadSettings(fc, st);
        ck(st.feedEnabled, "feed on by default");
        eq(st.feedBatch, 40, "default batch");
        eq(st.feedFlushSeconds, 2, "default flush");
        ck(!st.feedDebug, "default debug off");
        eq(st.feedEvents.size(), 0, "no event switches set means defaults apply");
        ck(FeedConfig.from(st).enabled("chat") && !FeedConfig.from(st).enabled("damage"), "defaults: chat on, damage off");

        fc.v.put("feed.enabled", false);
        fc.v.put("feed.batch-size", 25);
        fc.v.put("feed.flush-seconds", 5);
        fc.v.put("feed.debug", true);
        fc.v.put("feed.events.chat", false);
        fc.v.put("feed.events.damage", true);
        fc.v.put("app.secret", "  abc  ");
        Settings st2 = new Settings();
        WatchtowerPlugin.loadSettings(fc, st2);
        ck(!st2.feedEnabled, "feed.enabled read");
        eq(st2.feedBatch, 25, "feed.batch-size read");
        eq(st2.feedFlushSeconds, 5, "feed.flush-seconds read");
        ck(st2.feedDebug, "feed.debug read");
        FeedConfig f2 = FeedConfig.from(st2);
        ck(!f2.enabled("chat") && f2.enabled("damage") && f2.enabled("command"), "feed.events read, others keep their defaults");
        eq(f2.secret, "abc", "the secret comes from app.secret");
        eq(f2.baseUrl, "https://kai-core-genesis.base44.app", "the address comes from app.base-url");
        eq(f2.batchSize, 25, "batch from settings");

        // an address that is not https is blanked by Watchtower's own settings check, and then nothing is sent
        fc.v.put("app.base-url", "http://insecure.example");
        Settings st3 = new Settings();
        WatchtowerPlugin.loadSettings(fc, st3);
        ck(FeedConfig.from(st3).urlProblem != null, "http address: the feed refuses to send");
    }

    // ------------------------------------------------------------------ queue

    static void feedQueue() {
        Rig r = new Rig(goodCfg());
        ck(r.feed.offer(ev("chat", "Steve", "hi")), "enabled event queued");
        ck(!r.feed.offer(ev("damage", "Steve", "ouch")), "switched-off event not queued");
        ck(!r.feed.offer(ev("mystery", "Steve", "?")), "unknown type not queued");
        ck(r.feed.force(ev("damage", "Steve", "forced")), "force ignores the switches");
        eq(r.feed.queued(), 2, "two waiting");
        eq(r.feed.accepted(), 2L, "two accepted");
        eq(r.feed.snapshot().get(0).description(), "hi", "oldest first");
        eq(r.feed.dropped(), 0L, "none dropped");

        Rig f = new Rig(goodCfg());
        for (int i = 0; i < Feed.QUEUE_LIMIT; i++) {
            f.feed.offer(ev("chat", "Steve", "m"));
        }
        eq(f.feed.queued(), Feed.QUEUE_LIMIT, "queue holds 5000");
        ck(!f.feed.offer(ev("chat", "Steve", "overflow")), "full queue refuses at once");
        ck(!f.feed.offer(ev("chat", "Steve", "overflow2")), "and again");
        eq(f.feed.dropped(), 2L, "overflow counted");
        eq(f.logs.warn.size(), 1, "one warning, not one per event");
        ck(f.logs.warn.get(0).contains("queue is full"), "warning text");
        f.now.addAndGet(59_999L);
        f.feed.offer(ev("chat", "Steve", "x"));
        eq(f.logs.warn.size(), 1, "still quiet just under a minute later");
        f.now.addAndGet(1L);
        f.feed.offer(ev("chat", "Steve", "x"));
        eq(f.logs.warn.size(), 2, "warns again after a minute");

        // the listener thread is never blocked: a hundred thousand offers into a full queue finish quickly
        long t0 = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            f.feed.offer(ev("chat", "Steve", "x"));
        }
        ck((System.nanoTime() - t0) / 1_000_000L < 2000L, "offering never waits");
    }

    // ------------------------------------------------------------------ sending

    static void feedSending() {
        Rig r = new Rig(goodCfg());
        r.net.reply = "{\"status\":\"ingested\",\"count\":3,\"pushed\":2,\"results\":[]}";
        load(r, 3);
        r.feed.flushAll(false);
        eq(r.net.calls.size(), 1, "one request for three events");
        eq(r.net.calls.get(0)[0], "https://kai-core-genesis.base44.app/functions/ingestGameEvent", "to the ingest endpoint");
        eq(r.net.calls.get(0)[2], SECRET, "secret passed to the transport");
        eq(r.net.timeouts.get(0), 20, "20 second timeout");
        Map<String, Object> body = parse(r.net.calls.get(0)[1]);
        eq(events(body).size(), 3, "all three in the body");
        eq(events(body).get(0).get("description"), "m0", "order kept");
        eq(events(body).get(2).get("description"), "m2", "order kept (last)");
        eq(r.feed.queued(), 0, "queue empty");
        eq(r.feed.sent(), 3L, "sent counted");
        eq(r.feed.pushed(), 2L, "phone pushes counted from the reply");
        eq(r.feed.lastHttp(), 200, "last code");
        eq(r.feed.lastError(), "", "no error");
        eq(r.feed.lastOkAt(), r.now.get(), "last success time");
        eq(r.slept.size(), 0, "no waiting when all is well");
        r.feed.flushAll(false);
        eq(r.net.calls.size(), 1, "nothing queued: no request");

        // batch sizes
        Rig b = new Rig(goodCfg());
        load(b, 95);
        b.feed.flushAll(false);
        eq(b.net.calls.size(), 3, "95 events at 40 per request: 3 requests");
        eq(events(parse(b.net.calls.get(0)[1])).size(), 40, "first batch 40");
        eq(events(parse(b.net.calls.get(1)[1])).size(), 40, "second batch 40");
        eq(events(parse(b.net.calls.get(2)[1])).size(), 15, "third batch 15");
        eq(events(parse(b.net.calls.get(2)[1])).get(0).get("description"), "m80", "no event skipped or repeated");

        Map<String, Object> big = new HashMap<>();
        big.put("webhook-secret", SECRET);
        big.put("batch-size", 80);
        Rig c = new Rig(cfg(big));
        load(c, 120);
        c.feed.flushAll(false);
        for (String[] call : c.net.calls) {
            ck(events(parse(call[1])).size() <= 50, "never more than 50 in a request");
        }
        eq(c.net.calls.size(), 3, "120 events at 50 per request: 3 requests");

        // a flush does at most 20 requests so one burst cannot hold the thread forever
        Map<String, Object> one = new HashMap<>();
        one.put("webhook-secret", SECRET);
        one.put("batch-size", 1);
        Rig d = new Rig(cfg(one));
        load(d, 25);
        d.feed.flushAll(false);
        eq(d.net.calls.size(), Feed.MAX_BATCHES_PER_FLUSH, "at most 20 requests per flush");
        eq(d.feed.queued(), 5, "the rest wait for the next flush");
        d.feed.flushAll(false);
        eq(d.net.calls.size(), 25, "next flush finishes");

        // debug line shows counts only
        Map<String, Object> dbg = new HashMap<>();
        dbg.put("webhook-secret", SECRET);
        dbg.put("debug", true);
        Rig e = new Rig(cfg(dbg));
        load(e, 2);
        e.feed.flushAll(false);
        eq(e.logs.info.size(), 1, "one debug line per request");
        ck(e.logs.info.get(0).contains("2 event"), "debug line says how many");
        Rig quiet = new Rig(goodCfg());
        load(quiet, 2);
        quiet.feed.flushAll(false);
        eq(quiet.logs.info.size(), 0, "no per-request lines without debug");

        // final run: one quick try
        Rig fin = new Rig(goodCfg());
        load(fin, 1);
        fin.net.script.add(500);
        fin.feed.flushAll(true);
        eq(fin.net.calls.size(), 1, "shutdown flush tries once");
        eq(fin.net.timeouts.get(0), 5, "and with a short timeout");
        eq(fin.slept.size(), 0, "and never waits");
        eq(fin.feed.dropped(), 1L, "a failed shutdown flush drops the batch");
    }

    // ------------------------------------------------------------------ failures

    static void feedFailures() throws Exception {
        List<Logs> allLogs = new ArrayList<>();

        // 429 then success
        Rig a = new Rig(goodCfg());
        allLogs.add(a.logs);
        load(a, 2);
        a.net.script.addAll(List.of(429, 429, 200));
        a.feed.flushAll(false);
        eq(a.net.calls.size(), 3, "429 twice then ok: 3 requests");
        eq(a.slept, List.of(1000L, 2000L), "backs off 1 s then 2 s");
        eq(a.net.calls.get(0)[1], a.net.calls.get(2)[1], "the retry sends the same batch, no more, no less");
        eq(a.feed.sent(), 2L, "counted once");
        eq(a.feed.dropped(), 0L, "nothing lost");
        eq(a.feed.lastHttp(), 200, "last code ok");
        eq(a.feed.lastError(), "", "error cleared on success");

        // 5xx all the way
        Rig b = new Rig(goodCfg());
        allLogs.add(b.logs);
        load(b, 4);
        b.net.script.addAll(List.of(503, 500, 502, 504));
        b.feed.flushAll(false);
        eq(b.net.calls.size(), 4, "first try plus three retries, no more");
        eq(b.slept, List.of(1000L, 2000L, 4000L), "backoff 1, 2, 4 s");
        eq(b.feed.dropped(), 4L, "batch dropped after giving up");
        eq(b.feed.lastHttp(), 504, "last code");
        eq(b.feed.lastError(), "HTTP 504", "last error");
        ck(b.logs.warn.stream().anyMatch(w -> w.contains("Giving up")), "says it gave up");
        eq(b.feed.queued(), 0, "nothing left");

        // network errors count as retryable
        Rig c = new Rig(goodCfg());
        allLogs.add(c.logs);
        load(c, 1);
        c.net.script.addAll(List.of(new IOException("connection reset"), new IOException("timed out"), 200));
        c.feed.flushAll(false);
        eq(c.net.calls.size(), 3, "network errors retried");
        eq(c.feed.sent(), 1L, "then sent");
        Rig c2 = new Rig(goodCfg());
        allLogs.add(c2.logs);
        load(c2, 1);
        c2.net.script.addAll(List.of(new IOException("x"), new IOException("x"), new IOException("x"), new IOException("x")));
        c2.feed.flushAll(false);
        eq(c2.net.calls.size(), 4, "network errors: 4 tries then stop");
        ck(c2.feed.lastError().startsWith("network problem"), "network error text: " + c2.feed.lastError());
        ck(!c2.feed.lastError().contains("connection"), "error text does not echo the exception message");

        // 401 stops everything and says so once
        Rig d = new Rig(goodCfg());
        allLogs.add(d.logs);
        load(d, 90);
        d.net.script.add(401);
        d.feed.flushAll(false);
        ck(d.feed.halted(), "401 halts sending");
        eq(d.net.calls.size(), 1, "no retry after 401 and no further batches");
        eq(d.feed.queued(), 0, "queue emptied so memory is not held");
        eq(d.feed.dropped(), 90L, "all 90 counted as dropped");
        eq(d.logs.warn.size(), 1, "one loud warning");
        ck(d.logs.warn.get(0).contains("401") && d.logs.warn.get(0).contains("STOPPED"), "warning says 401 and STOPPED");
        ck(d.logs.warn.get(0).contains("/watchtower reload") && !d.logs.warn.get(0).contains("nexusfeed"), "warning says how to fix it");
        eq(d.feed.lastHttp(), 401, "last code 401");
        ck(!d.feed.offer(ev("chat", "Steve", "late")), "events offered while stopped are refused");
        eq(d.feed.dropped(), 91L, "and counted as dropped");
        eq(d.feed.queued(), 0, "and not queued");
        d.feed.flushAll(false);
        d.feed.flushAll(false);
        eq(d.net.calls.size(), 1, "no more requests while stopped");
        eq(d.logs.warn.size(), 1, "and no more log lines (no spam)");
        d.feed.reconfigure(goodCfg());
        ck(!d.feed.halted(), "reload clears the stop");
        ck(d.feed.offer(ev("chat", "Steve", "again")), "events are accepted again");
        d.feed.flushAll(false);
        eq(d.net.calls.size(), 2, "and sent again");
        eq(d.feed.lastError(), "", "error cleared");

        // other 4xx: no retry, dropped, quiet
        Rig e = new Rig(goodCfg());
        allLogs.add(e.logs);
        load(e, 2);
        e.net.script.add(400);
        e.feed.flushAll(false);
        eq(e.net.calls.size(), 1, "400 is not retried");
        eq(e.slept.size(), 0, "no waiting");
        eq(e.feed.dropped(), 2L, "dropped");
        ck(e.logs.warn.get(0).contains("HTTP 400"), "warning has the code");
        ck(!e.feed.halted(), "400 does not stop sending");
        load(e, 1);
        e.net.script.add(400);
        e.feed.flushAll(false);
        eq(e.logs.warn.size(), 1, "repeat 400 within a minute: no new line");
        e.now.addAndGet(60_000L);
        load(e, 1);
        e.net.script.add(400);
        e.feed.flushAll(false);
        eq(e.logs.warn.size(), 2, "after a minute: one more line");

        // 3xx is not success
        Rig e3 = new Rig(goodCfg());
        allLogs.add(e3.logs);
        load(e3, 2);
        e3.net.script.add(300);
        e3.feed.flushAll(false);
        eq(e3.feed.sent(), 0L, "300 is not counted as sent");
        eq(e3.feed.dropped(), 2L, "300 drops the batch");
        eq(e3.net.calls.size(), 1, "and is not retried");

        // empty secret: nothing sent, events wait, one warning
        Rig f = new Rig(cfg(new HashMap<>()));
        allLogs.add(f.logs);
        load(f, 3);
        f.feed.flushAll(false);
        f.feed.flushAll(false);
        eq(f.net.calls.size(), 0, "no secret: no requests");
        eq(f.feed.queued(), 3, "events wait");
        eq(f.logs.warn.size(), 1, "one warning");
        ck(f.logs.warn.get(0).contains("webhook-secret"), "names the setting");
        ck(f.feed.lastError().contains("webhook-secret"), "status shows why");
        Map<String, Object> withSecret = new HashMap<>();
        withSecret.put("webhook-secret", SECRET);
        f.feed.reconfigure(cfg(withSecret));
        f.feed.flushAll(false);
        eq(f.net.calls.size(), 1, "after a reload with the secret the waiting events go out");
        eq(events(parse(f.net.calls.get(0)[1])).size(), 3, "all three");

        // bad address: nothing sent
        Map<String, Object> bad = new HashMap<>();
        bad.put("webhook-secret", SECRET);
        bad.put("base-url", "http://example.com");
        Rig g = new Rig(cfg(bad));
        allLogs.add(g.logs);
        load(g, 1);
        g.feed.flushAll(false);
        eq(g.net.calls.size(), 0, "plain http to a real host: the secret is never sent");
        ck(g.feed.lastError().contains("https"), "status shows why");
        g.feed.reconfigure(cfg(bad));
        ck(g.logs.warn.stream().anyMatch(w -> w.contains("Not sending")), "reload warns about the bad address");

        // interrupted while waiting between tries
        Rig h = new Rig(goodCfg());
        allLogs.add(h.logs);
        Feed hf = new Feed(goodCfg(), h.net, h.logs, ms -> {
            throw new InterruptedException();
        }, h.now::get);
        hf.force(ev("chat", "Steve", "x"));
        hf.force(ev("chat", "Steve", "y"));
        h.net.script.add(500);
        hf.flushAll(false);
        ck(Thread.interrupted(), "interrupt flag is kept for the thread to see");
        eq(h.net.calls.size(), 1, "stops trying when interrupted");
        eq(hf.dropped(), 2L, "batch counted as dropped");

        // a listener problem is reported once a minute
        Rig p = new Rig(goodCfg());
        allLogs.add(p.logs);
        p.feed.problem("chat", new IllegalStateException("x"));
        p.feed.problem("chat", new IllegalStateException("x"));
        eq(p.logs.warn.size(), 1, "listener problems are rate limited");
        ck(p.logs.warn.get(0).contains("IllegalStateException"), "names the problem type");

        // the secret never appears in any log or status text
        for (Logs l : allLogs) {
            ck(!l.all().contains(SECRET), "secret never logged");
        }
        for (Rig rr : List.of(a, b, c, c2, d, e, f, g)) {
            ck(!rr.feed.lastError().contains(SECRET), "secret never in last error");
        }
    }

    // ------------------------------------------------------------------ listener

    static class TP extends Player {
        final String n;
        final UUID id;
        Player killer;
        final List<String> msgs = new ArrayList<>();
        boolean allowed = true;

        TP(String n) {
            this.n = n;
            this.id = UUID.nameUUIDFromBytes(("p:" + n).getBytes(StandardCharsets.UTF_8));
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
        public Player getKiller() {
            return killer;
        }

        @Override
        public boolean hasPermission(String node) {
            return allowed;
        }

        @Override
        public void sendMessage(String message) {
            msgs.add(message);
        }

        String all() {
            return String.join("\n", msgs);
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
        public void sendMessage(Component component) {
        }

        String all() {
            return String.join("\n", msgs);
        }
    }

    static FeedEvent only(Rig r) {
        List<FeedEvent> s = r.feed.snapshot();
        eq(s.size(), 1, "exactly one event expected but queue was " + s);
        return s.isEmpty() ? new FeedEvent("", "", "", "", "") : s.get(0);
    }

    static Block blockAt(Material m, int x, int y, int z) {
        Block b = new Block(new org.bukkit.World("world"), x, y, z);
        b.setType(m);
        return b;
    }

    static void listener() {
        Map<String, Object> all = new HashMap<>();
        all.put("webhook-secret", SECRET);
        all.put("events.damage", true);
        all.put("events.item_pickup", true);
        Rig r = new Rig(cfg(all));
        FeedListener l = new FeedListener(r.feed);
        TP steve = new TP("Steve");
        TP owner = new TP("RealSociety5107");
        TP op = new TP("TheOperator");
        op.setOp(true);
        TP bedrock = new TP(".RealSociety5107");

        eq(FeedListener.where(null), "", "no location: empty");
        eq(FeedListener.where(new org.bukkit.Location(null, 1, 2, 3)), "", "no world: empty");
        eq(FeedListener.where(new org.bukkit.Location(new org.bukkit.World("w"), 1.9, -2.5, 3)), "w 1 -3 3", "block coordinates");

        // chat -- from everybody, owner and operators included
        for (TP p : List.of(owner, op, bedrock, steve)) {
            r.feed.snapshot();
            l.onChat(new AsyncChatEvent(p, Component.text("hello \u00a7aworld")));
        }
        List<FeedEvent> chat = r.feed.snapshot();
        eq(chat.size(), 4, "chat from the owner, an operator, a Bedrock player and a normal player all recorded");
        eq(chat.get(0).type(), "chat", "type chat");
        eq(chat.get(0).player(), "RealSociety5107", "the owner's chat is reported");
        eq(chat.get(0).description(), "hello world", "message with colour codes cleaned");
        eq(chat.get(1).player(), "TheOperator", "an operator's chat is reported");
        eq(chat.get(2).player(), ".RealSociety5107", "Bedrock dot kept");
        l.onChat(new AsyncChatEvent(steve, Component.text("my ip is 1.2.3.4 ok")));
        eq(r.feed.snapshot().get(4).description(), "my ip is [ip] ok", "addresses in chat hidden");

        Rig j = new Rig(cfg(all));
        FeedListener lj = new FeedListener(j.feed);
        lj.onJoin(new PlayerJoinEvent(owner));
        FeedEvent je = only(j);
        eq(je.type(), "player_join", "join type");
        eq(je.player(), "RealSociety5107", "owner's join reported");
        eq(je.description(), "joined the game", "join text");

        Rig q = new Rig(cfg(all));
        new FeedListener(q.feed).onQuit(new PlayerQuitEvent(op));
        FeedEvent qe = only(q);
        eq(qe.type(), "player_quit", "quit type");
        eq(qe.player(), "TheOperator", "operator's quit reported");
        eq(qe.description(), "left the game (operator)", "quit text");
        eq(FeedListener.duration(0), "under a minute", "duration short");
        eq(FeedListener.duration(5 * 60000L), "5 min", "duration minutes");
        eq(FeedListener.duration(135 * 60000L), "2 h 15 min", "duration hours");

        // names are made to fit the app's rule
        Rig nm = new Rig(cfg(all));
        new FeedListener(nm.feed).onJoin(new PlayerJoinEvent(new TP("Ab")));
        eq(only(nm).player(), "Ab_", "short name padded");

        // death
        Rig d = new Rig(cfg(all));
        FeedListener ld = new FeedListener(d.feed);
        ld.onDeath(new PlayerDeathEvent(steve, List.of(), 0, Component.text("Steve was slain by Zombie")));
        FeedEvent de = only(d);
        eq(de.type(), "player_death", "death type");
        eq(de.player(), "Steve", "who died");
        eq(de.description(), "Steve was slain by Zombie", "death message");
        eq(de.location(), "world 0 64 0", "where");

        Rig d2 = new Rig(cfg(all));
        new FeedListener(d2.feed).onDeath(new PlayerDeathEvent(steve, List.of(), 0, null));
        eq(only(d2).description(), "died", "no death message: 'died'");

        Rig k = new Rig(cfg(all));
        TP victim = new TP("Victim");
        victim.killer = owner;
        new FeedListener(k.feed).onDeath(new PlayerDeathEvent(victim, List.of(), 0, Component.text("Victim was slain by RealSociety5107")));
        List<FeedEvent> ks = k.feed.snapshot();
        eq(ks.size(), 2, "a player killing a player gives a death and a kill");
        eq(ks.get(0).type(), "player_death", "first the death");
        eq(ks.get(1).type(), "player_kill", "then the kill");
        eq(ks.get(1).player(), "RealSociety5107", "kill credited to the killer, owner included");
        eq(ks.get(1).target(), "Victim", "kill target");
        eq(ks.get(1).description(), "killed Victim", "kill text");

        Rig sk = new Rig(cfg(all));
        TP self = new TP("Selfie");
        self.killer = self;
        new FeedListener(sk.feed).onDeath(new PlayerDeathEvent(self, List.of(), 0, null));
        eq(sk.feed.snapshot().size(), 1, "killing yourself is a death only");

        // mob kills
        Rig m = new Rig(cfg(all));
        FeedListener lm = new FeedListener(m.feed);
        lm.onMobDeath(new EntityDeathEvent(new Zombie() {
            @Override
            public Player getKiller() {
                return owner;
            }

            @Override
            public org.bukkit.Location getLocation() {
                return new org.bukkit.Location(new org.bukkit.World("nether"), 5, 70, -9);
            }
        }));
        FeedEvent me = only(m);
        eq(me.type(), "mob_kill", "mob kill type");
        eq(me.player(), "RealSociety5107", "credited to the killer");
        eq(me.target(), "minecraft:zombie", "target id");
        eq(me.description(), "killed zombie", "text");
        eq(me.location(), "nether 5 70 -9", "location");
        Rig m2 = new Rig(cfg(all));
        new FeedListener(m2.feed).onMobDeath(new EntityDeathEvent(new Zombie()));
        eq(m2.feed.snapshot().size(), 0, "a mob that nobody killed is not reported");
        new FeedListener(m2.feed).onMobDeath(new EntityDeathEvent(steve));
        eq(m2.feed.snapshot().size(), 0, "a player's death is not also a mob kill");
        new FeedListener(m2.feed).onMobDeath(new EntityDeathEvent(null));
        eq(m2.feed.snapshot().size(), 0, "no entity: nothing");

        // blocks
        Rig b = new Rig(cfg(all));
        FeedListener lb = new FeedListener(b.feed);
        lb.onBreak(new BlockBreakEvent(blockAt(Material.DIAMOND_ORE, 132, 64, -88), op));
        FeedEvent be = only(b);
        eq(be.type(), "block_break", "break type");
        eq(be.player(), "TheOperator", "operator's breaks reported");
        eq(be.target(), "minecraft:diamond_ore", "block id");
        eq(be.location(), "world 132 64 -88", "location");
        eq(be.description(), "broke minecraft:diamond_ore at world 132 64 -88", "text");
        Rig b2 = new Rig(cfg(all));
        new FeedListener(b2.feed).onPlace(new BlockPlaceEvent(blockAt(Material.STONE, 1, 2, 3), owner));
        FeedEvent pe = only(b2);
        eq(pe.type(), "block_place", "place type");
        eq(pe.target(), "minecraft:stone", "placed id");
        eq(pe.description(), "placed minecraft:stone at world 1 2 3", "place text");

        // commands
        Rig c = new Rig(cfg(all));
        FeedListener lc = new FeedListener(c.feed);
        lc.onCommand(new PlayerCommandPreprocessEvent(op, "/gamemode creative"));
        lc.onCommand(new PlayerCommandPreprocessEvent(owner, "/login hunter2"));
        lc.onCommand(new PlayerCommandPreprocessEvent(steve, "/nexusgate setpassword java 482915"));
        PlayerCommandPreprocessEvent denied = new PlayerCommandPreprocessEvent(steve, "/op Steve");
        denied.setCancelled(true);
        lc.onCommand(denied);
        List<FeedEvent> cs = c.feed.snapshot();
        eq(cs.size(), 4, "four commands");
        eq(cs.get(0).type(), "command", "command type");
        eq(cs.get(0).player(), "TheOperator", "operator command reported");
        eq(cs.get(0).description(), "/gamemode creative", "command text");
        eq(cs.get(1).description(), "/login [hidden]", "the password is hidden");
        eq(cs.get(2).description(), "/nexusgate [hidden]", "nexusgate arguments hidden");
        eq(cs.get(3).description(), "/op Steve (blocked)", "a command something blocked is still reported, marked");
        for (FeedEvent e : cs) {
            ck(!e.description().contains("hunter2") && !e.description().contains("482915"), "no password in " + e.description());
        }

        // advancements
        Rig a = new Rig(cfg(all));
        FeedListener la = new FeedListener(a.feed);
        la.onAdvancement(new PlayerAdvancementDoneEvent(steve, new Advancement(NamespacedKey.minecraft("story/mine_diamond"))));
        la.onAdvancement(new PlayerAdvancementDoneEvent(steve, new Advancement(NamespacedKey.minecraft("recipes/misc/stick"))));
        FeedEvent ae = only(a);
        eq(ae.type(), "advancement", "advancement type");
        eq(ae.description(), "earned story/mine_diamond", "advancement text; recipe unlocks skipped");
        eq(ae.target(), "minecraft:story/mine_diamond", "advancement id");

        // damage and pickup (switched on here)
        Rig dm = new Rig(cfg(all));
        FeedListener ldm = new FeedListener(dm.feed);
        ldm.onDamage(new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.FALL, 3.5));
        ldm.onDamage(new EntityDamageEvent(new Zombie(), EntityDamageEvent.DamageCause.FALL, 3.5));
        FeedEvent dme = only(dm);
        eq(dme.type(), "damage", "damage type");
        eq(dme.description(), "took 3.5 damage (FALL)", "damage text (mobs are not reported)");
        Rig pk = new Rig(cfg(all));
        FeedListener lpk = new FeedListener(pk.feed);
        Item item = new Item();
        ItemStack stack = new ItemStack(Material.EMERALD, 3);
        item.setItemStack(stack);
        lpk.onPickup(new EntityPickupItemEvent(steve, item));
        lpk.onPickup(new EntityPickupItemEvent(new Zombie(), item));
        FeedEvent pke = only(pk);
        eq(pke.type(), "item_pickup", "pickup type");
        eq(pke.description(), "picked up 3 x minecraft:emerald", "pickup text (mobs ignored)");

        // default: damage and pickup off
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("webhook-secret", SECRET);
        Rig off = new Rig(cfg(defaults));
        FeedListener lo = new FeedListener(off.feed);
        lo.onDamage(new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.FALL, 3.5));
        lo.onPickup(new EntityPickupItemEvent(steve, item));
        eq(off.feed.snapshot().size(), 0, "damage and pickup are quiet by default");

        // switches
        Map<String, Object> noChat = new HashMap<>(defaults);
        noChat.put("events.chat", false);
        noChat.put("events.command", false);
        Rig sw = new Rig(cfg(noChat));
        FeedListener lsw = new FeedListener(sw.feed);
        lsw.onChat(new AsyncChatEvent(steve, Component.text("x")));
        lsw.onCommand(new PlayerCommandPreprocessEvent(steve, "/home"));
        eq(sw.feed.snapshot().size(), 0, "switched-off types are not queued");
        lsw.onJoin(new PlayerJoinEvent(steve));
        eq(sw.feed.snapshot().size(), 1, "other types still are");

        // a broken event does not escape into the game
        Rig bad = new Rig(cfg(all));
        FeedListener lbad = new FeedListener(bad.feed);
        lbad.onBreak(new BlockBreakEvent(null, steve));
        lbad.onJoin(new PlayerJoinEvent(null));
        ck(bad.logs.warn.size() >= 0, "no exception escaped");
        eq(bad.feed.snapshot().size(), 0, "nothing queued for the broken events");
        ck(bad.logs.warn.stream().anyMatch(w -> w.contains("block break")), "problem reported: " + bad.logs.warn);

        // every handler: runs last, and only skips cancelled events where that is right
        Map<String, Boolean> expectIgnore = new LinkedHashMap<>();
        expectIgnore.put("onChat", true);
        expectIgnore.put("onJoin", false);
        expectIgnore.put("onQuit", false);
        expectIgnore.put("onDeath", false);
        expectIgnore.put("onMobDeath", false);
        expectIgnore.put("onBreak", true);
        expectIgnore.put("onPlace", true);
        expectIgnore.put("onCommand", false);
        expectIgnore.put("onAdvancement", false);
        expectIgnore.put("onDamage", true);
        expectIgnore.put("onPickup", true);
        int seen = 0;
        for (Method mt : FeedListener.class.getDeclaredMethods()) {
            EventHandler h = mt.getAnnotation(EventHandler.class);
            if (h == null) {
                continue;
            }
            seen++;
            eq(h.priority(), EventPriority.MONITOR, mt.getName() + " runs at MONITOR");
            eq(h.ignoreCancelled(), expectIgnore.get(mt.getName()), mt.getName() + " ignoreCancelled");
        }
        eq(seen, expectIgnore.size(), "all handlers checked");

        // the listener never filters by who the player is (isOp only labels join/leave lines, twice)
        try {
            String src = Files.readString(java.nio.file.Path.of("src/main/java/com/nexuscraft/nexuswatchtower/FeedListener.java"));
            ck(src.split("isOp\\(", -1).length - 1 == 2 && !src.contains("hasPermission(") && !src.toLowerCase().contains("owner."), "no player filtering in the listener");
        } catch (IOException ex) {
            ck(false, "could not read listener source");
        }
    }

    // ------------------------------------------------------------------ real web server

    static final class Hit {
        String method;
        String path;
        String contentType;
        String secret;
        String body;
    }

    static final class Srv {
        final HttpServer server;
        final List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
        final Deque<Integer> script = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final List<String> otherSecrets = Collections.synchronizedList(new ArrayList<>());
        volatile String expectSecret = SECRET;

        Srv() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/functions/ingestGameEvent", ex -> {
                Hit h = new Hit();
                h.method = ex.getRequestMethod();
                h.path = ex.getRequestURI().getPath();
                h.contentType = ex.getRequestHeaders().getFirst("Content-Type");
                h.secret = ex.getRequestHeaders().getFirst("x-kai-secret");
                h.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                hits.add(h);
                Integer forced = script.poll();
                int code = forced != null ? forced : (SECRET_OK(h) ? 200 : 401);
                byte[] out;
                if (code == 302) {
                    ex.getResponseHeaders().add("Location", "/elsewhere");
                    out = new byte[0];
                } else if (code == 200) {
                    int n = events(parse(h.body)).size();
                    out = ("{\"status\":\"ingested\",\"count\":" + n + ",\"pushed\":" + n + ",\"results\":[]}").getBytes(StandardCharsets.UTF_8);
                } else {
                    out = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
                }
                ex.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(out);
                    }
                }
                ex.close();
            });
            server.createContext("/elsewhere", ex -> {
                otherSecrets.add(String.valueOf(ex.getRequestHeaders().getFirst("x-kai-secret")));
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            server.start();
        }

        private boolean SECRET_OK(Hit h) {
            return expectSecret.equals(h.secret);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void waitFor(int n, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (hits.size() < n && System.currentTimeMillis() < end) {
                Thread.sleep(20);
            }
        }
    }

    static FeedConfig localCfg(Srv s, String secret) {
        Map<String, Object> m = new HashMap<>();
        m.put("base-url", s.url());
        m.put("webhook-secret", secret);
        return cfg(m);
    }

    static void wire() throws Exception {
        Srv s = new Srv();
        try {
            Logs logs = new Logs();
            Feed feed = new Feed(localCfg(s, SECRET), new FeedHttp(), logs, ms -> {
            }, System::currentTimeMillis);
            feed.offer(new FeedEvent("chat", "RealSociety5107", "hello \"world\" \u00e9 \ud83d\ude00", "", ""));
            feed.offer(new FeedEvent("player_join", "Notch", "joined the game", "", ""));
            feed.offer(new FeedEvent("block_break", "Notch", "broke it", "minecraft:diamond_ore", "world 132 64 -88"));
            feed.flushAll(false);
            eq(s.hits.size(), 1, "one real request for three events");
            Hit h = s.hits.get(0);
            eq(h.method, "POST", "POST");
            eq(h.path, "/functions/ingestGameEvent", "path");
            ck(h.contentType != null && h.contentType.startsWith("application/json"), "content type json: " + h.contentType);
            eq(h.secret, SECRET, "x-kai-secret header");
            Map<String, Object> body = parse(h.body);
            eq(body.get("server_id"), "main", "server_id on the wire");
            eq(body.get("source"), "plugin", "source on the wire");
            eq(events(body).size(), 3, "three events on the wire");
            eq(events(body).get(0).get("description"), "hello \"world\" \u00e9 \ud83d\ude00", "text survives the wire");
            eq(feed.lastHttp(), 200, "200");
            eq(feed.pushed(), 3L, "pushed count read from the real reply");
            ck(!h.body.contains(SECRET), "secret not in the body");

            // 429 twice then ok, through the real client
            s.script.addAll(List.of(429, 429));
            feed.offer(ev("chat", "Steve", "retry me"));
            feed.flushAll(false);
            eq(s.hits.size(), 4, "429, 429, then success");
            eq(s.hits.get(3).body, s.hits.get(1).body, "same body each time");

            // redirects are not followed: the secret goes nowhere else
            s.script.add(302);
            feed.offer(ev("chat", "Steve", "redirected"));
            feed.flushAll(false);
            eq(s.otherSecrets.size(), 0, "a redirect is not followed, so the secret is not sent to another place");
            eq(feed.dropped(), 1L, "redirect counts as a rejected batch");

            // 401 with a real server
            Srv wrong = new Srv();
            try {
                wrong.expectSecret = "something-else";
                Logs wl = new Logs();
                Feed wf = new Feed(localCfg(wrong, SECRET), new FeedHttp(), wl, ms -> {
                }, System::currentTimeMillis);
                wf.offer(ev("chat", "Steve", "x"));
                wf.flushAll(false);
                ck(wf.halted(), "real 401 halts");
                eq(wrong.hits.size(), 1, "one request");
                ck(!wl.all().contains(SECRET), "secret not logged on 401");
            } finally {
                wrong.server.stop(0);
            }

            // nothing listening: network error, retried, dropped, no secret in text
            int port;
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
                port = ss.getLocalPort();
            }
            Map<String, Object> dead = new HashMap<>();
            dead.put("base-url", "http://127.0.0.1:" + port);
            dead.put("webhook-secret", SECRET);
            Logs dl = new Logs();
            List<Long> slept = new ArrayList<>();
            Feed df = new Feed(cfg(dead), new FeedHttp(), dl, slept::add, System::currentTimeMillis);
            df.offer(ev("chat", "Steve", "x"));
            df.flushAll(false);
            eq(slept, List.of(1000L, 2000L, 4000L), "unreachable server: three backoffs");
            eq(df.dropped(), 1L, "then dropped");
            ck(!dl.all().contains(SECRET) && !df.lastError().contains(SECRET), "secret not in text");
        } finally {
            s.server.stop(0);
        }
    }

    // ------------------------------------------------------------------ whole plugin

    static final class FakeConfig extends FileConfiguration {
        final Map<String, Object> v;

        FakeConfig(Map<String, Object> v) {
            this.v = v;
        }

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

    static final class Capture extends Handler {
        final List<String> text = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord r) {
            text.add(r.getLevel() + " " + r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        String all() {
            synchronized (text) {
                return String.join("\n", text);
            }
        }
    }

    static void plugin() throws Exception {
        Logger log = Logger.getLogger("stub");
        log.setLevel(Level.ALL);
        log.setUseParentHandlers(false);
        Capture cap = new Capture();
        log.addHandler(cap);
        Srv s = new Srv();
        File server = Files.createTempDirectory("nw-feed-server").toFile();
        new File(server, "plugins").mkdirs();
        try {
            FakeConfig conf = new FakeConfig(new HashMap<>());
            JavaPlugin.TEST_CONFIG = conf;
            WatchtowerPlugin plugin = WatchTest.boot(server);
            Feed feed = plugin.feed();
            ck(feed != null, "the feed starts with the plugin");
            ck(plugin.statusLines().stream().anyMatch(l -> l.contains("Player-action feed")), "/watchtower status mentions the feed");
            ck(cap.all().contains("waiting: app.secret is empty"), "start-up says it is waiting for the secret: " + cap.all());
            // point the feed at a local fake app (Watchtower's own check only allows https addresses)
            feed.reconfigure(localCfg(s, "wrong-secret"));

            WatchCommand cmd = new WatchCommand(plugin, plugin.service());
            WatchTest.Console console = new WatchTest.Console();
            WatchTest.TP owner = new WatchTest.TP("RealSociety5107");
            WatchTest.TP op = new WatchTest.TP("TheOperator");
            op.setOp(true);

            // wrong secret: the test event reaches the app, the app says 401, sending stops
            cmd.onCommand(owner, null, "watchtower", new String[]{"feed", "test"});
            ck(owner.got("queued as RealSociety5107"), "test says it queued: " + owner.msgs);
            s.waitFor(1, 3000);
            Thread.sleep(200);
            eq(s.hits.size(), 1, "the test event is sent at once");
            eq(events(parse(s.hits.get(0).body)).get(0).get("player_name"), "RealSociety5107", "test event is sent as the calling player");
            eq(events(parse(s.hits.get(0).body)).get(0).get("event_type"), "chat", "test event is a chat event");
            ck(feed.halted(), "wrong secret: stopped");
            cmd.onCommand(console, null, "watchtower", new String[]{"feed"});
            String c1 = String.join("\n", console.msgs);
            ck(c1.contains("STOPPED") && c1.contains("401") && c1.contains("127.0.0.1"), "status says stopped with code and host: " + c1);
            ck(!c1.contains("wrong-secret") && !c1.contains(SECRET), "status never shows the secret");
            long loud = cap.text.stream().filter(t -> t.contains("STOPPED")).count();
            eq(loud, 1L, "the 401 is logged loudly exactly once");
            console.msgs.clear();
            cmd.onCommand(console, null, "watchtower", new String[]{"feed", "test"});
            ck(String.join("\n", console.msgs).contains("Could not queue"), "test refuses while stopped");

            // /watchtower reload clears the stop
            plugin.reload();
            ck(!feed.halted(), "reload clears the stop");
            feed.reconfigure(localCfg(s, SECRET));
            console.msgs.clear();
            cmd.onCommand(console, null, "watchtower", new String[]{"feed", "test"});
            s.waitFor(2, 3000);
            Thread.sleep(200);
            eq(s.hits.size(), 2, "after the fix the test goes through");
            eq(events(parse(s.hits.get(1).body)).get(0).get("player_name"), "CONSOLE", "console test uses the name CONSOLE");
            eq(feed.lastHttp(), 200, "200 now");
            console.msgs.clear();
            cmd.onCommand(console, null, "watchtower", new String[]{"feed"});
            String c2 = String.join("\n", console.msgs);
            ck(c2.contains("last HTTP code 200") && c2.contains("secret set"), "status shows 200 and the secret is set: " + c2);
            ck(c2.contains("chat") && !c2.contains("item_pickup"), "status lists the switched-on types");
            ck(!c2.contains("\u00a7"), "console text has no colour codes");

            // THE POINT: the owner's own lines, an operator's lines and a login all go out; the password does not
            FeedListener fl = new FeedListener(feed);
            fl.onChat(new AsyncChatEvent(owner, Component.text("my own line")));
            fl.onChat(new AsyncChatEvent(op, Component.text("operator line")));
            fl.onCommand(new PlayerCommandPreprocessEvent(owner, "/login 123456"));
            fl.onCommand(new PlayerCommandPreprocessEvent(op, "/msg Bob the secret plan"));
            s.waitFor(3, 6000);
            eq(s.hits.size(), 3, "queued events go out on the next flush by themselves");
            List<Map<String, Object>> sent = events(parse(s.hits.get(2).body));
            eq(sent.size(), 4, "all four in one request");
            eq(sent.get(0).get("player_name"), "RealSociety5107", "the owner's own line reaches the app");
            eq(sent.get(0).get("description"), "my own line", "and says what they said");
            eq(sent.get(1).get("player_name"), "TheOperator", "the operator's line reaches the app");
            eq(sent.get(2).get("description"), "/login [hidden]", "the login password does not");
            eq(sent.get(3).get("description"), "/msg Bob [hidden]", "private message text does not");
            for (Hit h : s.hits) {
                ck(!h.body.contains("123456") && !h.body.contains("secret plan"), "no password or private text on the wire");
            }

            // Watchtower's own owner rule is unchanged: the owner is still not alerted about, but IS in the feed
            ck(plugin.settings().owners.contains("realsociety5107"), "owners list untouched");

            // not an owner: unknown command, nothing queued
            WatchTest.TP rando = new WatchTest.TP("Rando");
            int q = feed.queued();
            cmd.onCommand(rando, null, "watchtower", new String[]{"feed", "test"});
            ck(rando.got("Unknown command"), "a non-owner is told the command does not exist");
            eq(feed.queued(), q, "and nothing was queued by it");
            ck(!String.join("\n", rando.msgs).contains("127.0.0.1"), "and learns nothing about the feed");
            ck(cmd.onTabComplete(owner, null, "watchtower", new String[]{"fe"}).contains("feed"), "feed is offered in tab completion");
            cmd.onCommand(owner, null, "watchtower", new String[]{"help"});
            ck(owner.got("/watchtower feed"), "help lists the feed command");

            // secret never in logs
            ck(cap.all().indexOf(SECRET) < 0 && cap.all().indexOf("wrong-secret") < 0, "secret not in any log line");

            // shutdown sends what is left, off the game thread
            fl.onJoin(new PlayerJoinEvent(owner));
            plugin.onDisable();
            s.waitFor(4, 3000);
            eq(s.hits.size(), 4, "the last events are sent on shutdown");
            eq(events(parse(s.hits.get(3).body)).get(0).get("event_type"), "player_join", "the join was in it");

            // switched off
            conf.v.put("feed.enabled", false);
            WatchtowerPlugin off = WatchTest.boot(Files.createTempDirectory("nw-feed-off").toFile());
            ck(off.feed() == null, "feed.enabled: false means no feed");
            WatchCommand offCmd = new WatchCommand(off, off.service());
            WatchTest.Console oc = new WatchTest.Console();
            offCmd.onCommand(oc, null, "watchtower", new String[]{"feed", "test"});
            ck(String.join("\n", oc.msgs).contains("switched off"), "test says it is switched off: " + oc.msgs);
            oc.msgs.clear();
            offCmd.onCommand(oc, null, "watchtower", new String[]{"feed"});
            ck(String.join("\n", oc.msgs).contains("OFF"), "status says OFF");
            off.onDisable();
        } finally {
            s.server.stop(0);
            log.removeHandler(cap);
            JavaPlugin.TEST_CONFIG = null;
        }
    }
}
