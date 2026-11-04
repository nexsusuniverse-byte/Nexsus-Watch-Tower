package com.nexuscraft.nexuswatchtower;

import java.util.Locale;
import java.util.UUID;

/** Who someone is. A Floodgate (Bedrock) name starts with a dot, which {@link #clean} drops for comparisons. */
record Identity(UUID uuid, String name) {

    String cleanName() {
        return clean(name);
    }

    static String clean(String n) {
        if (n == null) {
            return "";
        }
        String t = n.trim();
        if (t.startsWith(".")) {
            t = t.substring(1);
        }
        return t.toLowerCase(Locale.ROOT);
    }
}
