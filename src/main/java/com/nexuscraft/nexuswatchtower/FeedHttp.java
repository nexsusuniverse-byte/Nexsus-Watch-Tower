package com.nexuscraft.nexuswatchtower;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The real thing, using the HTTP client that comes with Java. It never follows redirects, so the secret cannot be sent somewhere else. */
final class FeedHttp implements FeedTransport {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Reply post(String url, String json, String secret, int timeoutSeconds) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("x-kai-secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Reply(res.statusCode(), res.body() == null ? "" : res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IllegalArgumentException e) {
            throw new IOException("bad address");
        }
    }
}
