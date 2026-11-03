package com.nexuscraft.nexuswatchtower;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

import java.util.logging.Level;

/**
 * Turns server events into calls on {@link WatchService}. Every handler fails open: an unexpected error
 * is logged and the event is left alone, so Watchtower can never be the reason players cannot play.
 */
public final class WatchListener implements Listener {

    private final WatchtowerPlugin plugin;
    private final WatchService service;

    WatchListener(WatchtowerPlugin plugin, WatchService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private static Identity id(Player p) {
        return new Identity(p.getUniqueId(), p.getName());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private void fail(String what, RuntimeException e) {
        plugin.getLogger().log(Level.WARNING, "Watchtower could not handle " + what + "; leaving it alone.", e);
    }

    private static String worldOf(Location l) {
        return l == null || l.getWorld() == null ? "?" : l.getWorld().getName();
    }

    private static int bx(Location l) {
        return l == null ? 0 : l.getBlockX();
    }

    private static int by(Location l) {
        return l == null ? 0 : l.getBlockY();
    }

    private static int bz(Location l) {
        return l == null ? 0 : l.getBlockZ();
    }

    private void deny(Player p, Verdict v) {
        p.sendMessage(ChatColor.RED + v.message());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        try {
            Block b = event.getBlock();
            Location l = b.getLocation();
            Verdict v = service.block(id(event.getPlayer()), false, b.getType().name(), worldOf(l), bx(l), by(l), bz(l), now());
            if (!v.allow()) {
                event.setCancelled(true);
                deny(event.getPlayer(), v);
            }
        } catch (RuntimeException e) {
            fail("a block break", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        try {
            Block b = event.getBlock();
            Location l = b.getLocation();
            Verdict v = service.block(id(event.getPlayer()), true, b.getType().name(), worldOf(l), bx(l), by(l), bz(l), now());
            if (!v.allow()) {
                event.setCancelled(true);
                deny(event.getPlayer(), v);
            }
        } catch (RuntimeException e) {
            fail("a block placement", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        try {
            Block b = event.getBlockClicked();
            Location l = b == null ? null : b.getLocation();
            Verdict v = service.bucket(id(event.getPlayer()), String.valueOf(event.getBucket()), worldOf(l), bx(l), by(l), bz(l), now());
            if (!v.allow()) {
                event.setCancelled(true);
                deny(event.getPlayer(), v);
            }
        } catch (RuntimeException e) {
            fail("a bucket", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        try {
            Player p = event.getPlayer();
            if (p == null) {
                return;
            }
            BlockIgniteEvent.IgniteCause cause = event.getCause();
            if (cause != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL && cause != BlockIgniteEvent.IgniteCause.FIREBALL) {
                return;
            }
            Location l = event.getBlock().getLocation();
            Verdict v = service.ignite(id(p), worldOf(l), bx(l), by(l), bz(l), now());
            if (!v.allow()) {
                event.setCancelled(true);
                deny(p, v);
            }
        } catch (RuntimeException e) {
            fail("a fire", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        try {
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_WITHER) {
                Entity e = event.getEntity();
                Location l = e == null ? null : e.getLocation();
                service.witherBuilt(worldOf(l), bx(l), by(l), bz(l), now());
            }
        } catch (RuntimeException e) {
            fail("a spawn", e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        try {
            Player p = event.getPlayer();
            Verdict v = service.command(id(p), event.getMessage(), now());
            if (!v.allow()) {
                event.setCancelled(true);
                deny(p, v);
            }
        } catch (RuntimeException e) {
            fail("a command", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        try {
            service.chat(id(event.getPlayer()), event.getMessage(), now());
        } catch (RuntimeException e) {
            fail("a chat line", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player p = event.getPlayer();
            service.joined(id(p), p.isOp(), now());
        } catch (RuntimeException e) {
            fail("a join", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            service.left(id(event.getPlayer()), now());
        } catch (RuntimeException e) {
            fail("a quit", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        try {
            if (!(event.getPlayer() instanceof Player p)) {
                return;
            }
            Verdict v = service.frozen(id(p));
            if (!v.allow()) {
                event.setCancelled(true);
                deny(p, v);
                return;
            }
            Location l = event.getInventory() == null ? null : event.getInventory().getLocation();
            if (l != null) {
                service.container(id(p), event.getInventory().getType().name(), worldOf(l), bx(l), by(l), bz(l), now());
            }
        } catch (RuntimeException e) {
            fail("a container", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        try {
            Player p = event.getPlayer();
            Verdict v = service.frozen(id(p));
            if (!v.allow()) {
                event.setCancelled(true);
            }
        } catch (RuntimeException e) {
            fail("an interaction", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        try {
            if (event.getDamager() instanceof Player p) {
                Verdict v = service.frozen(id(p));
                if (!v.allow()) {
                    event.setCancelled(true);
                }
            }
        } catch (RuntimeException e) {
            fail("a hit", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginOff(PluginDisableEvent event) {
        try {
            String name = event.getPlugin().getName();
            if (name.equalsIgnoreCase(plugin.getName())) {
                if (!Bukkit.isStopping()) {
                    plugin.closingNow();
                    service.pluginDisabled(name, false, now());
                } else {
                    plugin.closingNow();
                }
                return;
            }
            service.pluginDisabled(name, Bukkit.isStopping(), now());
        } catch (RuntimeException e) {
            fail("a plugin switch-off", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginOn(PluginEnableEvent event) {
        try {
            service.pluginEnabled(event.getPlugin().getName(), now());
        } catch (RuntimeException e) {
            fail("a plugin switch-on", e);
        }
    }
}
