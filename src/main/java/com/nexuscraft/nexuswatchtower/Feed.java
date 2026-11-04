package com.nexuscraft.nexuswatchtower;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The queue and the sender. Listeners only ever call {@link #offer}, which never waits and never touches the
 * network. One worker thread calls {@link #flushAll} every couple of seconds to post batches of up to 50.
 */
final class Feed {
    static final int QUEUE_LIMIT = 5000;
    static final int MAX_BATCHES_PER_FLUSH = 20;
    static final long[] BACKOFF_MS = {1000L, 2000L, 4000L};

    interface Log {
        void info(String s);

        void warn(String s);
    }

    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private static final Pattern PUSHED = Pattern.compile("\"pushed\"\\s*:\\s*(\\d+)");

    private final ArrayBlockingQueue<FeedEvent> queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private final FeedTransport transport;
    private final Log log;
    private final Sleeper sleeper;
    private final LongSupplier clock;

    private volatile FeedConfig cfg;
    private volatile boolean halted;
    private volatile int lastHttp;
    private volatile String lastError = "";
    private volatile long lastOkAt;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong pushed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final java.util.Map<String, Long> lastWarn = new java.util.concurrent.ConcurrentHashMap<>();

    Feed(FeedConfig cfg, FeedTransport transport, Log log, Sleeper sleeper, LongSupplier clock) {
        this.cfg = cfg;
        this.transport = transport;
        this.log = log;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** Takes in a new configuration (after a reload). A stopped feed (wrong secret) starts again. */
    void reconfigure(FeedConfig next) {
        cfg = next;
        halted = false;
        lastWarn.clear();
        if (next.urlProblem != null) {
            log.warn("Not sending: " + next.urlProblem + ".");
        }
    }

    FeedConfig config() {
        return cfg;
    }

    /** Safe to call from any thread, any time: puts the event in the queue or counts it as dropped. Never waits. */
    boolean offer(FeedEvent e) {
        if (!cfg.enabled(e.type())) {
            return false;
        }
        return force(e);
    }

    /** Same, for events that must go out whatever the per-type switches say (the test command). */
    boolean force(FeedEvent e) {
        if (halted) {
            dropped.incrementAndGet();
            return false;
        }
        if (!queue.offer(e)) {
            dropped.incrementAndGet();
            warnLimited("full", "The feed queue is full (" + QUEUE_LIMIT + "), so new events are being dropped.");
            return false;
        }
        accepted.incrementAndGet();
        return true;
    }

    /** Called by the worker thread. {@code finalRun} (shutting down) means one quick try per batch, no retries. */
    void flushAll(boolean finalRun) {
        FeedConfig c = cfg;
        if (halted || queue.isEmpty()) {
            return;
        }
        if (c.urlProblem != null) {
            lastError = c.urlProblem;
            warnLimited("url", "Not sending: " + c.urlProblem + ".");
            return;
        }
        if (!c.hasSecret()) {
            lastError = "webhook-secret is empty";
            warnLimited("secret", "webhook-secret is empty in config.yml, so nothing is being sent. Events wait in the queue.");
            return;
        }
        for (int i = 0; i < MAX_BATCHES_PER_FLUSH && !halted; i++) {
            List<FeedEvent> batch = new ArrayList<>(c.batchSize);
            queue.drainTo(batch, c.batchSize);
            if (batch.isEmpty()) {
                return;
            }
            send(c, batch, finalRun);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    private void send(FeedConfig c, List<FeedEvent> batch, boolean finalRun) {
        String json = FeedJson.batch(c.serverId, batch);
        int attempts = finalRun ? 1 : 1 + BACKOFF_MS.length;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int code;
            String body = "";
            try {
                FeedTransport.Reply r = transport.post(c.endpoint, json, c.secret, finalRun ? 5 : 20);
                code = r.code();
                body = r.body();
            } catch (IOException e) {
                code = -1;
                lastError = "network problem (" + e.getClass().getSimpleName() + ")";
            }
            if (code >= 200 && code < 300) {
                lastHttp = code;
                lastError = "";
                lastOkAt = clock.getAsLong();
                sent.addAndGet(batch.size());
                Matcher m = PUSHED.matcher(body);
                long p = m.find() ? Long.parseLong(m.group(1)) : 0L;
                pushed.addAndGet(p);
                if (c.debug) {
                    log.info("Sent " + batch.size() + " event(s), HTTP " + code + ", pushed " + p + ".");
                }
                return;
            }
            if (code == 401) {
                lastHttp = 401;
                lastError = "401 unauthorized: the webhook-secret is wrong";
                halted = true;
                dropped.addAndGet(batch.size() + queue.size());
                queue.clear();
                log.warn("*** The app refused the webhook-secret (HTTP 401). Sending is STOPPED. Put the real "
                        + "KAI_ROSS_WEBHOOK_SECRET in plugins/NexusFeed/config.yml and run /nexusfeed reload. ***");
                return;
            }
            if (code > 0) {
                lastHttp = code;
                lastError = "HTTP " + code;
            }
            boolean retryable = code == -1 || code == 429 || code >= 500;
            if (!retryable) {
                dropped.addAndGet(batch.size());
                warnLimited("http" + code, "The app rejected a batch of " + batch.size() + " event(s): HTTP " + code
                        + shorten(body) + ". Those events were dropped.");
                return;
            }
            if (attempt + 1 < attempts) {
                try {
                    sleeper.sleep(BACKOFF_MS[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dropped.addAndGet(batch.size());
                    return;
                }
            }
        }
        dropped.addAndGet(batch.size());
        warnLimited("retry", "Giving up on a batch of " + batch.size() + " event(s) after " + attempts + " tries (" + lastError
                + "). Those events were dropped.");
    }

    private static String shorten(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return " " + FeedClean.text(body, 120);
    }

    /** One warning per kind per minute, so a long outage cannot flood the console. */
    private void warnLimited(String key, String message) {
        long now = clock.getAsLong();
        Long last = lastWarn.get(key);
        if (last == null || now - last >= 60_000L) {
            lastWarn.put(key, now);
            log.warn(message);
        }
    }

    /** A listener hit an unexpected error: say so (once a minute at most) instead of breaking the game event. */
    void problem(String where, RuntimeException e) {
        warnLimited("listener-" + where, "Could not record a " + where + " event: " + e.getClass().getSimpleName() + ".");
    }

    /** A copy of what is waiting, oldest first (for tests and diagnostics). */
    List<FeedEvent> snapshot() {
        return new ArrayList<>(queue);
    }

    int queued() {
        return queue.size();
    }

    boolean halted() {
        return halted;
    }

    int lastHttp() {
        return lastHttp;
    }

    String lastError() {
        return lastError;
    }

    long lastOkAt() {
        return lastOkAt;
    }

    long accepted() {
        return accepted.get();
    }

    long sent() {
        return sent.get();
    }

    long pushed() {
        return pushed.get();
    }

    long dropped() {
        return dropped.get();
    }
}
