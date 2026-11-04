package com.nexuscraft.nexuswatchtower;

import java.io.IOException;

/** Sends one request. Anything that goes wrong on the way is an IOException. */
interface FeedTransport {
    record Reply(int code, String body) {
    }

    Reply post(String url, String json, String secret, int timeoutSeconds) throws IOException;
}
