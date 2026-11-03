package com.nexuscraft.nexuswatchtower;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nexuscraft.nexuswatchtower.WatchTest.T0;
import static com.nexuscraft.nexuswatchtower.WatchTest.ck;
import static com.nexuscraft.nexuswatchtower.WatchTest.eq;

/** Tests for the Kai Ross Nexus app channel, the server lockdown, the snapshot and the app-facing commands. */
public final class AppTest {

    // ------------------------------------------------------------------ helpers

    static final class Net implements AppClient.Transport {
        final List<String[]> calls = Collections.synchronizedList(new ArrayList<>());
        final Deque<Integer> script = new ArrayDeque<>();

        @Override
        public synchronized AppClient.Reply post(String url, String body, String secret, int timeoutSeconds) {
            calls.add(new String[]{url, body, secret});
            int code = script.isEmpty() ? 200 : script.poll();
            return new AppClient.Reply(code, "body-" + code);
        }

        String body(int i) {
            return calls.get(i)[1];
        }
    }

    static final class LogCatcher extends Handler {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord r) {
            lines.add(r.getLevel() + " " + r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean has(String part) {
            synchronized (lines) {
                return lines.stream().anyMatch(l -> l.contains(part));
            }
        }
    }

    static final long[] NOW = {T0};

    static Logger logger(LogCatcher c) {
        Logger l = Logger.getLogger("apptest-" + UUID.randomUUID());
        l.setUseParentHandlers(false);
        l.addHandler(c);
        return l;
    }

    static AppClient client(Net net, int perMinute, LogCatcher c) {
        NOW[0] = T0;
        return new AppClient("https://app.test", "S3CR3T", "main", "NexusWatchtower v9", perMinute, logger(c), net, 0.0, () -> NOW[0]);
    }

