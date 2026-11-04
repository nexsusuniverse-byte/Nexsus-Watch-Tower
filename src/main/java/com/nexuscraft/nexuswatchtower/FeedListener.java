package com.nexuscraft.nexuswatchtower;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns what happens in the game into queue entries. Every handler runs at MONITOR (after everything else has
 * decided), does only string work, and never touches the network. There is NO check of who the player is:
 * owners, operators and players with bypass permissions are recorded like everyone else.
 */
final class FeedListener implements Listener {
    private final Feed feed;
    /** When each player came in, so the leave line can say how long they stayed. */
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

    FeedListener(Feed feed) {
        this.feed = feed;
    }

    private void emit(String type, Player p, String description, String target, String location) {
        FeedConfig c = feed.config();
        if (!c.enabled(type) || p == null) {
            return;
        }
        feed.offer(new FeedEvent(type, FeedClean.name(p.getName()), FeedClean.text(description, FeedClean.MAX_DESCRIPTION),
                FeedClean.text(target, FeedClean.MAX_TARGET), FeedClean.text(location, FeedClean.MAX_LOCATION)));
    }

    static String where(Location l) {
        if (l == null || l.getWorld() == null) {
            return "";
        }
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    static String duration(long ms) {
        long mins = Math.max(0, ms) / 60000L;
        if (mins < 1) {
            return "under a minute";
        }
        if (mins < 60) {
            return mins + " min";
        }
        return (mins / 60) + " h " + (mins % 60) + " min";
    }

    static String key(Keyed k) {
        return k.getKey().toString();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        try {
            emit("chat", e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()), "", "");
        } catch (RuntimeException ex) {
            feed.problem("chat", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        try {
            Player p = e.getPlayer();
            if (p != null && p.getUniqueId() != null) {
                joinedAt.put(p.getUniqueId(), System.currentTimeMillis());
            }
            emit("player_join", p, p != null && p.isOp() ? "joined the game (operator)" : "joined the game", "",
                    p == null ? "" : where(p.getLocation()));
        } catch (RuntimeException ex) {
            feed.problem("join", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        try {
            Player p = e.getPlayer();
            String stayed = "";
            if (p != null && p.getUniqueId() != null) {
                Long since = joinedAt.remove(p.getUniqueId());
                if (since != null) {
                    stayed = " after " + duration(System.currentTimeMillis() - since);
                }
            }
            emit("player_quit", p, "left the game" + stayed + (p != null && p.isOp() ? " (operator)" : ""), "",
                    p == null ? "" : where(p.getLocation()));
        } catch (RuntimeException ex) {
            feed.problem("quit", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        try {
            Player victim = e.getEntity();
            String msg = e.deathMessage() == null ? "" : PlainTextComponentSerializer.plainText().serialize(e.deathMessage());
            emit("player_death", victim, msg.isBlank() ? "died" : msg, "", where(victim.getLocation()));
            Player killer = victim.getKiller();
            if (killer != null && !killer.equals(victim)) {
                emit("player_kill", killer, "killed " + victim.getName(), victim.getName(), where(victim.getLocation()));
            }
        } catch (RuntimeException ex) {
            feed.problem("death", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent e) {
        try {
            LivingEntity dead = e.getEntity();
            if (dead == null || dead instanceof Player) {
                return;
            }
            Player killer = dead.getKiller();
            if (killer == null) {
                return;
            }
            String id = key(dead.getType());
            emit("mob_kill", killer, "killed " + dead.getType().getKey().getKey(), id, where(dead.getLocation()));
        } catch (RuntimeException ex) {
            feed.problem("mob kill", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        try {
            String id = key(e.getBlock().getType());
            String loc = where(e.getBlock().getLocation());
            emit("block_break", e.getPlayer(), "broke " + id + (loc.isEmpty() ? "" : " at " + loc), id, loc);
        } catch (RuntimeException ex) {
            feed.problem("block break", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        try {
            String id = key(e.getBlock().getType());
            String loc = where(e.getBlock().getLocation());
            emit("block_place", e.getPlayer(), "placed " + id + (loc.isEmpty() ? "" : " at " + loc), id, loc);
        } catch (RuntimeException ex) {
            feed.problem("block place", ex);
        }
    }

    /** Commands are recorded even when something cancelled them; those are marked "(blocked)". */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        try {
            if (!feed.config().enabled("command")) {
                return;
            }
            String shown = FeedClean.command(e.getMessage(), feed.config().hideCommands);
            emit("command", e.getPlayer(), e.isCancelled() ? shown + " (blocked)" : shown, "", "");
        } catch (RuntimeException ex) {
            feed.problem("command", ex);
        }
    }

    /** Recipe unlocks are also "advancements" in the game's files and would drown out the real ones, so they are skipped. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        try {
            var k = e.getAdvancement().getKey();
            if (k.getKey().startsWith("recipes/")) {
                return;
            }
            emit("advancement", e.getPlayer(), "earned " + k.getKey(), k.toString(), "");
        } catch (RuntimeException ex) {
            feed.problem("advancement", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        try {
            if (e.getEntity() instanceof Player p && feed.config().enabled("damage")) {
                emit("damage", p, String.format(Locale.ROOT, "took %.1f damage (%s)", e.getFinalDamage(), e.getCause().name()),
                        e.getCause().name(), where(p.getLocation()));
            }
        } catch (RuntimeException ex) {
            feed.problem("damage", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        try {
            if (e.getEntity() instanceof Player p && feed.config().enabled("item_pickup")) {
                var stack = e.getItem().getItemStack();
                String id = key(stack.getType());
                emit("item_pickup", p, "picked up " + stack.getAmount() + " x " + id, id, where(p.getLocation()));
            }
        } catch (RuntimeException ex) {
            feed.problem("item pickup", ex);
        }
    }
}
