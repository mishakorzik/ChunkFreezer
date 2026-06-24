package com.heonezen.chunkfreezer.redstone;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RedstoneLoopProtector implements Listener {

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;
    private final Map<Key, RsState> states = new ConcurrentHashMap<>();

    public RedstoneLoopProtector(Plugin plugin, Settings settings, FrozenChunkManager manager) { this.plugin = plugin; this.settings = settings; this.manager = manager; }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        long now = System.currentTimeMillis();
        Key k = new Key(e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
        states.computeIfAbsent(k, _k -> new RsState(windowIndex(now))).ignoreUntilMs = now + settings.redstoneGraceSeconds * 1000L;
    }
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) { states.remove(new Key(e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ())); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent e) {
        if (e.getOldCurrent() == e.getNewCurrent()) return;
        World w = e.getBlock().getWorld();
        int cx = e.getBlock().getX() >> 4, cz = e.getBlock().getZ() >> 4;
        if (manager.isRedstoneMuted(w, cx, cz)) return;
        long now = System.currentTimeMillis();
        Key k = new Key(w.getUID(), cx, cz);
        RsState st = states.computeIfAbsent(k, _k -> new RsState(windowIndex(now)));
        if (now < st.ignoreUntilMs) return;
        long win = windowIndex(now);
        if (st.windowIdx != win) {
            st.consecutiveOver = (st.eventsInWindow >= settings.redstoneMaxEventsPerWindow && st.distinctInWindow >= settings.redstoneMinDistinctBlocks) ? st.consecutiveOver + 1 : 0;
            st.windowIdx = win; st.eventsInWindow = 0; st.distinctInWindow = 0; st.positions = null;
        }
        st.eventsInWindow++;
        if (settings.redstoneMinDistinctBlocks > 0) {
            if (st.positions == null && st.eventsInWindow >= 10) st.positions = new HashSet<>(128);
            if (st.positions != null && st.distinctInWindow <= 2048) {
                if (st.positions.add(packPos(e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ())))
                    st.distinctInWindow = st.positions.size();
            }
        }
        if (st.eventsInWindow < settings.redstoneMaxEventsPerWindow) return;
        if (st.distinctInWindow < settings.redstoneMinDistinctBlocks) return;
        if (st.consecutiveOver + 1 < settings.redstoneRequiredWindows) return;
        if (now < st.nextAllowedTriggerMs) return;
        int reportDistinct = st.positions != null ? st.positions.size() : st.distinctInWindow;
        st.nextAllowedTriggerMs = now + settings.redstoneFreezeSeconds * 1000L + 3000L;
        st.consecutiveOver = 0; st.eventsInWindow = 0; st.distinctInWindow = 0; st.positions = null;
        trigger(w, cx, cz, reportDistinct);
    }
    private void trigger(World world, int cx, int cz, int distinct) {
        long now = System.currentTimeMillis();
        manager.lockUnfreezeUntil(world, cx, cz, now + settings.redstoneFreezeSeconds * 1000L);
        if (settings.redstoneMuteAfterFreeze) {
            long muteUntil = settings.redstoneMuteMaxSeconds <= 0 ? Long.MAX_VALUE : now + settings.redstoneMuteMaxSeconds * 1000L;
            manager.muteRedstone(world, cx, cz, muteUntil);
        }
        Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
            if (!world.isChunkLoaded(cx, cz)) return;
            Chunk chunk = world.getChunkAt(cx, cz);
            manager.freezeChunk(chunk, FrozenChunkManager.FreezeCause.REDSTONE);
            if (settings.broadcast) {
                String msg = settings.prefix + "§8Chunk overloaded §e" + world.getName() + " §8xyz(§f" + ((cx << 4) + 8) + "§8, §f~§8, §f" + ((cz << 4) + 8) + "§8)" + " §c(redstone=" + distinct + ")";
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcastMessage(msg));
            }
            Bukkit.getRegionScheduler().runDelayed(plugin, world, cx, cz,
                    (ScheduledTask _st) -> attemptUnfreeze(world, cx, cz),
                    Math.max(20L, settings.redstoneFreezeSeconds * 20L));
        });
    }
    private void attemptUnfreeze(World world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return;
        Chunk chunk = world.getChunkAt(cx, cz);
        if (countForLimit(chunk) < settings.freezeThreshold && manager.isFrozen(world, cx, cz))
            manager.unfreezeChunk(chunk);
    }
    private int countForLimit(Chunk chunk) {
        int c = 0;
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Player) && !settings.isIgnored(e.getType())) c++;
        }
        return c;
    }
    private long windowIndex(long nowMs) {
        return nowMs / Math.max(50L, settings.redstoneWindowTicks * 50L);
    }
    private static int packPos(int x, int y, int z) {
        return ((x & 15) << 14) | ((z & 15) << 10) | (y & 1023);
    }
    private record Key(UUID worldId, int cx, int cz) {}
    private static final class RsState {
        long windowIdx;
        int eventsInWindow;
        int distinctInWindow;
        int consecutiveOver;
        long nextAllowedTriggerMs;
        long ignoreUntilMs;
        Set<Integer> positions;
        RsState(long windowIdx) { this.windowIdx = windowIdx; }
    }
}