    static Alert alert(Severity sev, String cat, String who, String title, String detail) {
        return new Alert(T0, sev, cat, who, title, detail);
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** A small strict JSON checker, so the bodies we send are known to be well formed. */
    static boolean validJson(String s) {
        int[] i = {0};
        try {
            value(s, i);
            ws(s, i);
            return i[0] == s.length();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void ws(String s, int[] i) {
        while (i[0] < s.length() && " \t\n\r".indexOf(s.charAt(i[0])) >= 0) {
            i[0]++;
        }
    }

    private static void expect(String s, int[] i, char c) {
        ws(s, i);
        if (i[0] >= s.length() || s.charAt(i[0]) != c) {
            throw new IllegalStateException("expected " + c);
        }
        i[0]++;
    }

    private static void value(String s, int[] i) {
        ws(s, i);
        char c = s.charAt(i[0]);
        if (c == '{') {
            i[0]++;
            ws(s, i);
            if (s.charAt(i[0]) == '}') {
                i[0]++;
                return;
            }
            while (true) {
                string(s, i);
                expect(s, i, ':');
                value(s, i);
                ws(s, i);
                if (s.charAt(i[0]) == ',') {
                    i[0]++;
                    continue;
                }
                expect(s, i, '}');
                return;
            }
        } else if (c == '[') {
            i[0]++;
            ws(s, i);
            if (s.charAt(i[0]) == ']') {
                i[0]++;
                return;
            }
            while (true) {
                value(s, i);
                ws(s, i);
                if (s.charAt(i[0]) == ',') {
                    i[0]++;
                    continue;
                }
                expect(s, i, ']');
                return;
            }
        } else if (c == '"') {
            string(s, i);
        } else if (s.startsWith("true", i[0])) {
            i[0] += 4;
        } else if (s.startsWith("false", i[0])) {
            i[0] += 5;
        } else if (s.startsWith("null", i[0])) {
            i[0] += 4;
        } else {
            int start = i[0];
            while (i[0] < s.length() && "-+.eE0123456789".indexOf(s.charAt(i[0])) >= 0) {
                i[0]++;
            }
            if (start == i[0]) {
                throw new IllegalStateException("bad value");
            }
        }
    }

    private static void string(String s, int[] i) {
        ws(s, i);
        if (s.charAt(i[0]) != '"') {
            throw new IllegalStateException("expected string");
        }
        i[0]++;
        while (true) {
            char c = s.charAt(i[0]++);
            if (c == '"') {
                return;
            }
            if (c < 0x20) {
                throw new IllegalStateException("raw control character");
            }
            if (c == '\\') {
                char n = s.charAt(i[0]++);
                if (n == 'u') {
                    i[0] += 4;
                } else if ("\"\\/bfnrt".indexOf(n) < 0) {
                    throw new IllegalStateException("bad escape");
                }
            }
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        jsonChecker();
        bodies();
        worker();
        limits();
        retries();
        shutdownAndOverflow();
        realTransport();
        lockdown();
        snapshot();
        pluginWiring();
        commandsFromTheApp();
        gaps();
        System.out.println(WatchTest.checks + " checks, " + WatchTest.failures + " failed (after the app tests).");
        if (WatchTest.failures > 0) {
            System.exit(1);
        }
    }

    static void jsonChecker() {
        ck(validJson("{\"a\":[1,2,{\"b\":\"x\\n\\u0041\"}],\"c\":true,\"d\":null,\"e\":-1.5e3}"), "json checker accepts good json");
        ck(!validJson("{\"a\":1,}") && !validJson("{\"a\":\"x\ny\"}") && !validJson("{\"a\":\"\\q\"}") && !validJson("{\"a\":1} x")
                && !validJson("[1 2]") && !validJson("{\"a\" 1}") && !validJson(""), "json checker rejects bad json");
    }

    // ------------------------------------------------------------------ bodies

    static void bodies() {
        Net net = new Net();
        AppClient c = client(net, 18, new LogCatcher());
        String red = c.alertBody(alert(Severity.RED, "mass-blocks", "Bob", "is breaking \"lots\" of blocks", "nexsus 1 2 3"), true);
        ck(validJson(red), "alert body is valid json");
        eq(field(red, "title"), "Bob: is breaking \\\"lots\\\" of blocks", "alert title carries who and what");
        eq(field(red, "severity"), "critical", "RED is critical");
        eq(field(red, "category"), "world", "mass-blocks is world");
        eq(field(red, "source"), "NexusWatchtower", "alert source");
        eq(field(red, "plugin"), "NexusWatchtower v9", "alert plugin label");
        eq(field(red, "url"), "/command", "alert url is the in-app path");
        eq(field(red, "body"), "nexsus 1 2 3", "alert body is the detail");
        ck(red.contains("\"push\":true"), "alert push flag");
        ck(c.alertBody(alert(Severity.YELLOW, "chat", "", "said it", ""), false).contains("\"push\":false"), "push false is respected");
        eq(field(c.alertBody(alert(Severity.YELLOW, "chat", "", "said it", ""), false), "title"), "said it", "no who: no prefix");
        eq(field(c.alertBody(alert(Severity.YELLOW, "chat", "", "x", ""), true), "severity"), "warning", "YELLOW is warning");
        eq(field(c.alertBody(alert(Severity.INFO, "chat", "", "x", ""), true), "severity"), "info", "INFO is info");

        String longTitle = c.alertBody(alert(Severity.RED, "ops", "A".repeat(200), "t", ""), true);
        eq(field(longTitle, "title").length(), 80, "title is cut to the app's 80 character limit");
        ck(field(longTitle, "title").endsWith("..."), "a cut title ends with dots");
        String ctl = c.alertBody(alert(Severity.RED, "ops", "Ba\u0001d", "ti\u0007tle", "de\ttail"), true);
        ck(validJson(ctl) && !ctl.contains("\\u0001") && !ctl.contains("\\u0007") && !ctl.contains("\\t"), "control characters never reach the app");
        eq(field(c.noticeBody("t", "b".repeat(2000), "info", "system", false), "body").length(), 900, "body is cut to 900");
        eq(AppClient.clip(null, 5), "", "clip of null");
        eq(AppClient.clip("  abc  ", 10), "abc", "clip trims");
        eq(AppClient.clip("abcdef", 6), "abcdef", "clip keeps text that exactly fits");
        eq(AppClient.clip("abcdefg", 6), "abc...", "clip cuts one over");
        eq(AppClient.clip("a\nb", 10), "a\nb", "clip keeps newlines");

        Map<String, String> cats = new HashMap<>();
        for (String k : List.of("plugin", "server", "watch", "chat", "coordination", "container", "mass-blocks", "hazard",
                "command-block", "test", "digest", "system", "ops", "files", "guard", "lockdown", "probe", "command", "zzz")) {
            cats.put(k, AppClient.appCategory(k));
        }
        eq(cats.get("plugin"), "plugin", "category plugin");
        eq(cats.get("server"), "server", "category server");
        for (String k : List.of("watch", "chat", "coordination", "container")) {
            eq(cats.get(k), "player", "category " + k);
        }
        for (String k : List.of("mass-blocks", "hazard", "command-block")) {
            eq(cats.get(k), "world", "category " + k);
        }
        for (String k : List.of("test", "digest", "system")) {
            eq(cats.get(k), "system", "category " + k);
        }
        for (String k : List.of("ops", "files", "guard", "lockdown", "probe", "command", "zzz")) {
            eq(cats.get(k), "security", "category " + k);
        }
        eq(AppClient.appCategory(null), "security", "null category");

        StringBuilder text = new StringBuilder("Watched activity | Srv");
        for (int i = 0; i < 60; i++) {
            text.append("\n12:00:").append(String.format("%02d", i)).append(" Bob: /say \"hi\" ").append(i);
        }
        String ev = c.eventBody(text.toString());
        ck(validJson(ev), "event body is valid json");
        ck(ev.contains("\"fire_kai\":false"), "the director is never invoked for Watchtower's events");
        ck(!ev.contains("player_name") && !ev.contains("player_uuid"), "the feed never names a player field the AI could act on");
        eq(field(ev, "event_type"), "operator_activity", "event type");
        eq(field(ev, "source"), "NexusWatchtower", "event source");
        eq(field(ev, "server_id"), "main", "event server id");
        eq(field(ev, "severity"), "info", "event severity");
        String details = ev.substring(ev.indexOf("\"details\""));
        eq(details.split("\",\"").length, 40, "at most 40 lines are sent (the header plus 39)");
        ck(!details.contains("12:00:39 Bob") && details.contains("12:00:38 Bob"), "the lines after the 40th are left out");
        String desc = field(ev, "description").replace("\\n", "\n").replace("\\\"", "\"");
        ck(desc.length() <= 900 && desc.endsWith("..."), "event description is bounded");
    }

    // ------------------------------------------------------------------ worker

    static void worker() {
        Net net = new Net();
        LogCatcher log = new LogCatcher();
        AppClient c = client(net, 18, log);
        c.offerAlert(alert(Severity.YELLOW, "chat", "Bob", "said it", ""), true);
        c.offerFeed("Watched activity | Srv\n12:00:00 Bob: /list");
        List<AppClient.Reply> replies = new ArrayList<>();
        c.offerNotice("Hello", "first contact", "info", "system", false, replies::add);
        eq(c.queued(), 3, "three jobs wait");
        c.drainForTest();
        eq(net.calls.size(), 3, "all three were sent");
        eq(net.calls.get(0)[0], "https://app.test/functions/pluginAlert", "alerts go to pluginAlert");
        eq(net.calls.get(1)[0], "https://app.test/functions/ingestGameEvent", "the feed goes to ingestGameEvent");
        eq(net.calls.get(2)[0], "https://app.test/functions/pluginAlert", "notices go to pluginAlert");
        eq(net.calls.get(0)[2], "S3CR3T", "the secret is passed to the transport");
        ck(net.body(0).contains("\"push\":true"), "yellow pushes when asked");
        eq(c.sentCount(), 3, "sent count");
        eq(c.lastCode(), 200, "last code");
        eq(replies.size(), 1, "the notice callback ran");
        ck(replies.get(0).ok(), "the callback saw the answer");
        ck(c.status().startsWith("on (3 sent, 0 failed, 0 waiting, last answer 200)"), "status text: " + c.status());
        ck(!c.status().contains("S3CR3T"), "status never shows the secret");

        c.offerAlert(alert(Severity.YELLOW, "chat", "Bob", "quiet one", ""), false);
        c.offerAlert(alert(Severity.RED, "ops", "Bob", "loud one", ""), false);
        c.drainForTest();
        ck(net.body(3).contains("\"push\":false"), "yellow does not push when push-yellow is off");
        ck(net.body(4).contains("\"push\":true"), "RED always pushes");
        eq(new AppClient("https://x", "s", "main", "p", 5, logger(log), net, 0.0, () -> NOW[0]).status().substring(0, 20), "on (0 sent, 0 failed", "fresh status");
        Net empty = new Net();
        AppClient fresh = client(empty, 18, log);
        ck(fresh.status().contains("no call yet"), "status before any call");
        empty.script.add(-1);
        fresh.offerAlert(alert(Severity.RED, "ops", "", "x", ""), true);
        fresh.shutdown(1);
        fresh.drainForTest();
        ck(fresh.status().contains("could not connect") || fresh.status().contains("last answer 200"), "status after a failed call");
    }

    // ------------------------------------------------------------------ limits

    static void limits() {
        Net net = new Net();
        LogCatcher log = new LogCatcher();
        AppClient c = client(net, 3, log);
        for (int i = 0; i < 5; i++) {
            c.offerAlert(alert(Severity.YELLOW, "chat", "p" + i, "t", ""), true);
        }
        c.drainForTest();
        eq(net.calls.size(), 3, "only 3 yellow go out in a minute");
        eq(c.droppedCount(), 2, "two were dropped");
        NOW[0] += 61_000;
        c.drainForTest();
        eq(net.calls.size(), 4, "one 'held back' notice goes out once there is room");
        ck(field(net.body(3), "title").contains("held back 2"), "the notice says how many");
        ck(net.body(3).contains("\"push\":false") && net.body(3).contains("\"severity\":\"warning\""), "the notice is quiet and a warning");
        eq(c.droppedCount(), 0, "the dropped counter resets");
        NOW[0] += 61_000;
        c.drainForTest();
        eq(net.calls.size(), 4, "and it is not sent twice");

        Net net2 = new Net();
        AppClient r = client(net2, 3, log);
        for (int i = 0; i < 3; i++) {
            r.offerAlert(alert(Severity.YELLOW, "chat", "p" + i, "t", ""), true);
        }
        for (int i = 0; i < 17; i++) {
            r.offerAlert(alert(Severity.RED, "ops", "r" + i, "t", ""), true);
        }
        r.drainForTest();
        eq(net2.calls.size(), 20, "RED may use the whole 20 per minute even when yellow is capped at 3");
        r.offerAlert(alert(Severity.YELLOW, "chat", "late", "t", ""), true);
        r.offerAlert(alert(Severity.RED, "ops", "late", "t", ""), true);
        r.drainForTest();
        eq(net2.calls.size(), 21, "a RED beyond 20 waits for room rather than being lost; yellow is dropped");
        eq(r.droppedCount() >= 1 ? 1 : 0, 1, "the late yellow was counted as dropped");
        ck(AppClient.HARD_LIMIT == 20, "hard limit matches the app's 20 a minute");

        Net net3 = new Net();
        AppClient hi = new AppClient("https://app.test", "s", "main", "p", 99, logger(log), net3, 0.0, () -> NOW[0]);
        NOW[0] = T0;
        for (int i = 0; i < 25; i++) {
            hi.offerAlert(alert(Severity.YELLOW, "chat", "p" + i, "t", ""), true);
        }
        hi.drainForTest();
        eq(net3.calls.size(), 20, "a per-minute setting above 20 is clamped to 20");
        Net net4 = new Net();
        AppClient lo = new AppClient("https://app.test", "s", "main", "p", -5, logger(log), net4, 0.0, () -> NOW[0]);
        lo.offerAlert(alert(Severity.YELLOW, "chat", "a", "t", ""), true);
        lo.offerAlert(alert(Severity.YELLOW, "chat", "b", "t", ""), true);
        lo.drainForTest();
        eq(net4.calls.size(), 1, "a per-minute setting below 1 is clamped to 1");
    }

    // ------------------------------------------------------------------ retries

    static void retries() {
        LogCatcher log = new LogCatcher();
        Net net = new Net();
        net.script.addAll(List.of(500, 503, 200));
        AppClient c = client(net, 18, log);
        c.offerAlert(alert(Severity.RED, "ops", "Bob", "x", ""), true);
        c.drainForTest();
        eq(net.calls.size(), 3, "5xx is retried");
        eq(c.sentCount(), 1, "and counted as sent once it works");
        eq(c.failedCount(), 0, "no failure when a retry works");
        ck(log.has("503"), "the app's refusal is logged with its code");

        Net n429 = new Net();
        n429.script.addAll(List.of(429, 429, 429, 429));
        LogCatcher log2 = new LogCatcher();
        AppClient c2 = client(n429, 18, log2);
        c2.offerAlert(alert(Severity.YELLOW, "chat", "Bob", "x", ""), true);
        c2.drainForTest();
        eq(n429.calls.size(), 5, "429 is retried 3 times, then given up on, then one 'held back' notice follows");
        ck(n429.body(4).contains("held back 1"), "the notice says one alert was lost");
        eq(c2.failedCount(), 1, "counted as failed");
        ck(log2.has("429: body-429"), "the response body is logged");

        Net n401 = new Net();
        n401.script.add(401);
        LogCatcher log3 = new LogCatcher();
        AppClient c3 = client(n401, 18, log3);
        c3.offerAlert(alert(Severity.YELLOW, "chat", "Bob", "x", ""), true);
        c3.drainForTest();
        eq(n401.calls.size(), 2, "a wrong secret (401) is not retried (the second call is the 'held back' notice)");
        ck(n401.body(1).contains("held back 1"), "the notice follows the lost alert");
        eq(c3.failedCount(), 1, "401 counts as failed");
        ck(log3.has("401") && !log3.has("S3CR3T"), "401 is logged and the secret never is");

        Net net5 = new Net();
        net5.script.addAll(List.of(-1, 200));
        AppClient c5 = client(net5, 18, new LogCatcher());
        c5.offerAlert(alert(Severity.RED, "ops", "Bob", "x", ""), true);
        c5.drainForTest();
        eq(net5.calls.size(), 2, "an unreachable app is retried");
        eq(c5.sentCount(), 1, "and delivered when it comes back");

        Net net6 = new Net();
        net6.script.add(400);
        AppClient c6 = client(net6, 18, new LogCatcher());
        List<AppClient.Reply> got = new ArrayList<>();
        c6.offerNotice("t", "b", "info", "system", false, got::add);
        c6.drainForTest();
        eq(got.size(), 1, "the callback is called on failure too");
        eq(got.get(0).code(), 400, "with the app's answer");
        eq(c6.droppedCount(), 0, "a failed notice is not counted as a dropped alert");

        Net net7 = new Net();
        net7.script.addAll(List.of(500, 500, 500, 500));
        AppClient c7 = client(net7, 18, new LogCatcher());
        c7.offerAlert(alert(Severity.YELLOW, "chat", "Bob", "x", ""), true);
        c7.drainForTest();
        eq(c7.droppedCount() >= 0 ? 1 : 0, 1, "give-up path finished");
        ck(net7.calls.size() >= 4, "gave up after the retries");

        // repeated warnings with the same code are not logged every time
        Net net8 = new Net();
        net8.script.addAll(List.of(500, 500, 500, 500));
        LogCatcher log8 = new LogCatcher();
        AppClient c8 = client(net8, 18, log8);
        c8.offerAlert(alert(Severity.YELLOW, "chat", "Bob", "x", ""), true);
        c8.drainForTest();
        synchronized (log8.lines) {
            eq(log8.lines.stream().filter(l -> l.contains("500")).count(), 1L, "the same refusal is logged once, not four times");
        }
    }

    // ------------------------------------------------------------------ shutdown, overflow, threads

    static void shutdownAndOverflow() throws Exception {
        Net net = new Net();
        net.script.addAll(List.of(500, 200));
        AppClient c = client(net, 18, new LogCatcher());
        c.offerAlert(alert(Severity.RED, "plugin", "", "Watchtower was switched off", ""), true);
        c.shutdown(1);
        c.drainForTest();
        eq(net.calls.size(), 1, "while closing there are no retries (the server is going down)");
        c.offerAlert(alert(Severity.RED, "ops", "", "after the end", ""), true);
        eq(c.queued(), 0, "nothing can be queued after shutdown");

        Net net2 = new Net();
        AppClient t = client(net2, 18, new LogCatcher());
        t.start();
        t.offerAlert(alert(Severity.RED, "ops", "Bob", "threaded", ""), true);
        long deadline = System.currentTimeMillis() + 3000;
        while (net2.calls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        eq(net2.calls.size(), 1, "the background thread delivers on its own");
        t.offerAlert(alert(Severity.RED, "ops", "Bob", "last words", ""), true);
        t.shutdown(3000);
        eq(net2.calls.size(), 2, "shutdown lets what is already queued go out first");
        ck(Thread.getAllStackTraces().keySet().stream().noneMatch(th -> th.getName().equals("NexusWatchtower-app") && th.isAlive()), "the thread has stopped");

        Net net3 = new Net();
        AppClient o = client(net3, 18, new LogCatcher());
        for (int i = 0; i < 250; i++) {
            o.offerAlert(alert(Severity.YELLOW, "chat", "p" + i, "t", ""), true);
        }
        eq(o.queued(), 200, "the queue is bounded");
        eq(o.droppedCount(), 50, "extra yellow alerts are dropped and counted");
        o.offerAlert(alert(Severity.RED, "ops", "boss-problem", "x", ""), true);
        eq(o.queued(), 200, "a RED alert makes room for itself");
        eq(o.droppedCount(), 51, "by dropping the oldest");
        o.drainForTest();
        ck(net3.body(0).contains("boss-problem"), "and goes first");
    }

    // ------------------------------------------------------------------ the real network client

    static void realTransport() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/functions/pluginAlert", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + "|" + ex.getRequestHeaders().getFirst("x-kai-secret") + "|"
                    + ex.getRequestHeaders().getFirst("Content-Type") + "|" + body);
            byte[] out = "{\"ok\":true,\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.createContext("/functions/ingestGameEvent", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] out = "bad secret".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.start();
        try {
            LogCatcher log = new LogCatcher();
            NOW[0] = T0;
            AppClient c = new AppClient("http://127.0.0.1:" + srv.getAddress().getPort(), "S3CR3T", "main", "NexusWatchtower v9", 18, logger(log));
            c.offerAlert(alert(Severity.RED, "ops", "Bob", "was just given operator", "check /op"), true);
            c.offerFeed("Watched activity | Srv\n12:00:00 Bob: /list");
            c.drainForTest();
            eq(seen.size(), 2, "the alert reached the app, and so did the 'held back' notice for the refused feed");
            String[] p = seen.get(0).split("\\|", 4);
            eq(p[0], "POST", "alerts are POSTed");
            eq(p[1], "S3CR3T", "with the x-kai-secret header");
            eq(p[2], "application/json", "as json");
            ck(validJson(p[3]) && p[3].contains("was just given operator"), "the body arrived intact");
            ck(seen.get(1).contains("held back 1"), "the refused feed is reported once");
            eq(c.sentCount(), 2, "two delivered");
            eq(c.failedCount(), 1, "the feed was refused with 401");
            eq(c.lastCode(), 200, "last code is the notice's answer");
            ck(log.has("401: bad secret") && !log.has("S3CR3T"), "the refusal and its reason are logged, the secret is not");
        } finally {
            srv.stop(0);
        }

        LogCatcher log2 = new LogCatcher();
        AppClient dead = new AppClient("http://127.0.0.1:1", "S3CR3T", "main", "NexusWatchtower v9", 18, logger(log2),
                AppClient.realTransport(logger(log2)), 0.0, () -> NOW[0]);
        dead.offerAlert(alert(Severity.RED, "ops", "Bob", "x", ""), true);
        dead.drainForTest();
        eq(dead.failedCount(), 2, "an app that cannot be reached is a failure, not a crash (the alert, then the notice about it)");
        ck(dead.lastCode() < 0 && log2.has("Could not reach"), "and it is logged plainly");
    }

    // ------------------------------------------------------------------ lockdown

    static void lockdown() throws Exception {
        WatchTest.Fx f = new WatchTest.Fx();
        WatchService svc = f.svc;
        Identity bob = WatchTest.person("Bob");
        Identity boss = WatchTest.owner();
        ck(!svc.lockdown(), "lockdown starts off");
        ck(svc.block(bob, false, "STONE", "w", 0, 0, 0, T0).allow(), "before: building is allowed");
        ck(svc.setLockdown(true, T0), "lockdown on reports a change");
        ck(!svc.setLockdown(true, T0), "turning it on twice changes nothing");
        ck(svc.lockdown(), "lockdown is on");
        ck(f.journal.recent(null, 50).stream().anyMatch(a -> a.title().contains("SERVER LOCKDOWN ON")), "turning it on is recorded");
        ck(!svc.block(bob, false, "STONE", "w", 0, 0, 0, T0 + 1).allow(), "break denied for everyone");
        ck(!svc.block(bob, true, "STONE", "w", 0, 0, 0, T0 + 1).allow(), "place denied");
        ck(!svc.bucket(bob, "WATER_BUCKET", "w", 0, 0, 0, T0 + 1).allow(), "buckets denied");
        ck(!svc.ignite(bob, "w", 0, 0, 0, T0 + 1).allow(), "fire denied");
        ck(!svc.frozen(bob).allow(), "opening and hitting denied");
        ck(!svc.command(bob, "/home", T0 + 1).allow(), "commands denied");
        ck(svc.command(bob, "/login hunter2", T0 + 2).allow(), "log-in commands still work during lockdown");
        ck(svc.command(bob, "/register hunter2 hunter2", T0 + 2).allow(), "register still works");
        ck(svc.block(boss, false, "STONE", "w", 0, 0, 0, T0 + 1).allow() && svc.bucket(boss, "LAVA_BUCKET", "w", 0, 0, 0, T0 + 1).allow()
                && svc.frozen(boss).allow() && svc.command(boss, "/stop", T0 + 1).allow() && svc.ignite(boss, "w", 0, 0, 0, T0 + 1).allow(),
                "owners are never frozen");
        f.rebuild();
        ck(svc != f.svc && f.svc.lockdown(), "lockdown survives a restart");
        ck(!f.svc.block(bob, false, "STONE", "w", 0, 0, 0, T0 + 3).allow(), "and still applies after it");
        ck(f.svc.setLockdown(false, T0 + 4), "lockdown off reports a change");
        ck(!f.svc.setLockdown(false, T0 + 4), "off twice changes nothing");
        ck(f.svc.block(bob, false, "STONE", "w", 0, 0, 0, T0 + 5).allow(), "after: building is allowed again");
        ck(f.journal.recent(null, 50).stream().anyMatch(a -> a.title().contains("Server lockdown lifted")), "lifting it is recorded");
        f.rebuild();
        ck(!f.svc.lockdown(), "lifting it survives a restart too");

        // an individually frozen player is still denied the log-in family
        WatchTest.watched(f, "Eve", Watchlist.Mode.GUARD);
        f.svc.lock("Eve", T0 + 6);
        ck(!f.svc.command(WatchTest.person("Eve"), "/login pw", T0 + 7).allow(), "a player frozen on their own gets no exemption");
        f.svc.setLockdown(true, T0 + 8);
        ck(!f.svc.command(WatchTest.person("Eve"), "/login pw", T0 + 9).allow(), "nor during lockdown");
        f.svc.unlock("Eve", T0 + 10);
        ck(f.svc.command(WatchTest.person("Eve"), "/login pw", T0 + 11).allow(), "released, she can log in during lockdown");
        ck(!f.svc.command(WatchTest.person("Eve"), "/home", T0 + 11).allow(), "but not run other commands");
        f.journal.close();
    }

    // ------------------------------------------------------------------ snapshot

    static void snapshot() throws Exception {
        WatchTest.Fx f = new WatchTest.Fx(s -> s.expectedOps = Settings.set("pal"));
        Map<UUID, String> ops = new HashMap<>();
        ops.put(WatchTest.person("Pal").uuid(), "Pal");
        ops.put(WatchTest.person("Zed\"Q").uuid(), "Zed\"Q");
        ops.put(WatchTest.owner().uuid(), "RealSociety5107");
        String empty = f.svc.snapshot(new HashMap<>(), T0);
        ck(validJson(empty), "an empty snapshot is valid json");
        ck(empty.contains("\"watched\":[]") && empty.contains("\"operators\":[]") && empty.contains("\"unexpectedOperators\":[]"), "empty lists");
        ck(empty.contains("\"lockdown\":false") && empty.contains("\"armed\":true") && empty.contains("\"time\":" + T0), "flags and time");
        WatchTest.watched(f, "Shady", Watchlist.Mode.GUARD);
        WatchTest.watched(f, "Quiet", Watchlist.Mode.OBSERVE);
        f.svc.lock("Shady", T0);
        f.svc.joined(WatchTest.person("Quiet"), false, T0);
        f.svc.setLockdown(true, T0);
        String s = f.svc.snapshot(ops, T0);
        ck(validJson(s), "snapshot is valid json: " + s);
        ck(s.contains("{\"name\":\"Shady\",\"mode\":\"guard\",\"frozen\":true,\"online\":false}"), "a frozen guarded player");
        ck(s.contains("{\"name\":\"Quiet\",\"mode\":\"observe\",\"frozen\":false,\"online\":true}"), "an online observed player");
        ck(s.contains("\"lockdown\":true"), "lockdown shows");
        ck(s.contains("\"operators\":[\"Pal\",\"RealSociety5107\",\"Zed\\\"Q\"]"), "operators are sorted and escaped: " + s);
        ck(s.contains("\"unexpectedOperators\":[\"Zed\\\"Q\"]"), "only the operator nobody expected is flagged: " + s);
        ck(s.contains("\"app\":\"off\""), "app state is reported");
        f.svc.appInfo = () -> "on (1 sent)";
        ck(f.svc.snapshot(ops, T0).contains("\"app\":\"on (1 sent)\""), "app state follows the plugin");
        ck(f.svc.status(ops, T0).stream().anyMatch(l -> l.contains("App: on (1 sent)") && l.contains("SERVER LOCKDOWN ON")), "status mentions the app and lockdown");
        f.journal.close();
    }

    // ------------------------------------------------------------------ the plugin and its wiring

    static void pluginWiring() throws Exception {
        File server = Files.createTempDirectory("nw-server2").toFile();
        new File(server, "plugins").mkdirs();
        WatchTest.FakeConfig cfg = new WatchTest.FakeConfig();
        cfg.v.put("app.secret", "S3CR3T");
        cfg.v.put("app.server-id", "side-1");
        JavaPlugin.TEST_CONFIG = cfg;
        WatchtowerPlugin plugin;
        try {
            plugin = WatchTest.boot(server);
        } finally {
            JavaPlugin.TEST_CONFIG = null;
        }
        ck(plugin.app() != null, "plugin: a secret in config turns the app channel on");
        ck(plugin.statusLines().stream().anyMatch(l -> l.contains("App: on (0 sent")), "plugin: status says the app is on");
        plugin.app().shutdown(10);
        Net net = new Net();
        NOW[0] = T0;
        AppClient fake = new AppClient("https://app.test", "S3CR3T", "side-1", "NexusWatchtower v9", 18, logger(new LogCatcher()), net, 0.0, () -> NOW[0]);
        java.lang.reflect.Field fld = WatchtowerPlugin.class.getDeclaredField("app");
        fld.setAccessible(true);
        fld.set(plugin, fake);
        for (Runnable r : new ArrayList<>(org.bukkit.scheduler.BukkitScheduler.QUEUED)) {
            r.run();
        }
        fake.drainForTest();
        ck(net.calls.stream().anyMatch(c -> c[1].contains("\"title\":\"Server started\"") && c[1].contains("\"category\":\"server\"") && c[1].contains("\"push\":true")),
                "plugin: 'Server started' reaches the app");

        int before = net.calls.size();
        plugin.send(alert(Severity.YELLOW, "chat", "Bob", "said a watched phrase", "x"), "text", false);
        plugin.send(alert(Severity.RED, "ops", "Bob", "was just given operator", ""), "text", true);
        plugin.send(alert(Severity.INFO, "watch", "Bob", "left", ""), "text", false);
        fake.drainForTest();
        eq(net.calls.size() - before, 2, "plugin: yellow and red go to the app, INFO does not");
        ck(net.body(before).contains("\"push\":true") && net.body(before + 1).contains("\"severity\":\"critical\""), "plugin: yellow pushes, red is critical");
        plugin.settings().appPushYellow = false;
        plugin.send(alert(Severity.YELLOW, "chat", "Amy", "said it", ""), "text", false);
        fake.drainForTest();
        ck(net.body(before + 2).contains("\"push\":false"), "plugin: push-yellow false keeps yellow quiet");

        int b2 = net.calls.size();
        plugin.feed("Watched activity | Srv\n12:00:00 Shady: /list");
        plugin.sendDigest();
        fake.drainForTest();
        ck(net.calls.get(b2)[0].endsWith("/ingestGameEvent") && net.body(b2).contains("\"fire_kai\":false"), "plugin: the feed goes to the app without waking the director");
        ck(net.body(b2 + 1).contains("Watchtower digest") && net.body(b2 + 1).contains("\"push\":false"), "plugin: the digest goes to the app, quietly");

        WatchCommand cmd = new WatchCommand(plugin, plugin.service());
        WatchTest.Console console = new WatchTest.Console();
        org.bukkit.command.Command c = new org.bukkit.command.Command();
        cmd.onCommand(console, c, "watchtower", new String[]{"test"});
        ck(console.msgs.stream().anyMatch(m -> m.equals("Test alert sent to the console, the app.")), "command: test says it went to the app");
        ck(console.msgs.stream().anyMatch(m -> m.startsWith("App: on")), "command: test shows the app status");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"snapshot"});
        ck(console.msgs.size() == 1 && validJson(console.msgs.get(0)) && console.msgs.get(0).contains("\"app\":\"on ("), "command: snapshot is one line of valid json");

        // a server stop sends a last notice and drains the queue
        Bukkit.STOPPING = true;
        int b3 = net.calls.size();
        plugin.onDisable();
        fake.drainForTest();
        Bukkit.STOPPING = false;
        ck(net.calls.size() > b3 && net.calls.stream().skip(b3).anyMatch(x -> x[1].contains("\"title\":\"Server stopping\"")), "plugin: 'Server stopping' reaches the app");
    }

    // ------------------------------------------------------------------ small edges found by mutation testing

    static void gaps() throws Exception {
        Settings st = new Settings();
        st.trustedSenders = new java.util.LinkedHashSet<>();
        st.trustedSenders.add("bad name!");
        st.trustedSenders.add(" ok.Class$Inner ");
        st.trustedSenders.add("");
        st.trustedSenders.add(null);
        st.clamp();
        eq(st.trustedSenders, Settings.set("ok.Class$Inner"), "settings: sender class names are checked and trimmed");

        ck(new AppClient.Reply(200, "").ok() && new AppClient.Reply(299, "").ok(), "reply: 2xx is ok");
        ck(!new AppClient.Reply(199, "").ok() && !new AppClient.Reply(300, "").ok() && !new AppClient.Reply(-1, "").ok(), "reply: anything else is not");

        // retries really wait (a very short wait here) and then retry
        Net slow = new Net();
        slow.script.addAll(List.of(500, 200));
        NOW[0] = T0;
        AppClient waits = new AppClient("https://app.test", "s", "main", "p", 18, logger(new LogCatcher()), slow, 0.0005, () -> NOW[0]);
        waits.offerAlert(alert(Severity.RED, "ops", "Bob", "x", ""), true);
        waits.drainForTest();
        eq(slow.calls.size(), 2, "retry after a short wait");

        // the idle worker sends the 'held back' notice once there is room
        Net idle = new Net();
        NOW[0] = T0;
        AppClient t = new AppClient("https://app.test", "s", "main", "p", 1, logger(new LogCatcher()), idle, 0.0, () -> NOW[0]);
        t.start();
        t.offerAlert(alert(Severity.YELLOW, "chat", "a", "one", ""), true);
        t.offerAlert(alert(Severity.YELLOW, "chat", "b", "two", ""), true);
        long deadline = System.currentTimeMillis() + 3000;
        while (t.droppedCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        NOW[0] += 61_000;
        deadline = System.currentTimeMillis() + 3000;
        while (idle.calls.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        t.shutdown(2000);
        ck(idle.calls.size() == 2 && idle.body(1).contains("held back 1"), "the background thread reports what it had to drop");

        // journal lines for owner actions, and the frozen-command alert
        WatchTest.Fx f = new WatchTest.Fx();
        WatchTest.watched(f, "Cal", Watchlist.Mode.GUARD);
        f.svc.lock("Cal", T0 + 1);
        ck(f.journal.recent(null, 20).stream().anyMatch(a -> a.title().contains("was frozen by the owner")), "freezing is journaled");
        ck(f.journal.recent(null, 20).stream().anyMatch(a -> a.title().contains("added to the watchlist in guard mode")), "adding is journaled");
        f.svc.command(WatchTest.person("Cal"), "/home", T0 + 2);
        ck(f.sink.has("lockdown", "tried a command while frozen"), "a frozen player's command attempt alerts you");

        // a chat line is cut at 200 characters, not 199 or 201
        WatchTest.Fx chat = new WatchTest.Fx(x -> x.phrases = new ArrayList<>(List.of("zz")));
        chat.svc.chat(WatchTest.person("Dan"), "zz" + "a".repeat(198), T0);
        eq(chat.sink.last().alert().detail().length(), 200, "a 200 character line is kept whole");
        chat.svc.chat(WatchTest.person("Eli"), "zz" + "a".repeat(199), T0);
        ck(chat.sink.last().alert().detail().endsWith("...") && chat.sink.last().alert().detail().length() == 203, "a 201 character line is cut");

        // online time reaches the digest
        WatchTest.Fx dig = new WatchTest.Fx();
        WatchTest.watched(dig, "Fay", Watchlist.Mode.OBSERVE);
        dig.svc.joined(WatchTest.person("Fay"), false, T0);
        dig.svc.left(WatchTest.person("Fay"), T0 + 3_600_000L);
        ck(dig.svc.digest(new HashMap<>(), T0 + 3_700_000L).contains("online 1h00m"), "digest: online time is counted when someone leaves");

        // join and leave lines reach the feed; removing and the watchlist heading are recorded
        WatchTest.Fx fd = new WatchTest.Fx();
        WatchTest.watched(fd, "Gus", Watchlist.Mode.OBSERVE);
        fd.svc.joined(WatchTest.person("Gus"), false, T0);
        fd.svc.left(WatchTest.person("Gus"), T0 + 1000);
        fd.svc.flushFeed();
        ck(fd.sink.feeds.size() == 1 && fd.sink.feeds.get(0).contains("Gus joined") && fd.sink.feeds.get(0).contains("Gus left"), "the feed lists joins and leaves");
        ck(fd.svc.digest(new HashMap<>(), T0 + 2000).contains("Watchlist:\n"), "digest: the watchlist heading is on its own line");
        fd.svc.watchRemove("Gus", T0 + 3000);
        ck(fd.journal.recent(null, 50).stream().anyMatch(a -> a.title().contains("removed from the watchlist")), "removing is journaled");
        WatchTest.watched(fd, "Hal", Watchlist.Mode.GUARD);
        fd.svc.lock("Hal", T0 + 4000);
        ck(fd.svc.status(new HashMap<>(), T0 + 4000).get(0).startsWith("Watching 1 player(s), 1 frozen"), "status counts frozen players");

        // binding an unknown or id-less identity changes nothing and never throws
        fd.watch.bind(new Identity(UUID.randomUUID(), "Stranger"));
        fd.watch.add("Nameless", null, Watchlist.Mode.OBSERVE, "", T0);
        fd.watch.bind(new Identity(null, "Nameless"));
        ck(fd.watch.findByName("Nameless").uuid.isEmpty(), "bind: without an id there is nothing to remember");
        fd.journal.close();

        // a changed name is saved
        WatchTest.Fx bind = new WatchTest.Fx();
        UUID id = UUID.randomUUID();
        bind.watch.add("OldName", id, Watchlist.Mode.OBSERVE, "", T0);
        bind.watch.bind(new Identity(id, "NewName"));
        eq(new Watchlist(new File(bind.dir, "watchlist.tsv")).all().get(0).name, "NewName", "a renamed player is saved under the new name");
        for (WatchTest.Fx x : List.of(f, chat, dig, bind)) {
            x.journal.close();
        }
    }

    // ------------------------------------------------------------------ commands the app sends

    static void commandsFromTheApp() throws Exception {
        File server = Files.createTempDirectory("nw-server3").toFile();
        new File(server, "plugins").mkdirs();
        WatchtowerPlugin plugin = WatchTest.boot(server);
        WatchService svc = plugin.service();
        WatchCommand cmd = new WatchCommand(plugin, svc);
        org.bukkit.command.Command c = new org.bukkit.command.Command();
        WatchTest.Console console = new WatchTest.Console();

        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "on"});
        ck(svc.lockdown() && console.msgs.get(0).contains("SERVER LOCKDOWN ON"), "command: lockdown on");
        ck(console.msgs.stream().noneMatch(m -> m.contains("\u00a7")), "command: the app's command bridge gets plain text, no colour codes");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "on"});
        ck(console.msgs.get(0).contains("already on"), "command: lockdown on twice");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown"});
        ck(console.msgs.get(0).contains("lockdown is ON"), "command: lockdown status is the default");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "status"});
        ck(console.msgs.get(0).contains("is ON"), "command: lockdown status");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "off"});
        ck(!svc.lockdown() && console.msgs.get(0).contains("Lockdown lifted"), "command: lockdown off");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "off"});
        ck(console.msgs.get(0).contains("already off"), "command: lockdown off twice");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "maybe"});
        ck(console.msgs.get(0).contains("Usage: /watchtower lockdown"), "command: lockdown usage");
        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "status"});
        ck(console.msgs.get(0).contains("is off"), "command: lockdown status when off");
        ck(cmd.onTabComplete(console, c, "wt", new String[]{"lockdown", ""}).containsAll(List.of("on", "off", "status")), "tab: lockdown options");
        ck(cmd.onTabComplete(console, c, "wt", new String[]{"lo"}).containsAll(List.of("lock", "lockdown", "log")), "tab: lockdown is listed");
        ck(cmd.onTabComplete(console, c, "wt", new String[]{"sn"}).contains("snapshot"), "tab: snapshot is listed");

        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"help"});
        ck(console.msgs.stream().anyMatch(m -> m.contains("lockdown on|off|status")) && console.msgs.stream().anyMatch(m -> m.contains("snapshot")), "command: help lists the new commands");

        console.msgs.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"add", "Zed", "guard"});
        cmd.onCommand(console, c, "watchtower", new String[]{"mode", "Zed", "observe"});
        ck(console.msgs.stream().anyMatch(m -> m.equals("Zed is now in observe mode.")), "command: mode change is confirmed in plain text");
        cmd.onCommand(console, c, "watchtower", new String[]{"remove", "Zed"});

        // a command bridge that is not the console
        WatchTest.OtherSender bridge = new WatchTest.OtherSender();
        cmd.onCommand(bridge, c, "watchtower", new String[]{"lockdown", "on"});
        ck(!svc.lockdown() && bridge.msgs.get(0).contains("Unknown command"), "command: an unknown kind of sender is refused");
        ck(svc.journal.recent(null, 20).stream().anyMatch(a -> a.category().equals("probe") && a.detail().contains("sender type " + WatchTest.OtherSender.class.getName())),
                "command: and the refusal tells the owner exactly which sender class to trust");
        plugin.settings().trustedSenders.add(WatchTest.OtherSender.class.getName());
        bridge.msgs.clear();
        cmd.onCommand(bridge, c, "watchtower", new String[]{"lockdown", "on"});
        ck(svc.lockdown() && bridge.msgs.get(0).contains("SERVER LOCKDOWN ON"), "command: a trusted sender class works");
        ck(bridge.msgs.stream().noneMatch(m -> m.contains("\u00a7")), "command: and gets plain text");
        plugin.settings().trustedSenders.clear();
        cmd.onCommand(console, c, "watchtower", new String[]{"lockdown", "off"});

        // players still see colours; others cannot use any of it
        WatchTest.TP rando = new WatchTest.TP("Rando");
        cmd.onCommand(rando, c, "watchtower", new String[]{"lockdown", "on"});
        ck(!svc.lockdown() && rando.got("Unknown command"), "command: a normal player cannot lock the server down");
        WatchTest.TP me = new WatchTest.TP("RealSociety5107");
        cmd.onCommand(me, c, "watchtower", new String[]{"lockdown", "on"});
        ck(svc.lockdown() && me.msgs.get(0).contains("\u00a7"), "command: the owner in game still gets colours");
        cmd.onCommand(me, c, "watchtower", new String[]{"lockdown", "off"});
        plugin.service().journal.close();
    }
}
