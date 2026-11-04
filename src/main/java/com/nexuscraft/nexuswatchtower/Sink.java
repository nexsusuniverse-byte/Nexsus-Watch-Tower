package com.nexuscraft.nexuswatchtower;

/** Where alerts go. The plugin sends them to Discord, to the app, and to the owner in game. Must be safe from any thread. */
interface Sink {
    /** An alert to deliver now. {@code text} is the ready-to-send line; {@code ping} is true for RED. */
    void send(Alert alert, String text, boolean ping);

    /** A batched activity feed message (the watched players' recent actions). */
    void feed(String text);
}
