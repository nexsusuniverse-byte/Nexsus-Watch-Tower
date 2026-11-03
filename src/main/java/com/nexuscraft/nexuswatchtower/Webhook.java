package com.nexuscraft.nexuswatchtower;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Posts to a private Discord channel webhook (optional). The app has its own client, {@link AppClient}. */
final class Webhook {

    private static final List<String> DISCORD_PREFIXES = List.of(
            "https://discord.com/api/webhooks/",
            "https://discordapp.com/api/webhooks/",
            "https://canary.discord.com/api/webhooks/",
            "https://ptb.discord.com/api/webhooks/");

    private Webhook() {
    }

    static boolean validDiscord(String url) {
        if (url == null) {
            return false;
        }
        for (String p : DISCORD_PREFIXES) {
            if (url.startsWith(p) && url.length() > p.length()) {
                return true;
            }
        }
        return false;
    }

    /** The app's address must be https and have a host. */
    static boolean validApp(String url) {
        if (url == null || !url.startsWith("https://") || url.length() <= "https://".length()) {
            return false;
        }
        try {
            URI u = URI.create(url);
            return u.getHost() != null && !u.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean validUserId(String id) {
        return id != null && id.matches("\\d{15,25}");
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * The Discord body. Mentions are off for everything except the owner's own user id on a RED alert,
     * so a hostile player name can never ping anybody.
     */
    static String discordPayload(String content, boolean ping, String userId) {
        String text = content.length() > 1900 ? content.substring(0, 1900) + "..." : content;
        if (ping && validUserId(userId)) {
            return "{\"content\":" + jsonString("<@" + userId + "> " + text)
                    + ",\"allowed_mentions\":{\"parse\":[],\"users\":[" + jsonString(userId) + "]}}";
        }
        return "{\"content\":" + jsonString(text) + ",\"allowed_mentions\":{\"parse\":[]}}";
    }

    /** Blocking. The plugin only ever calls this off the main thread (and once, briefly, as the plugin shuts down). */
    static void post(String url, String body, String bearer, int timeoutSeconds, Logger log) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json");
            if (bearer != null && !bearer.isBlank()) {
                req.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<Void> res = client.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 300) {
                log.warning("An alert endpoint answered " + res.statusCode() + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not reach an alert endpoint: " + e.getClass().getSimpleName());
        }
    }
}
