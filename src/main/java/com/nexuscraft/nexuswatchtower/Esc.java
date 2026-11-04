package com.nexuscraft.nexuswatchtower;

import java.util.ArrayList;
import java.util.List;

/** Tab-separated record fields with backslash escapes, so notes and chat lines can hold anything. */
final class Esc {
    private Esc() {
    }

    static String enc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\t' -> b.append("\\t");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    static String dec(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't' -> b.append('\t');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    default -> b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    static String join(Object... fields) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                b.append('\t');
            }
            b.append(enc(fields[i] == null ? "" : String.valueOf(fields[i])));
        }
        return b.toString();
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.split("\t", -1)) {
            out.add(dec(part));
        }
        return out;
    }
}
