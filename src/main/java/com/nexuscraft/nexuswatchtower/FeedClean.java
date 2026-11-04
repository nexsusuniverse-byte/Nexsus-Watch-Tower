package com.nexuscraft.nexuswatchtower;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything that leaves the server goes through here first: colour codes and control characters are
 * removed, lengths are capped, IP addresses are blanked out, and the arguments of password-type
 * commands are hidden.
 */
final class FeedClean {
    static final int MAX_DESCRIPTION = 400;
    static final int MAX_TARGET = 100;
    static final int MAX_LOCATION = 100;
    static final int MAX_NAME = 17;

    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\w)(?::\\d{1,5})?");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![\\w:])(?:(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}"
            + "|(?:[0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,5}"
            + "|::(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})(?![\\w:])");
    private static final Pattern NAME_BAD = Pattern.compile("[^A-Za-z0-9_.]");

    private FeedClean() {
    }

    /** Removes Minecraft colour/format codes, control characters and extra blanks, blanks out IP addresses, then caps the length. */
    static String text(String raw, int max) {
        if (raw == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        String s = b.toString().replaceAll(" {2,}", " ").trim();
        s = IPV4.matcher(s).replaceAll("[ip]");
        s = IPV6.matcher(s).replaceAll("[ip]");
        return cap(s, max);
    }

    static String cap(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** The app only accepts 3 to 17 characters of letters, digits, underscore and dot; anything else is swapped for an underscore. */
    static String name(String raw) {
        String s = raw == null ? "" : NAME_BAD.matcher(raw).replaceAll("_");
        if (s.length() > MAX_NAME) {
            s = s.substring(0, MAX_NAME);
        }
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 3) {
            b.append('_');
        }
        return b.toString();
    }

    /**
     * A command as it is shown to the owner. {@code hide} maps a command name to how many words after it stay
     * visible: 0 for password commands (everything hidden), 1 for private messages (the person is shown, the
     * text is not). The name is matched in any capitals and with or without a plugin prefix ("nexusgate:login").
     */
    static String command(String raw, Map<String, Integer> hide) {
        String s = text(raw, MAX_DESCRIPTION);
        if (s.isEmpty()) {
            return s;
        }
        String trimmed = s.startsWith("/") ? s.substring(1) : s;
        String[] words = trimmed.split(" ");
        String head = words[0];
        String base = head.toLowerCase(Locale.ROOT);
        int colon = base.lastIndexOf(':');
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }
        Integer keep = hide.get(base);
        if (keep != null && words.length - 1 > keep) {
            StringBuilder b = new StringBuilder("/").append(head);
            for (int i = 1; i <= keep; i++) {
                b.append(' ').append(words[i]);
            }
            return b.append(" [hidden]").toString();
        }
        return s.startsWith("/") ? s : "/" + s;
    }
}
