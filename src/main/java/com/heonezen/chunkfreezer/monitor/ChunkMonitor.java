package com.heonezen.chunkfreezer.monitor;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkMonitor implements Listener {

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;

    private final Map<Key, ScheduledTask> monitors = new ConcurrentHashMap<>();
    private final Map<Key, Boolean> state = new ConcurrentHashMap<>();
    private final Map<Key, Long> lastMsgMs = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Key>> playerCoverage = new ConcurrentHashMap<>();
    private final Map<Key, Integer> interestCount = new ConcurrentHashMap<>();

    public ChunkMonitor(Plugin plugin, Settings settings, FrozenChunkManager manager) {
        this.plugin = plugin;
        this.settings = settings;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.getScheduler().execute(plugin, () -> refreshPlayerCoverage(p), null, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removePlayerCoverage(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        removePlayerCoverage(p.getUniqueId());
        p.getScheduler().execute(plugin, () -> refreshPlayerCoverage(p), null, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        p.getScheduler().execute(plugin, () -> refreshPlayerCoverage(p), null, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        if (e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if ((e.getFrom().getBlockX() >> 4 != e.getTo().getBlockX() >> 4) || (e.getFrom().getBlockZ() >> 4 != e.getTo().getBlockZ() >> 4)) refreshPlayerCoverage(e.getPlayer());
    }

    private void refreshPlayerCoverage(Player p) {
        if (p == null || !p.isOnline()) return;

        World w = p.getWorld();
        UUID worldId = w.getUID();

        int pcx = p.getLocation().getBlockX() >> 4;
        int pcz = p.getLocation().getBlockZ() >> 4;

        int r = p.getSimulationDistance();
        if (r <= 0) r = w.getSimulationDistance();
        if (r <= 0) r = 10;
        r = Math.max(2, Math.min(32, r));

        Set<Key> next = new HashSet<>((2 * r + 1) * (2 * r + 1));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                next.add(new Key(worldId, pcx + dx, pcz + dz));
            }
        }
        next = Collections.unmodifiableSet(next);

        UUID playerId = p.getUniqueId();
        Set<Key> prev = playerCoverage.put(playerId, next);

        if (prev == null) {
            for (Key k : next) {
                int after = interestCount.merge(k, 1, Integer::sum);
                if (after == 1) tryStartMonitorIfLoaded(w, k);
            }
            return;
        }

        for (Key k : prev) if (!next.contains(k)) decrementInterest(k);
        for (Key k : next) {
            if (!prev.contains(k)) {
                int after = interestCount.merge(k, 1, Integer::sum);
                if (after == 1) tryStartMonitorIfLoaded(w, k);
            }
        }
    }

    private void removePlayerCoverage(UUID playerId) {
        Set<Key> prev = playerCoverage.remove(playerId);
        if (prev == null) return;
        for (Key k : prev) decrementInterest(k);
    }

    private void decrementInterest(Key k) {
        Integer cur = interestCount.get(k);
        if (cur == null) return;

        int next = cur - 1;
        if (next <= 0) {
            interestCount.remove(k);

            ScheduledTask task = monitors.remove(k);
            if (task != null) {
                try { task.cancel(); } catch (Throwable ignored) {}
            }

            state.remove(k);
            lastMsgMs.remove(k);
        } else {
            interestCount.put(k, next);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        World w = e.getWorld();
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();

        Key k = new Key(w.getUID(), cx, cz);
        if (interestCount.getOrDefault(k, 0) <= 0) return;

        ensureMonitored(w, cx, cz);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        World w = e.getWorld();
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();
        Key k = new Key(w.getUID(), cx, cz);

        ScheduledTask task = monitors.remove(k);
        if (task != null) task.cancel();

        state.remove(k);
        lastMsgMs.remove(k);

        if (manager.isFrozen(w, cx, cz)) manager.unfreezeChunk(e.getChunk());
    }

    private void tryStartMonitorIfLoaded(World currentWorld, Key k) {
        if (!currentWorld.getUID().equals(k.worldId())) return;
        if (!currentWorld.isChunkLoaded(k.cx(), k.cz())) return;
        ensureMonitored(currentWorld, k.cx(), k.cz());
    }

    private void ensureMonitored(World world, int cx, int cz) {
        Key k = new Key(world.getUID(), cx, cz);
        if (monitors.containsKey(k)) return;
        if (interestCount.getOrDefault(k, 0) <= 0) return;

        ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(
                plugin,
                world, cx, cz,
                st -> tick(world, cx, cz, st),
                1L,
                settings.checkPeriodTicks
        );

        monitors.put(k, task);
    }


    private void tick(World world, int cx, int cz, ScheduledTask st) {
        Key k = new Key(world.getUID(), cx, cz);

        if (interestCount.getOrDefault(k, 0) <= 0) {
            st.cancel();
            monitors.remove(k);
            state.remove(k);
            lastMsgMs.remove(k);
            return;
        }

        if (!world.isChunkLoaded(cx, cz)) return;

        Chunk chunk = world.getChunkAt(cx, cz);
        int count = countEntitiesForLimit(chunk);

        if (settings.overloadPurgeEnabled
                && count >= settings.freezeThreshold
                && !settings.overloadPurgeEntityTypes.isEmpty()) {
            purgeOverloadEntities(chunk);
            count = countEntitiesForLimit(chunk);
        }

        if (settings.freezeType == 0) {
            updateMessage(world, cx, cz, k, count >= settings.freezeThreshold, count, FrozenChunkManager.FreezeCause.ENTITY);
            return;
        }

        boolean frozenNow = manager.isFrozen(world, cx, cz);

        if (!frozenNow && count >= settings.freezeThreshold) {
            manager.freezeChunk(chunk);
            frozenNow = true;
        } else if (frozenNow) {
            manager.enforceFrozen(chunk);
            if (count <= settings.unfreezeThreshold && !manager.isUnfreezeLocked(world, cx, cz)) {
                manager.unfreezeChunk(chunk);
                frozenNow = false;
            }
        }

        if (settings.freezeType == 2 && frozenNow) {
            long ageSec = manager.frozenAgeSeconds(world, cx, cz);
            if (ageSec >= 120) {
                manager.purgeEntitiesDownTo(chunk, settings.unfreezeThreshold);
                count = countEntitiesForLimit(chunk);

                if (count <= settings.unfreezeThreshold && !manager.isUnfreezeLocked(world, cx, cz)) {
                    manager.unfreezeChunk(chunk);
                    frozenNow = false;
                }
            }
        }
        FrozenChunkManager.FreezeCause cause = frozenNow ? manager.getFreezeCause(world, cx, cz) : null;
        updateMessage(world, cx, cz, k, frozenNow, count, cause);
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

    private void purgeOverloadEntities(Chunk chunk) {
        for (Entity ent : chunk.getEntities()) {
            if (ent instanceof Player) continue;
            if (settings.isIgnored(ent.getType())) continue;
            if (!settings.shouldPurgeProjectileType(ent.getType())) continue;
            ent.remove();
        }
    }

    private void updateMessage(World world, int cx, int cz, Key k, boolean flag, int count, FrozenChunkManager.FreezeCause cause) {
        if (!settings.broadcast) return;
        if (flag && cause == FrozenChunkManager.FreezeCause.REDSTONE) {
            return;
        }

        Boolean prev = state.get(k);

        if (prev == null) {
            state.put(k, flag);
            if (flag) broadcast(world, cx, cz, count, flag, cause);
            return;
        }

        if (prev == flag) return;
        long now = System.currentTimeMillis();
        Long last = lastMsgMs.get(k);
        if (last != null && (now - last) < 1000L) {
            state.put(k, flag);
            return;
        }

        lastMsgMs.put(k, now);
        state.put(k, flag);
        if (flag) broadcast(world, cx, cz, count, flag, cause);
    }

    private void broadcast(World world, int cx, int cz, int count, boolean flag, FrozenChunkManager.FreezeCause cause) {
        if (cause == FrozenChunkManager.FreezeCause.ENTITY) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcastMessage(settings.prefix + "§8Chunk overloaded §e" + world.getName() + " §8xyz(§f" + ((cx << 4) + 8) + "§8, §f~§8, §f" + ((cz << 4) + 8) + "§8) §c(entity=" + count + ")"));
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcastMessage(settings.prefix + "§8Chunk overloaded §e" + world.getName() + " §8xyz(§f" + ((cx << 4) + 8) + "§8, §f~§8, §f" + ((cz << 4) + 8) + "§8)"));
        }

    }

    public void shutdown() {
        for (ScheduledTask t : monitors.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        monitors.clear();
        state.clear();
        lastMsgMs.clear();
        playerCoverage.clear();
        interestCount.clear();
    }

    private record Key(UUID worldId, int cx, int cz) {}
}
