package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.util.FoliaSupport;
import com.heonezen.chunkfreezer.util.ParticleWalls;
import com.heonezen.chunkfreezer.util.PlayerTasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Purely informational, Folia-only feature: shows a particle wall where the region grid runs
 *  near a player.
 *
 *  <h2>Why it used to show nothing at all</h2>
 *  The old implementation only drew a wall when a chunk directly next to the player's chunk was
 *  owned by a different region. Folia's regionizer guarantees that can never happen: every chunk
 *  position within the merge radius of a loaded chunk is owned by that chunk's region, and a
 *  player is always surrounded by their own loaded (view-distance) chunks. So the neighbouring
 *  chunks were always "ours" and the check was false forever.
 *
 *  <h2>What it shows now</h2>
 *  Folia builds every region out of whole region <i>sections</i> - squares of 2^grid-exponent
 *  chunks (16x16 chunks = 256x256 blocks by default). Region borders, splits and merges can
 *  only ever happen along those section lines, so they are exactly the lines worth seeing:
 *  <ul>
 *    <li><b>blue</b> - a section line; the section across it is still ticked by the same
 *        region as the player (the normal case);</li>
 *    <li><b>orange</b> - the section across the line is NOT owned by the player's region (a real
 *        region edge, e.g. right after a teleport, before the regions merge).</li>
 *  </ul>
 *  The grid size is read from the running server, so a custom grid-exponent is honoured. On
 *  plain Paper/Spigot (no regions) this listener does nothing. */
public final class RegionBorderListener implements Listener {

    public static final String PERMISSION = "chunkfreezer.regionborder";

    private static final Particle.DustOptions SECTION_DUST = new Particle.DustOptions(Color.fromRGB(60, 140, 255), 0.85f);
    private static final Particle.DustOptions EDGE_DUST    = new Particle.DustOptions(Color.fromRGB(255, 165, 40), 0.85f);

    private final Plugin plugin;
    private final Settings settings;
    private final boolean folia;
    /** Region section edge length in blocks (16 << grid-exponent). */
    private final double sectionBlocks;
    private final PlayerTasks tasks;
    private final AtomicBoolean loggedError = new AtomicBoolean(false);
    private volatile boolean active = true;

    public RegionBorderListener(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.folia = FoliaSupport.isFolia();
        int shift = FoliaSupport.regionSectionChunkShift();
        this.sectionBlocks = (double) (16L << shift);
        this.tasks = new PlayerTasks(plugin, this::enabled, 5L, 6L, this::tick);
    }

    private boolean enabled() {
        return active && folia && settings.regionBorderParticlesEnabled;
    }

    public void startTasks() {
        if (settings.regionBorderParticlesEnabled && !folia) {
            plugin.getLogger().info("region-border-particles: not a Folia server, nothing to show (regions only exist on Folia).");
            return;
        }
        if (!enabled()) return;
        plugin.getLogger().info("region-border-particles: region section size is " + (long) sectionBlocks + " blocks.");
        for (Player p : Bukkit.getOnlinePlayers()) tasks.start(p);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (enabled()) tasks.start(e.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        if (enabled()) tasks.restart(e.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        tasks.stop(e.getPlayer().getUniqueId());
    }

    private void tick(Player p, ScheduledTask st) {
        try {
            tick0(p, st);
        } catch (Throwable t) {
            /** Cosmetic feature running several times a second per player: log once, then stop
             *  drawing for this player instead of flooding the console. */
            if (loggedError.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "region-border-particles: unexpected error, disabling it for this player", t);
            }
            tasks.finish(p.getUniqueId(), st);
        }
    }

    private void tick0(Player p, ScheduledTask st) {
        if (!p.isOnline()) { tasks.finish(p.getUniqueId(), st); return; }
        if (!enabled() || !p.isValid()) return;
        if (settings.regionBorderRequirePermission && !p.hasPermission(PERMISSION)) return;
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return;
        double px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        double d = settings.regionBorderParticleDistance;
        int pcx = loc.getBlockX() >> 4, pcz = loc.getBlockZ() >> 4;

        // Nearest section line running north-south (constant X).
        double lineX = Math.rint(px / sectionBlocks) * sectionBlocks;
        if (Math.abs(px - lineX) <= d) {
            int lineChunk = (int) Math.floorDiv((long) lineX, 16L);
            int acrossCx = px < lineX ? lineChunk : lineChunk - 1;
            boolean edge = !Bukkit.isOwnedByCurrentRegion(w, acrossCx, pcz);
            ParticleWalls.wallAtX(p, lineX, pz - d, pz + d, pz, py, d, edge ? EDGE_DUST : SECTION_DUST);
        }
        // Nearest section line running east-west (constant Z).
        double lineZ = Math.rint(pz / sectionBlocks) * sectionBlocks;
        if (Math.abs(pz - lineZ) <= d) {
            int lineChunk = (int) Math.floorDiv((long) lineZ, 16L);
            int acrossCz = pz < lineZ ? lineChunk : lineChunk - 1;
            boolean edge = !Bukkit.isOwnedByCurrentRegion(w, pcx, acrossCz);
            ParticleWalls.wallAtZ(p, lineZ, px - d, px + d, px, py, d, edge ? EDGE_DUST : SECTION_DUST);
        }
    }

    public void shutdown() {
        active = false;
        tasks.close();
    }
}
