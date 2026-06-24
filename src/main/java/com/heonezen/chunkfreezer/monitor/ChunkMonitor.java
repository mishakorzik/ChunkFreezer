package com.heonezen.chunkfreezer.monitor;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkMonitor implements Listener {

    private static final long REFRESH_COOLDOWN_MS = 150L;

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;
    private final NamespacedKey dropOwnerKey;

    private final Map<Key, ScheduledTask> monitors    = new ConcurrentHashMap<>();
    private final Map<Key, Boolean>       msgState    = new ConcurrentHashMap<>();
    private final Map<Key, Long>          lastMsgMs   = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Key>>     coverage    = new ConcurrentHashMap<>();
    private final Map<Key, Integer>       interest    = new ConcurrentHashMap<>();
    private final Map<UUID, Long>         refreshedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long>         projectileEnteredFrozen = new ConcurrentHashMap<>();

    public ChunkMonitor(Plugin plugin, Settings settings, FrozenChunkManager manager) {
        this.plugin = plugin;
        this.settings = settings;
        this.manager = manager;
        this.dropOwnerKey = new NamespacedKey(plugin, "dropOwner");
    }
    public void runStartupScan() {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, _t -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 1L);
            }
            if (!settings.instantDespawnEnabled) return;
            for (World w : Bukkit.getWorlds()) {
                for (Chunk chunk : w.getLoadedChunks()) {
                    if (!manager.isFrozen(w, chunk.getX(), chunk.getZ())) continue;
                    final World fw = w; final int cx = chunk.getX(), cz = chunk.getZ();
                    Bukkit.getRegionScheduler().execute(plugin, fw, cx, cz, () -> {
                        if (fw.isChunkLoaded(cx, cz))
                            despawnIfAbandoned(fw, cx, cz, fw.getChunkAt(cx, cz));
                    });
                }
            }
        }, 20L);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 1L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { removeCoverage(e.getPlayer().getUniqueId()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        removeCoverage(p.getUniqueId());
        p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 1L);
    }
    @EventHandler(ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 2L);
    }
    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if ((e.getFrom().getBlockX() >> 4) == (e.getTo().getBlockX() >> 4)
                && (e.getFrom().getBlockZ() >> 4) == (e.getTo().getBlockZ() >> 4)) return;
        refreshCoverage(e.getPlayer());
    }
    private void refreshCoverage(Player p) {
        if (p == null || !p.isOnline()) return;
        long now = System.currentTimeMillis();
        Long last = refreshedAt.get(p.getUniqueId());
        if (last != null && now - last < REFRESH_COOLDOWN_MS) return;
        refreshedAt.put(p.getUniqueId(), now);
        World w = p.getWorld();
        int pcx = p.getLocation().getBlockX() >> 4;
        int pcz = p.getLocation().getBlockZ() >> 4;
        int r = Math.max(2, Math.min(32, w.getSimulationDistance() + 1));
        UUID worldId = w.getUID();
        Set<Key> next = new HashSet<>((2 * r + 1) * (2 * r + 1) + 1, 0.9f);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                next.add(new Key(worldId, pcx + dx, pcz + dz));
        UUID pid = p.getUniqueId();
        Set<Key> prev = coverage.put(pid, Collections.unmodifiableSet(next));
        if (prev == null) {
            for (Key k : next) if (interest.merge(k, 1, Integer::sum) == 1) startIfLoaded(w, k);
            return;
        }
        for (Key k : prev) if (!next.contains(k)) decrementInterest(k);
        for (Key k : next) if (!prev.contains(k) && interest.merge(k, 1, Integer::sum) == 1) startIfLoaded(w, k);
    }
    private void removeCoverage(UUID pid) {
        refreshedAt.remove(pid);
        Set<Key> prev = coverage.remove(pid);
        if (prev != null) prev.forEach(this::decrementInterest);
    }
    private void decrementInterest(Key k) {
        interest.compute(k, (key, cur) -> {
            if (cur == null || cur <= 1) {
                ScheduledTask t = monitors.remove(key);
                if (t != null) { try { t.cancel(); } catch (Throwable ignored) {} }
                msgState.remove(key);
                lastMsgMs.remove(key);
                return null;
            }
            return cur - 1;
        });
    }
    @EventHandler public void onChunkLoad(ChunkLoadEvent e) {
        Key k = new Key(e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
        if (interest.getOrDefault(k, 0) > 0) ensureMonitored(e.getWorld(), k.cx(), k.cz());
        if (settings.entitiesProtectionEnabled && settings.destroyThreshold > 0) {
            World w = e.getWorld();
            int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
            Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                if (!w.isChunkLoaded(cx, cz)) return;
                Chunk loaded = w.getChunkAt(cx, cz);
                int count = 0;
                for (Entity ent : loaded.getEntities()) {
                    if (!(ent instanceof Player) && !settings.isIgnored(ent.getType())) count++;
                }
                if (count > settings.destroyThreshold) {
                    for (Entity ent : loaded.getEntities()) {
                        if (!(ent instanceof Player) && !settings.isIgnored(ent.getType())) ent.remove();
                    }
                }
            });
        }
    }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent e) {
        World w = e.getWorld(); int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        Key k = new Key(w.getUID(), cx, cz);
        ScheduledTask t = monitors.remove(k);
        if (t != null) t.cancel();
        msgState.remove(k); lastMsgMs.remove(k);
        if (manager.isFrozen(w, cx, cz)) manager.unfreezeChunk(e.getChunk());
    }
    private void startIfLoaded(World w, Key k) {
        if (w.getUID().equals(k.worldId()) && w.isChunkLoaded(k.cx(), k.cz()))
            ensureMonitored(w, k.cx(), k.cz());
    }
    private void ensureMonitored(World world, int cx, int cz) {
        Key k = new Key(world.getUID(), cx, cz);
        if (monitors.containsKey(k) || interest.getOrDefault(k, 0) <= 0) return;
        ScheduledTask t = Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, cx, cz, st -> tick(world, cx, cz, st), 1L, settings.checkPeriodTicks);
        monitors.put(k, t);
    }
    private void tick(World world, int cx, int cz, ScheduledTask st) {
        Key k = new Key(world.getUID(), cx, cz);
        if (interest.getOrDefault(k, 0) <= 0) {
            st.cancel(); monitors.remove(k); msgState.remove(k); lastMsgMs.remove(k);
            return;
        }
        if (!world.isChunkLoaded(cx, cz)) return;
        Chunk chunk = world.getChunkAt(cx, cz);
        int count = countForLimit(chunk);
        if (settings.overloadPurgeEnabled && count >= settings.freezeThreshold && !settings.overloadPurgeEntityTypes.isEmpty()) {
            purgeOverload(chunk);
            count = countForLimit(chunk);
        }
        boolean frozenNow = manager.isFrozen(world, cx, cz);
        if (!frozenNow && count >= settings.freezeThreshold) {
            manager.freezeChunk(chunk); frozenNow = true;
        } else if (frozenNow && count <= settings.unfreezeThreshold && !manager.isUnfreezeLocked(world, cx, cz)) {
            manager.unfreezeChunk(chunk); frozenNow = false;
        }
        manager.updateEntityCount(world, cx, cz, count);
        if (frozenNow && settings.watchItemsEnabled && settings.returnPlayerDroppedItem)
            redirectFrozenItems(world, cx, cz, chunk);
        if (frozenNow)
            checkProjectileStuck(chunk);
        if (frozenNow && settings.instantDespawnEnabled)
            despawnIfAbandoned(world, cx, cz, chunk);
        updateBroadcast(world, cx, cz, k, frozenNow, count, frozenNow ? manager.getFreezeCause(world, cx, cz) : null);
    }
    private void checkProjectileStuck(Chunk chunk) {
        long now = System.currentTimeMillis();
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Projectile)) continue;
            if (!e.isValid() || e.isDead()) { projectileEnteredFrozen.remove(e.getUniqueId()); continue; }
            UUID id = e.getUniqueId();
            long first = projectileEnteredFrozen.computeIfAbsent(id, k -> now);
            if (now - first >= 3000L) {
                e.remove();
                projectileEnteredFrozen.remove(id);
            }
        }
        projectileEnteredFrozen.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
    }
    private void redirectFrozenItems(World world, int cx, int cz, Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Item item)) continue;
            if (!item.isValid() || item.isDead()) continue;
            if (item.getPickupDelay() == Integer.MAX_VALUE) continue;
            item.setPickupDelay(Integer.MAX_VALUE);
            ItemStack stack = item.getItemStack().clone();
            String ownerStr = item.getPersistentDataContainer().get(dropOwnerKey, PersistentDataType.STRING);
            item.remove();
            if (ownerStr != null) {
                try {
                    Player owner = Bukkit.getPlayer(java.util.UUID.fromString(ownerStr));
                    if (owner != null && owner.isOnline()) {
                        owner.getScheduler().execute(plugin, () -> {
                            owner.getInventory().addItem(stack).values().forEach(rem -> owner.getWorld().dropItemNaturally(owner.getLocation(), rem));
                            owner.updateInventory();
                        }, null, 1L);
                        continue;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            int[] dxs = {0, 0, 1, -1};
            int[] dzs = {1, -1, 0, 0};
            for (int i = 0; i < 4; i++) {
                int ncx = cx + dxs[i], ncz = cz + dzs[i];
                if (!manager.isFrozen(world, ncx, ncz) && world.isChunkLoaded(ncx, ncz)) {
                    Location exit = new Location(world, (ncx << 4) + 8.0, e.getLocation().getY(), (ncz << 4) + 8.0);
                    Bukkit.getRegionScheduler().execute(plugin, world, ncx, ncz, () -> world.dropItemNaturally(exit, stack));
                    break;
                }
            }
        }
    }
    void despawnIfAbandoned(World world, int cx, int cz, Chunk chunk) {
        double r = settings.instantDespawnRadius;
        double ccx = (cx << 4) + 8.0, ccz = (cz << 4) + 8.0;
        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            double dx = p.getLocation().getX() - ccx, dz = p.getLocation().getZ() - ccz;
            if (dx * dx + dz * dz <= r * r) return;
        }
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Player) continue;
            if (e instanceof Projectile) continue;
            if (settings.isIgnored(e.getType()) || settings.isInstantDespawnIgnored(e.getType())) continue;
            if (!settings.instantDespawnNamedEntity && e.customName() != null) continue;
            e.remove();
        }
    }
    private int countForLimit(Chunk chunk) {
        int c = 0;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Player) continue;
            if (!settings.isIgnored(e.getType())) c++;
        }
        return c;
    }
    private void purgeOverload(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Player || e instanceof Projectile) continue;
            if (settings.isIgnored(e.getType())) continue;
            if (settings.shouldPurgeProjectileType(e.getType())) e.remove();
        }
    }
    private void updateBroadcast(World world, int cx, int cz, Key k, boolean frozen, int count, FrozenChunkManager.FreezeCause cause) {
        if (!settings.broadcast || (frozen && cause == FrozenChunkManager.FreezeCause.REDSTONE)) return;
        Boolean prev = msgState.get(k);
        if (prev == null) { msgState.put(k, frozen); if (frozen) broadcast(world, cx, cz, count, cause); return; }
        if (prev == frozen) return;
        long now = System.currentTimeMillis();
        Long last = lastMsgMs.get(k);
        if (last != null && now - last < 1000L) { msgState.put(k, frozen); return; }
        lastMsgMs.put(k, now); msgState.put(k, frozen);
        if (frozen) broadcast(world, cx, cz, count, cause);
    }
    private void broadcast(World world, int cx, int cz, int count, FrozenChunkManager.FreezeCause cause) {
        String msg = settings.prefix + "§8Chunk overloaded §e" + world.getName() + " §8xyz(§f" + ((cx << 4) + 8) + "§8, §f~§8, §f" + ((cz << 4) + 8) + "§8)" + (cause == FrozenChunkManager.FreezeCause.ENTITY ? " §c(entity=" + count + ")" : "");
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.broadcastMessage(msg));
    }
    public void shutdown() {
        monitors.values().forEach(t -> { try { t.cancel(); } catch (Throwable ignored) {} });
        monitors.clear(); msgState.clear(); lastMsgMs.clear();
        coverage.clear(); interest.clear(); refreshedAt.clear();
        projectileEnteredFrozen.clear();
    }
    private record Key(UUID worldId, int cx, int cz) {}
}
