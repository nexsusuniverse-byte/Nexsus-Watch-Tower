package com.nexuscraft.nexuswatchtower;

/** The answer to "may this happen?". A denial carries the text the player sees. */
record Verdict(boolean allow, String message) {
    static final Verdict ALLOW = new Verdict(true, "");

    static Verdict deny(String message) {
        return new Verdict(false, message);
    }
}
