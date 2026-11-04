package com.nexuscraft.nexuswatchtower;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fingerprints (SHA-256) of every plugin jar and the key server config files, saved when the owner
 * approves them. Anything added, removed or changed afterwards is reported, so a swapped-in jar or a
 * quietly edited config cannot go unnoticed.
 */
final class Baseline {

    record Diff(List<String> added, List<String> removed, List<String> changed) {
        boolean clean() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        int total() {
            return added.size() + removed.size() + changed.size();
        }
    }

    /** What was hashed last time for a file, so an untouched file (same size and modified time) is not hashed again. */
    record Seen(long size, long modified, String sha) {
    }

    private final File store;
    private final Map<String, String> saved = new TreeMap<>();
    private boolean existed;

    Baseline(File store) {
        this.store = store;
        if (store.isFile()) {
            existed = true;
            try {
                for (String line : Files.readAllLines(store.toPath(), StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    List<String> f = Esc.split(line);
                    if (f.size() >= 2) {
                        saved.put(f.get(0), f.get(1));
                    }
                }
            } catch (IOException e) {
                existed = false;
                saved.clear();
            }
        }
    }

    boolean existed() {
        return existed;
    }

    int size() {
        return saved.size();
    }

    Diff compare(Map<String, String> current) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : current.entrySet()) {
            String old = saved.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String k : saved.keySet()) {
            if (!current.containsKey(k)) {
                removed.add(k);
            }
        }
        return new Diff(added, removed, changed);
    }

    void save(Map<String, String> current) {
        StringBuilder b = new StringBuilder("# NexusWatchtower approved file fingerprints. Replaced by /watchtower baseline.\n");
        for (Map.Entry<String, String> e : new TreeMap<>(current).entrySet()) {
            b.append(Esc.join(e.getKey(), e.getValue())).append('\n');
        }
        AtomicFile.write(store, b.toString());
        saved.clear();
        saved.putAll(current);
        existed = true;
    }

    /** Every plugin jar plus the listed config files that exist, as paths relative to the server folder. */
    static List<File> collect(File serverRoot, List<String> extra) {
        List<File> out = new ArrayList<>();
        File[] jars = new File(serverRoot, "plugins").listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars != null) {
            for (File j : jars) {
                if (j.isFile()) {
                    out.add(j);
                }
            }
        }
        for (String rel : extra) {
            File f = new File(serverRoot, rel);
            if (f.isFile()) {
                out.add(f);
            }
        }
        return out;
    }

    static String relative(File root, File f) {
        String r = root.getAbsoluteFile().toPath().relativize(f.getAbsoluteFile().toPath()).toString();
        return r.replace(File.separatorChar, '/');
    }

    /** SHA-256 of every file, reusing {@code cache} for files whose size and modified time have not changed. */
    static Map<String, String> hashAll(File root, List<File> files, Map<String, Seen> cache) {
        Map<String, String> out = new TreeMap<>();
        for (File f : files) {
            String rel = relative(root, f);
            Seen s = cache.get(rel);
            long size = f.length();
            long mod = f.lastModified();
            try {
                if (s != null && s.size() == size && s.modified() == mod) {
                    out.put(rel, s.sha());
                } else {
                    String sha = sha256(f);
                    cache.put(rel, new Seen(size, mod, sha));
                    out.put(rel, sha);
                }
            } catch (IOException e) {
                out.put(rel, "unreadable");
            }
        }
        return out;
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Seen> newCache() {
        return new HashMap<>();
    }
}
