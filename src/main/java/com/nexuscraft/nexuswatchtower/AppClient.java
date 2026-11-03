package com.nexuscraft.nexuswatchtower;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * The Kai Ross Nexus app channel. Alerts go to {@code /functions/pluginAlert} (phone notifications), the
 * guarded players' activity goes to {@code /functions/ingestGameEvent} with {@code fire_kai:false} so the
 * Kairos director never reacts to it. Everything is queued and sent by one background thread, so the game
 * thread never waits on the network. The app allows 20 alerts a minute; this stays under that, always
 * lets RED through first, and folds anything it has to drop into one "held back" notice.
 */
final class AppClient {

    /** What the app answered. {@code code} is -1 when it could not be reached. */
    record Reply(int code, String body) {
        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    /** The network. Replaced in tests. */
    interface Transport {
        Reply post(String url, String body, String secret, int timeoutSeconds);
    }

    private static final class Job {
        final String function;
        final String body;
        final boolean red;
        final boolean notice;
        final Consumer<Reply> done;

        Job(String function, String body, boolean red, boolean notice, Consumer<Reply> done) {
            this.function = function;
            this.body = body;
            this.red = red;
            this.notice = notice;
            this.done = done;
        }
    }

    static final int HARD_LIMIT = 20;
    private static final int QUEUE_MAX = 200;
    private static final long[] RETRY_DELAYS_MS = {2_000L, 6_000L, 15_000L};

    private final String base;
    private final String secret;
    private final String serverId;
    private final String pluginLabel;
    private final int perMinute;
    private final Logger log;
    private final Transport transport;
    private final double sleepScale;
    private final LongSupplier clock;
    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>(QUEUE_MAX);
    private final Deque<Long> sentTimes = new ArrayDeque<>();
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile int lastCode;
    private volatile boolean closing;
    private volatile boolean stopped;
    private Thread worker;
    private long lastWarnAt;
    private int lastWarnCode;

    AppClient(String base, String secret, String serverId, String pluginLabel, int perMinute, Logger log) {
        this(base, secret, serverId, pluginLabel, perMinute, log, realTransport(log), 1.0, System::currentTimeMillis);
    }

