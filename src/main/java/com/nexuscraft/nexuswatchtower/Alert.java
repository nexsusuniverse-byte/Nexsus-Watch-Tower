package com.nexuscraft.nexuswatchtower;

/** One thing worth knowing about. {@code who} may be empty when no single player is responsible. */
record Alert(long time, Severity severity, String category, String who, String title, String detail) {
}
