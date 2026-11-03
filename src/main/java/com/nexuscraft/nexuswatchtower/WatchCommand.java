package com.nexuscraft.nexuswatchtower;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /watchtower}. Only the console and the owners listed in config.yml may use it. To anyone else
 * it answers exactly like a command that does not exist, and the owner is told who tried.
 */
final class WatchCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("status", "list", "add", "remove", "mode", "lock", "unlock",
            "log", "digest", "ops", "baseline", "test", "reload", "lockdown", "snapshot", "help");

    private final WatchtowerPlugin plugin;
    private final WatchService svc;

    WatchCommand(WatchtowerPlugin plugin, WatchService svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    /** Players get colours; the console and the app's command bridge get plain text. */
    private static void say(CommandSender to, String message) {
        to.sendMessage(to instanceof Player ? message : ChatColor.stripColor(message));
    }

    private boolean authorized(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender || plugin.settings().trustedSenders.contains(sender.getClass().getName())) {
            return true;
        }
        return sender instanceof Player p && svc.owner(new Identity(p.getUniqueId(), p.getName()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!authorized(sender)) {
            say(sender, ChatColor.RED + "Unknown command. Type \"/help\" for help.");
            svc.probe(new Identity(sender instanceof Player p ? p.getUniqueId() : null, sender.getName()),
                    "/" + label + " " + String.join(" ", args)
                            + (sender instanceof Player ? "" : " [sender type " + sender.getClass().getName() + "]"), System.currentTimeMillis());
            return true;
        }
        long now = System.currentTimeMillis();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                for (String l : plugin.statusLines()) {
                    say(sender, ChatColor.GRAY + l);
                }
            }
            case "list" -> list(sender);
            case "add" -> add(sender, args, now);
            case "remove" -> {
                if (args.length < 2) {
                    say(sender, ChatColor.RED + "Usage: /watchtower remove <name>");
                } else if (svc.watchRemove(args[1], now)) {
                    say(sender, ChatColor.GREEN + args[1] + " is no longer on the watchlist.");
                } else {
                    say(sender, ChatColor.RED + "Nobody called " + args[1] + " is on the watchlist.");
                }
            }
            case "mode" -> mode(sender, args, now);
            case "lock" -> lock(sender, args, now);
            case "unlock" -> {
                if (args.length < 2) {
                    say(sender, ChatColor.RED + "Usage: /watchtower unlock <name>");
                } else if (svc.unlock(args[1], now)) {
                    say(sender, ChatColor.GREEN + args[1] + " is released.");
                } else {
                    say(sender, ChatColor.RED + "Nobody called " + args[1] + " is on the watchlist.");
                }
            }
            case "log" -> log(sender, args);
            case "digest" -> plugin.digestNow(sender);
            case "ops" -> {
                String names = String.join(", ", new java.util.TreeSet<>(plugin.operators().values()));
                say(sender, ChatColor.GRAY + "Operators: " + (names.isEmpty() ? "none" : names));
            }
            case "baseline" -> {
                say(sender, ChatColor.GRAY + "Fingerprinting your plugins and key files as the approved baseline...");
                plugin.tamperScanAsync(true, sender);
            }
            case "test" -> {
                svc.alerts.raise(Severity.YELLOW, "test", "", "Test alert. If you can read this on your phone, alerts work.", "", now);
                boolean app = plugin.app() != null;
                boolean discord = !plugin.settings().discordWebhook.isEmpty();
                say(sender, ChatColor.GRAY + "Test alert sent to the console" + (app ? ", the app" : "") + (discord ? ", Discord" : "")
                        + (app || discord ? "." : ". Nothing else is set up: add app.secret to config.yml to get phone alerts."));
                if (app) {
                    say(sender, ChatColor.GRAY + "App: " + plugin.app().status());
                }
            }
            case "lockdown" -> lockdown(sender, args, now);
            case "snapshot" -> say(sender, svc.snapshot(plugin.operators(), now));
            case "reload" -> {
                plugin.reload();
                say(sender, ChatColor.GREEN + "Watchtower settings reloaded. (Timers keep their old schedule until a restart.)");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        String[] lines = {
                "/watchtower status | list | ops | digest | test | reload",
                "/watchtower add <name> [observe|guard] [note]   -- watch someone (guard = keep operator, block dangerous commands, freeze on a tripwire)",
                "/watchtower mode <name> <observe|guard>   /watchtower remove <name>",
                "/watchtower lock <name>   /watchtower unlock <name>   -- freeze or release (lock works on anyone, watched or not)",
                "/watchtower lockdown on|off|status   -- freeze EVERYONE except the owners (log-in commands still work)",
                "/watchtower snapshot   -- one line of JSON with the current state, for the app",
                "/watchtower log [name] [count]   -- recent journal lines",
                "/watchtower baseline   -- approve the current plugins and key files as trusted",
        };
        for (String l : lines) {
            say(s, ChatColor.GRAY + l);
        }
    }

    private void lock(CommandSender sender, String[] args, long now) {
        if (args.length < 2) {
            say(sender, ChatColor.RED + "Usage: /watchtower lock <name>");
            return;
        }
        if (svc.watch.findByName(args[1]) == null) {
            Player online = Bukkit.getPlayerExact(args[1]);
            String err = svc.watchAdd(online == null ? args[1] : online.getName(), online == null ? null : online.getUniqueId(),
                    Watchlist.Mode.GUARD, "frozen from the app or console", now);
            if (err != null) {
                say(sender, ChatColor.RED + err);
                return;
            }
        }
        if (svc.lock(args[1], now)) {
            say(sender, ChatColor.GREEN + args[1] + " is frozen: no building, commands, containers or fighting.");
        } else {
            say(sender, ChatColor.RED + "Could not freeze " + args[1] + ".");
        }
    }

    private void lockdown(CommandSender sender, String[] args, long now) {
        String arg = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (arg) {
            case "on" -> say(sender, svc.setLockdown(true, now)
                    ? ChatColor.RED + "SERVER LOCKDOWN ON. Everyone except the owners is frozen. Lift it with /watchtower lockdown off."
                    : ChatColor.GRAY + "Lockdown was already on.");
            case "off" -> say(sender, svc.setLockdown(false, now)
                    ? ChatColor.GREEN + "Lockdown lifted. Everyone can play normally again (individually frozen players stay frozen)."
                    : ChatColor.GRAY + "Lockdown was already off.");
            case "status" -> say(sender, ChatColor.GRAY + "Server lockdown is " + (svc.lockdown() ? "ON" : "off") + ".");
            default -> say(sender, ChatColor.RED + "Usage: /watchtower lockdown <on|off|status>");
        }
    }

    private void list(CommandSender sender) {
        List<Watchlist.Entry> all = svc.watch.all();
        if (all.isEmpty()) {
            say(sender, ChatColor.GRAY + "Nobody is on the watchlist. Add someone with /watchtower add <name> guard");
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
        for (Watchlist.Entry e : all) {
            say(sender, ChatColor.GRAY + "- " + e.name + " [" + e.mode.name().toLowerCase(Locale.ROOT) + "]"
                    + (e.locked ? ChatColor.RED + " FROZEN" + ChatColor.GRAY : "")
                    + " since " + fmt.format(Instant.ofEpochMilli(e.added).atZone(plugin.settings().zone))
                    + (e.note.isEmpty() ? "" : " -- " + e.note));
        }
    }

    private void add(CommandSender sender, String[] args, long now) {
        if (args.length < 2) {
            say(sender, ChatColor.RED + "Usage: /watchtower add <name> [observe|guard] [note]");
            return;
        }
        String name = args[1];
        Watchlist.Mode mode = Watchlist.Mode.GUARD;
        int noteFrom = 2;
        if (args.length >= 3) {
            Watchlist.Mode m = Watchlist.Mode.parse(args[2]);
            if (m != null) {
                mode = m;
                noteFrom = 3;
            }
        }
        String note = noteFrom < args.length ? String.join(" ", Arrays.copyOfRange(args, noteFrom, args.length)) : "";
        Player online = Bukkit.getPlayerExact(name);
        UUID uuid = online == null ? null : online.getUniqueId();
        String shownName = online == null ? name : online.getName();
        String err = svc.watchAdd(shownName, uuid, mode, note, now);
        if (err != null) {
            say(sender, ChatColor.RED + err);
            return;
        }
        say(sender, ChatColor.GREEN + shownName + " is now watched in " + mode.name().toLowerCase(Locale.ROOT) + " mode"
                + (uuid == null ? " (matched by name until they next join)." : "."));
    }

    private void mode(CommandSender sender, String[] args, long now) {
        Watchlist.Mode m = args.length >= 3 ? Watchlist.Mode.parse(args[2]) : null;
        if (m == null) {
            say(sender, ChatColor.RED + "Usage: /watchtower mode <name> <observe|guard>");
        } else if (svc.setMode(args[1], m, now)) {
            say(sender, ChatColor.GREEN + args[1] + " is now in " + m.name().toLowerCase(Locale.ROOT) + " mode.");
        } else {
            say(sender, ChatColor.RED + "Nobody called " + args[1] + " is on the watchlist.");
        }
    }

    private void log(CommandSender sender, String[] args) {
        String who = null;
        int count = 15;
        for (int i = 1; i < args.length; i++) {
            try {
                count = Math.max(1, Math.min(30, Integer.parseInt(args[i])));
            } catch (NumberFormatException e) {
                who = args[i];
            }
        }
        List<Alert> lines = plugin.journal().recent(who, count);
        if (lines.isEmpty()) {
            say(sender, ChatColor.GRAY + "Nothing recorded" + (who == null ? "." : " for " + who + "."));
            return;
        }
        for (Alert a : lines) {
            say(sender, ChatColor.GRAY + Journal.describe(a, plugin.settings().zone));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!authorized(sender)) {
            return out;
        }
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && List.of("remove", "mode", "lock", "unlock", "log").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Watchlist.Entry e : svc.watch.all()) {
                if (e.name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(e.name);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lockdown")) {
            out.addAll(List.of("on", "off", "status"));
        } else if (args.length == 3 && List.of("add", "mode").contains(args[0].toLowerCase(Locale.ROOT))) {
            out.addAll(List.of("observe", "guard"));
        }
        return out;
    }
}
