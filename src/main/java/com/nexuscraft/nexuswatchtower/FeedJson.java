package com.nexuscraft.nexuswatchtower;

import java.util.List;

/** Just enough JSON writing for the one request this plugin makes. */
final class FeedJson {
    private FeedJson() {
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    static String batch(String serverId, List<FeedEvent> events) {
        StringBuilder b = new StringBuilder(64 + events.size() * 160);
        b.append("{\"server_id\":").append(quote(serverId)).append(",\"source\":\"plugin\",\"events\":[");
        boolean first = true;
        for (FeedEvent e : events) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append("{\"event_type\":").append(quote(e.type())).append(",\"player_name\":").append(quote(e.player()));
            if (!e.description().isEmpty()) {
                b.append(",\"description\":").append(quote(e.description()));
            }
            if (!e.target().isEmpty()) {
                b.append(",\"target_name\":").append(quote(e.target()));
            }
            if (!e.location().isEmpty()) {
                b.append(",\"location\":").append(quote(e.location()));
            }
            b.append('}');
        }
        return b.append("]}").toString();
    }
}
