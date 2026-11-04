package com.nexuscraft.nexuswatchtower;

/** INFO is only journaled and summarised. YELLOW is sent right away. RED is sent right away and pings the owner. */
enum Severity {
    INFO, YELLOW, RED
}
