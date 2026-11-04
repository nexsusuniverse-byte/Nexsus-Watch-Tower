package com.nexuscraft.nexuswatchtower;

/** One thing a player did, already cleaned. {@code target} and {@code location} may be empty. */
record FeedEvent(String type, String player, String description, String target, String location) {
}
