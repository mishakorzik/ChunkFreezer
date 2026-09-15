package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Settings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Purely informational, region-threaded-server-only feature: shows a blue particle wall to a
 *  player when they're near the boundary between the region currently ticking them and a
 *  neighboring one, so they (and anyone watching) can see where one region hands off to another.
 *
 *  <p>Crossing the line is completely normal and is never blocked - unlike a frozen chunk's
 *  border, nothing about this restricts movement in any way, and there's no cost to walking back
 *  and forth across it. Regions merge and split dynamically as players spread out or cluster
 *  together, so unlike frozen chunks (which have a fixed, precomputed border), a region's border
 *  has to be recomputed live: this only knows "is the region ticking me right now the same one
 *  that owns the chunk next door", checked fresh a few times a second.
 *
 *  <p>Every method here relies on Folia's (or a Folia fork's, e.g. CanvasMC's) region-scheduler
 *  API, exactly like the rest of this plugin already does - there's no separate "are we on
 *  Folia?" check because the whole plugin already requires it to load at all. */
public final class RegionBorderListener implements Listener {

    private final Plugin  plugin;
    private final Settings settings;
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private static final Particle.DustOptions REGION_DUST = new Particle.DustOptions(Color.fromRGB(60, 140, 255), 0.85f);
    public RegionBorderListener(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }
    public void startTasks() {
        if (!settings.regionBorderParticlesEnabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) startTask(p);
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (settings.regionBorderParticlesEnabled) startTask(e.getPlayer());
    }
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!settings.regionBorderParticlesEnabled) return;
        UUID id = e.getPlayer().getUniqueId();
        ScheduledTask old = tasks.remove(id);
        if (old != null) try { old.cancel(); } catch (Throwable ignored) {}
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> startTask(e.getPlayer()), 2L);
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ScheduledTask t = tasks.remove(e.getPlayer().getUniqueId());
        if (t != null) try { t.cancel(); } catch (Throwable ignored) {}
    }
    private void startTask(Player p) { startTask(p, 0); }
    private void startTask(Player p, int attempt) {
        if (!settings.regionBorderParticlesEnabled || !p.isOnline()) return;
        UUID id = p.getUniqueId();
        if (tasks.containsKey(id)) return;
        ScheduledTask t = p.getScheduler().runAtFixedRate(plugin, st -> tick(p, st), () -> tasks.remove(id), 5L, 6L);
        if (t != null) { tasks.put(id, t); return; }
        if (attempt < 10) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> startTask(p, attempt + 1), 4L);
        }
    }
    private void tick(Player p, ScheduledTask st) {
        try {
            tick0(p, st);
        } catch (Throwable t) {
            /** Defensive: this runs 10x/second per online player. An uncaught exception here would
             *  otherwise repeat every cycle and flood the console - log it at most once so admins
             *  can still see and report it, then quietly stop drawing for the rest of this session
             *  rather than spamming forever. Doesn't touch anything else the plugin does. */
            if (loggedError.compareAndSet(false, true)) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "region-border-particles: unexpected error, disabling for this session", t);
            }
            st.cancel();
        }
    }
    private final java.util.concurrent.atomic.AtomicBoolean loggedError = new java.util.concurrent.atomic.AtomicBoolean(false);
    private void tick0(Player p, ScheduledTask st) {
        if (!p.isOnline() || !p.isValid()) { st.cancel(); return; }
        if (!settings.regionBorderParticlesEnabled) return;
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return;
        /** This task runs on the region that owns the player (it was scheduled through the
         *  player's own EntityScheduler), so checking neighboring chunks below tells us whether
         *  THAT SAME region also owns them, or whether they belong to a different one. */
        int pcx = loc.getBlockX() >> 4, pcz = loc.getBlockZ() >> 4;
        double px = loc.getX(), pz = loc.getZ(), py = loc.getY();
        double d = settings.regionBorderParticleDistance;
        boolean eastForeign  = !Bukkit.isOwnedByCurrentRegion(w, pcx + 1, pcz);
        boolean westForeign  = !Bukkit.isOwnedByCurrentRegion(w, pcx - 1, pcz);
        boolean southForeign = !Bukkit.isOwnedByCurrentRegion(w, pcx, pcz + 1);
        boolean northForeign = !Bukkit.isOwnedByCurrentRegion(w, pcx, pcz - 1);
        if (!eastForeign && !westForeign && !southForeign && !northForeign) return;
        double x0 = pcx << 4, z0 = pcz << 4;
        if (eastForeign && Math.abs(px - (x0 + 16.0)) <= d)
            drawWallFixedX(p, x0 + 16.0, z0, z0 + 16.0, pz, py, d);
        if (westForeign && Math.abs(px - x0) <= d)
            drawWallFixedX(p, x0, z0, z0 + 16.0, pz, py, d);
        if (southForeign && Math.abs(pz - (z0 + 16.0)) <= d)
            drawWallFixedZ(p, z0 + 16.0, x0, x0 + 16.0, px, py, d);
        if (northForeign && Math.abs(pz - z0) <= d)
            drawWallFixedZ(p, z0, x0, x0 + 16.0, px, py, d);
    }
    private void drawWallFixedX(Player p, double bx, double zMin, double zMax, double pz, double py, double d) {
        double zFrom = Math.max(zMin, pz - d), zTo = Math.min(zMax, pz + d);
        for (double z = zFrom; z <= zTo; z += 0.5)
            for (double y = py - 1.0; y <= py + 3.0; y += 0.5)
                p.spawnParticle(Particle.DUST, bx, y, z, 1, REGION_DUST);
    }
    private void drawWallFixedZ(Player p, double bz, double xMin, double xMax, double px, double py, double d) {
        double xFrom = Math.max(xMin, px - d), xTo = Math.min(xMax, px + d);
        for (double x = xFrom; x <= xTo; x += 0.5)
            for (double y = py - 1.0; y <= py + 3.0; y += 0.5)
                p.spawnParticle(Particle.DUST, x, y, bz, 1, REGION_DUST);
    }
    public void shutdown() {
        tasks.values().forEach(t -> { try { t.cancel(); } catch (Throwable ignored) {} });
        tasks.clear();
    }
}