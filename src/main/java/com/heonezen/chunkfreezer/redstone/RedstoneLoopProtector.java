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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RedstoneLoopProtector implements Listener {

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;
    private final Map<Key, RsState> states = new ConcurrentHashMap<>();

    public RedstoneLoopProtector(Plugin plugin, Settings settings, FrozenChunkManager manager) {
        this.plugin = plugin;
        this.settings = settings;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!settings.redstoneProtectionEnabled) return;
        if (settings.freezeType == 0) return;

        long now = System.currentTimeMillis();
        RsState st = states.computeIfAbsent(new Key(e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ()), k -> new RsState(windowIndex(now)));
        st.ignoreUntilMs = now + settings.redstoneGraceSeconds * 1000L;
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        states.remove(new Key(e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent e) {
        if (settings.freezeType == 0) return;
        if (!settings.redstoneProtectionEnabled) return;
        if (e.getOldCurrent() == e.getNewCurrent()) return;

        World w = e.getBlock().getWorld();
        int cx = e.getBlock().getX() >> 4;
        int cz = e.getBlock().getZ() >> 4;

        if (manager.isRedstoneMuted(w, cx, cz)) return;

        final long now = System.currentTimeMillis();
        RsState st = states.computeIfAbsent(new Key(w.getUID(), cx, cz), _k -> new RsState(windowIndex(now)));

        if (now < st.ignoreUntilMs) return;

        long win = windowIndex(now);
        if (st.windowIdx != win) {
            st.consecutiveOver = (st.eventsInWindow >= settings.redstoneMaxEventsPerWindow && st.distinctInWindow >= settings.redstoneMinDistinctBlocks) ? (st.consecutiveOver + 1) : 0;
            st.windowIdx = win;
            st.eventsInWindow = 0;
            st.distinctInWindow = 0;
            st.positions = null;
        }

        st.eventsInWindow++;
        if (settings.redstoneMinDistinctBlocks > 0) {
            if (st.positions == null && st.eventsInWindow >= 10) st.positions = new HashSet<>(128);
            if (st.positions != null && st.distinctInWindow <= 2048) {
                if (st.positions.add(packRelPos(e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ()))) st.distinctInWindow = st.positions.size();
                if (st.positions.size() > 2048) st.distinctInWindow = 2048 + 1;
            }
        }

        if (st.eventsInWindow < settings.redstoneMaxEventsPerWindow) return;
        if (st.distinctInWindow < settings.redstoneMinDistinctBlocks) return;
        if (st.consecutiveOver + 1 < settings.redstoneRequiredWindows) return;
        if (now < st.nextAllowedTriggerMs) return;

        int reportDistinct = getDistinctBlocksCountForReport(st);

        st.nextAllowedTriggerMs = now + (settings.redstoneFreezeSeconds * 1000L) + 3000L;
        st.consecutiveOver = 0;
        st.eventsInWindow = 0;
        st.distinctInWindow = 0;
        st.positions = null;

        trigger(w, cx, cz, reportDistinct);
    }

    private int getDistinctBlocksCountForReport(RsState st) {
        Set<Integer> pos = st.positions;
        if (pos != null) return pos.size();
        if (st.distinctInWindow > 0) return st.distinctInWindow;
        return 1;
    }

    private void trigger(World world, int cx, int cz, int redstoneDistinctBlocks) {
        final long now = System.currentTimeMillis();
        manager.lockUnfreezeUntil(world, cx, cz, now + (settings.redstoneFreezeSeconds * 1000L));
        if (settings.redstoneMuteAfterFreeze) {
            if (settings.redstoneMuteMaxSeconds <= 0) manager.muteRedstone(world, cx, cz, Long.MAX_VALUE);
            else manager.muteRedstone(world, cx, cz, now + (settings.redstoneMuteMaxSeconds * 1000L));
        }

        Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
            if (!world.isChunkLoaded(cx, cz)) return;
            Chunk chunk = world.getChunkAt(cx, cz);
            manager.freezeChunk(chunk, FrozenChunkManager.FreezeCause.REDSTONE);

            if (settings.broadcast) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcastMessage(settings.prefix + "§8Chunk overloaded §e" + world.getName() + " §8xyz(§f" + ((cx << 4) + 8) + "§8, §f~§8, §f" + ((cz << 4) + 8) + "§8)" + " §c(redstone=" + redstoneDistinctBlocks + ")"));
            }

            Bukkit.getRegionScheduler().runDelayed(plugin, world, cx, cz, (ScheduledTask scheduled) -> attemptUnfreeze(world, cx, cz), Math.max(20L, settings.redstoneFreezeSeconds * 20L));
        });
    }

    private void attemptUnfreeze(World world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return;
        Chunk chunk = world.getChunkAt(cx, cz);

        if (countEntitiesForLimit(chunk) >= settings.freezeThreshold) return;
        if (manager.isFrozen(world, cx, cz)) {
            manager.unfreezeChunk(chunk);
        }
    }

    private int countEntitiesForLimit(Chunk chunk) {
        int c = 0;
        for (Entity ent : chunk.getEntities()) {
            if (ent instanceof Player) continue;
            if (settings.isIgnored(ent.getType())) continue;
            c++;
        }
        return c;
    }

    private long windowIndex(long nowMs) {
        return nowMs / (Math.max(50L, settings.redstoneWindowTicks * 50L));
    }

    private static int packRelPos(int x, int y, int z) {
        return ((x & 15) << 14) | ((z & 15) << 10) | (y & 1023);
    }

    private record Key(UUID worldId, int cx, int cz) {}
    private static final class RsState {
        volatile long windowIdx;
        volatile int eventsInWindow;
        volatile int distinctInWindow;
        volatile int consecutiveOver;
        volatile long nextAllowedTriggerMs;
        volatile long ignoreUntilMs;
        volatile Set<Integer> positions;

        private RsState(long windowIdx) {
            this.windowIdx = windowIdx;
            this.ignoreUntilMs = 0L;
        }
    }
}