    AppClient(String base, String secret, String serverId, String pluginLabel, int perMinute, Logger log,
              Transport transport, double sleepScale, LongSupplier clock) {
        this.base = base;
        this.secret = secret;
        this.serverId = serverId;
        this.pluginLabel = pluginLabel;
        this.perMinute = Math.max(1, Math.min(HARD_LIMIT, perMinute));
        this.log = log;
        this.transport = transport;
        this.sleepScale = sleepScale;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- bodies

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 3)) + "...";
    }

    static String appSeverity(Severity s) {
        return switch (s) {
            case RED -> "critical";
            case YELLOW -> "warning";
            default -> "info";
        };
    }

    /** Maps this plugin's categories onto the app's: plugin | server | player | world | security | economy | system. */
    static String appCategory(String c) {
        if (c == null) {
            return "security";
        }
        return switch (c) {
            case "plugin" -> "plugin";
            case "server" -> "server";
            case "watch", "chat", "coordination", "container" -> "player";
            case "mass-blocks", "hazard", "command-block" -> "world";
            case "test", "digest", "system" -> "system";
            default -> "security";
        };
    }

    String alertBody(Alert a, boolean push) {
        String who = a.who() == null ? "" : a.who();
        String title = clip((who.isEmpty() ? "" : who + ": ") + a.title(), 80);
        return noticeBody(title, a.detail(), appSeverity(a.severity()), appCategory(a.category()), push);
    }

    String noticeBody(String title, String body, String severity, String category, boolean push) {
        return "{\"title\":" + Webhook.jsonString(clip(title, 80))
                + ",\"body\":" + Webhook.jsonString(clip(body, 900))
                + ",\"severity\":" + Webhook.jsonString(severity)
                + ",\"category\":" + Webhook.jsonString(category)
                + ",\"source\":\"NexusWatchtower\""
                + ",\"plugin\":" + Webhook.jsonString(pluginLabel)
                + ",\"url\":\"/command\""
                + ",\"push\":" + push + "}";
    }

    /** The guarded players' batched activity. fire_kai is always false: the director must never act on this. */
    String eventBody(String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n")) {
            if (!l.isBlank() && lines.size() < 40) {
                lines.add(clip(l, 200));
            }
        }
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            arr.append(i == 0 ? "" : ",").append(Webhook.jsonString(lines.get(i)));
        }
        arr.append(']');
        return "{\"event_type\":\"operator_activity\""
                + ",\"description\":" + Webhook.jsonString(clip(text, 900))
                + ",\"severity\":\"info\""
                + ",\"server_id\":" + Webhook.jsonString(serverId)
                + ",\"source\":\"NexusWatchtower\""
                + ",\"fire_kai\":false"
                + ",\"details\":{\"lines\":" + arr + "}}";
    }

    // ---------------------------------------------------------------- offering work

    void offerAlert(Alert a, boolean pushYellow) {
        boolean push = a.severity() == Severity.RED || (a.severity() == Severity.YELLOW && pushYellow);
        enqueue(new Job("pluginAlert", alertBody(a, push), a.severity() == Severity.RED, false, null));
    }

    void offerNotice(String title, String body, String severity, String category, boolean push, Consumer<Reply> done) {
        enqueue(new Job("pluginAlert", noticeBody(title, body, severity, category, push), "critical".equals(severity), true, done));
    }

    void offerFeed(String text) {
        enqueue(new Job("ingestGameEvent", eventBody(text), false, false, null));
    }

    private void enqueue(Job j) {
        if (stopped) {
            return;
        }
        if (!queue.offer(j)) {
            if (j.red) {
                Job old = queue.pollFirst();
                if (old != null) {
                    dropped.incrementAndGet();
                }
                queue.offerFirst(j);
            } else {
                dropped.incrementAndGet();
            }
        }
    }

    // ---------------------------------------------------------------- the worker

    void start() {
        worker = new Thread(this::run, "NexusWatchtower-app");
        worker.setDaemon(true);
        worker.start();
    }

    /** Lets already-queued messages (such as "server stopping") go out, for at most {@code millis}, then stops. */
    void shutdown(long millis) {
        closing = true;
        Thread w = worker;
        if (w != null) {
            try {
                w.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (w.isAlive()) {
                w.interrupt();
            }
        }
        stopped = true;
    }

    private void run() {
        try {
            while (!stopped) {
                Job j = queue.poll(closing ? 50 : 500, TimeUnit.MILLISECONDS);
                if (j == null) {
                    if (closing) {
                        return;
                    }
                    flushHeldNotice();
                    continue;
                }
                process(j);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Package-visible so tests can drive the worker one job at a time without threads. */
    void drainForTest() {
        Job j;
        while ((j = queue.poll()) != null) {
            process(j);
        }
        flushHeldNotice();
        Job extra;
        while ((extra = queue.poll()) != null) {
            process(extra);
        }
    }

    private boolean slotFree(boolean red, long now) {
        while (!sentTimes.isEmpty() && now - sentTimes.peekFirst() >= 60_000L) {
            sentTimes.pollFirst();
        }
        return sentTimes.size() < (red ? HARD_LIMIT : perMinute);
    }

    private long clock() {
        return clock.getAsLong();
    }

    private void process(Job j) {
        long now = clock();
        if (!slotFree(j.red, now)) {
            if (j.red && !closing && !stopped) {
                long wait = 60_000L - (now - sentTimes.peekFirst()) + 50L;
                if (!pause(wait)) {
                    return;
                }
                now = clock();
            } else {
                dropped.incrementAndGet();
                if (j.done != null) {
                    j.done.accept(new Reply(429, "held back by Watchtower's own per-minute limit"));
                }
                return;
            }
        }
        Reply r = null;
        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            r = transport.post(base + "/functions/" + j.function, j.body, secret, closing ? 5 : 10);
            sentTimes.addLast(clock());
            lastCode = r.code();
            if (r.ok()) {
                sent.incrementAndGet();
                break;
            }
            warn(r);
            boolean retryable = r.code() == 429 || r.code() >= 500 || r.code() < 0;
            if (!retryable || closing || attempt == RETRY_DELAYS_MS.length || !pause(RETRY_DELAYS_MS[attempt])) {
                failed.incrementAndGet();
                if (!j.notice) {
                    dropped.incrementAndGet();
                }
                break;
            }
            if (!slotFree(true, clock())) {
                failed.incrementAndGet();
                dropped.incrementAndGet();
                break;
            }
        }
        if (j.done != null && r != null) {
            j.done.accept(r);
        }
    }

    /** One "held back" notice for everything that was dropped, sent when there is room. */
    private void flushHeldNotice() {
        int n = dropped.get();
        if (n == 0 || stopped || !slotFree(false, clock())) {
            return;
        }
        dropped.addAndGet(-n);
        Job j = new Job("pluginAlert", noticeBody("Watchtower held back " + n + " message(s)",
                "The app accepts 20 per minute, or the app could not be reached. Check /watchtower log for the full list.",
                "warning", "system", false), false, true, null);
        process(j);
    }

    /** Sleeps unless closing. Returns false if interrupted or closing. */
    private boolean pause(long millis) {
        if (closing || stopped) {
            return false;
        }
        long scaled = (long) (millis * sleepScale);
        if (scaled <= 0) {
            return true;
        }
        try {
            Thread.sleep(scaled);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Logs why the app said no, at most once per few seconds per code. Never logs the secret or the request. */
    private void warn(Reply r) {
        long now = clock();
        if (r.code() == lastWarnCode && now - lastWarnAt < 5_000L) {
            return;
        }
        lastWarnCode = r.code();
        lastWarnAt = now;
        if (r.code() < 0) {
            log.warning("Could not reach the Kai Ross Nexus app.");
        } else {
            log.warning("The app answered " + r.code() + ": " + clip(r.body(), 200));
        }
    }

    // ---------------------------------------------------------------- reporting

    int queued() {
        return queue.size();
    }

    int lastCode() {
        return lastCode;
    }

    int sentCount() {
        return sent.get();
    }

    int failedCount() {
        return failed.get();
    }

    int droppedCount() {
        return dropped.get();
    }

    String status() {
        String code = lastCode == 0 ? "no call yet" : lastCode < 0 ? "last call could not connect" : "last answer " + lastCode;
        return "on (" + sent.get() + " sent, " + failed.get() + " failed, " + queue.size() + " waiting, " + code + ")";
    }

    // ---------------------------------------------------------------- the real network

    static Transport realTransport(Logger log) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, body, secret, timeout) -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout))
                        .header("Content-Type", "application/json")
                        .header("x-kai-secret", secret)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                return new Reply(res.statusCode(), res.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Reply(-1, "");
            } catch (Exception e) {
                return new Reply(-1, e.getClass().getSimpleName().toLowerCase(Locale.ROOT));
            }
        };
    }
}
