package com.heonezen.chunkfreezer.monitor;

import com.heonezen.chunkfreezer.config.Lang;
import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class ChunkMonitor implements Listener {

    private static final long REFRESH_COOLDOWN_MS = 200L;
    /** How long a projectile must stay genuinely stuck - or an XP orb must sit - in a frozen
     *  chunk before it's cleaned up. */
    private static final long PROJECTILE_STUCK_REMOVE_MS = 3000L;
    /** Velocity (blocks/tick, squared) below which a non-arrow projectile counts as motionless. */
    private static final double STUCK_VELOCITY_SQ = 1.0E-4;
    /** Stale-entry sweep of the (global) stuck-projectile map runs at most this often, instead of
     *  on every check of every frozen chunk. */
    private static final long STUCK_MAP_SWEEP_INTERVAL_MS = 10_000L;
    private static final long STUCK_ENTRY_MAX_AGE_MS = 60_000L;
    private static final long BROADCAST_COOLDOWN_MS = 1000L;

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;
    private final Lang lang;
    private final NamespacedKey dropOwnerKey;
    /** Chunks are only monitored when something can freeze them or something has to be done
     *  inside a frozen one (redstone-frozen chunks need the item/projectile/new-arrival upkeep
     *  too, even with entities-protection off). */
    private final boolean monitoringEnabled;

    private final Map<Key, ScheduledTask> monitors                = new ConcurrentHashMap<>();
    private final Map<Key, Long>          lastBroadcastMs         = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Key>>     coverage                = new ConcurrentHashMap<>();
    private final Map<Key, Integer>       interest                = new ConcurrentHashMap<>();
    private final Map<UUID, Long>         refreshedAt             = new ConcurrentHashMap<>();
    private final Set<UUID>               refreshPending          = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long>         projectileStuckSinceMs  = new ConcurrentHashMap<>();
    private final Set<Key>                fastCheckScheduled      = ConcurrentHashMap.newKeySet();
    private final Map<Key, Long>          freezeGraceUntilMs      = new ConcurrentHashMap<>();
    private final AtomicLong              lastStuckSweepMs        = new AtomicLong();
    private final AtomicBoolean           doTickErrorLogged       = new AtomicBoolean(false);
    private volatile boolean active = true;

    public ChunkMonitor(Plugin plugin, Settings settings, FrozenChunkManager manager, Lang lang) {
        this.plugin = plugin;
        this.settings = settings;
        this.manager = manager;
        this.lang = lang;
        this.dropOwnerKey = new NamespacedKey(plugin, "dropOwner");
        this.monitoringEnabled = settings.entitiesProtectionEnabled || settings.redstoneProtectionEnabled;
    }
    public void runStartupScan() {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, _t -> {
            if (!active) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 1L);
            }
            sweepAbandonedFrozenChunks(null);
        }, 20L);
    }
    /** Runs the instant-despawn check on every loaded frozen chunk (optionally of one world), each
     *  on its own region thread. Iterates the (small) frozen set rather than World#getLoadedChunks(),
     *  which is both far larger and not safe to call off the owning thread on Folia. */
    private void sweepAbandonedFrozenChunks(World onlyWorld) {
        if (!active || !settings.instantDespawnEnabled) return;
        for (FrozenChunkManager.FrozenInfo info : manager.getFrozenChunks()) {
            World w = Bukkit.getWorld(info.worldId());
            if (w == null || (onlyWorld != null && !onlyWorld.getUID().equals(info.worldId()))) continue;
            int cx = info.cx(), cz = info.cz();
            if (!w.isChunkLoaded(cx, cz)) continue;
            Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                if (active && w.isChunkLoaded(cx, cz) && manager.isFrozen(w, cx, cz))
                    despawnIfAbandoned(w, cx, cz, w.getChunkAt(cx, cz).getEntities());
            });
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 1L);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player qp = e.getPlayer();
        World qw = qp.getWorld();
        removeCoverage(qp.getUniqueId());
        if (!settings.instantDespawnEnabled || !manager.hasAnyFrozenChunks()) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> sweepAbandonedFrozenChunks(qw), 10L);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        removeCoverage(p.getUniqueId());
        p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 1L);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 2L);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
                && from.getWorld() == to.getWorld()) return;
        refreshCoverage(e.getPlayer());
    }
    /** Recomputes which chunks this player keeps monitored. Rate-limited, but never lossy: a
     *  refresh that lands inside the cooldown schedules one trailing refresh for when it ends
     *  (the old version simply dropped it, so e.g. a teleport right after crossing a chunk
     *  border left the destination area unmonitored until the player crossed another border).
     *  Must run on the player's own thread. */
    private void refreshCoverage(Player p) {
        if (!active || !monitoringEnabled || p == null || !p.isOnline()) return;
        UUID pid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = refreshedAt.get(pid);
        if (last != null && now - last < REFRESH_COOLDOWN_MS) {
            if (refreshPending.add(pid)) {
                long delayTicks = (REFRESH_COOLDOWN_MS - (now - last)) / 50L + 1L;
                boolean scheduled = p.getScheduler().execute(plugin, () -> {
                    refreshPending.remove(pid);
                    refreshCoverage(p);
                }, () -> refreshPending.remove(pid), Math.max(1L, delayTicks));
                if (!scheduled) refreshPending.remove(pid);
            }
            return;
        }
        refreshedAt.put(pid, now);
        World w = p.getWorld();
        Location pLoc = p.getLocation();
        int pcx = pLoc.getBlockX() >> 4;
        int pcz = pLoc.getBlockZ() >> 4;
        int r = Math.max(2, Math.min(32, w.getSimulationDistance() + 1));
        UUID worldId = w.getUID();
        Set<Key> next = new HashSet<>((int) (((2 * r + 1) * (2 * r + 1)) / 0.75f) + 1);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                next.add(new Key(worldId, pcx + dx, pcz + dz));
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
                return null;
            }
            return cur - 1;
        });
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        Key k = new Key(e.getWorld().getUID(), e.getChunk().getX(), e.getChunk().getZ());
        if (settings.entitiesGraceSeconds > 0) {
            freezeGraceUntilMs.put(k, System.currentTimeMillis() + settings.entitiesGraceSeconds * 1000L);
        }
        if (interest.getOrDefault(k, 0) > 0) ensureMonitored(e.getWorld(), k.cx(), k.cz());
    }
    /** Entities are loaded separately from (and usually after) the chunk itself on Paper/Folia,
     *  so destroy-threshold has to look at them here - at ChunkLoadEvent time the chunk usually
     *  doesn't have its entities yet, which made the old check miss overloaded chunks. Fires on
     *  the thread that owns the chunk. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!settings.entitiesProtectionEnabled) return;
        Chunk chunk = e.getChunk();
        Key k = new Key(e.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (settings.entitiesGraceSeconds > 0) {
            freezeGraceUntilMs.put(k, System.currentTimeMillis() + settings.entitiesGraceSeconds * 1000L);
        }
        if (settings.destroyThreshold <= 0) return;
        List<Entity> loaded = e.getEntities();
        if (loaded.size() <= settings.destroyThreshold) return; // can't exceed it, skip the count
        if (settings.countNonIgnoredEntities(loaded) <= settings.destroyThreshold) return;
        int removed = 0;
        for (Entity ent : loaded) {
            if (ent instanceof Player || !ent.isValid() || settings.isIgnored(ent.getType())) continue;
            ent.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("destroy-threshold: removed " + removed + " entities from chunk "
                    + chunk.getX() + ", " + chunk.getZ() + " in " + e.getWorld().getName() + " on load.");
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        World w = e.getWorld(); int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        Key k = new Key(w.getUID(), cx, cz);
        ScheduledTask t = monitors.remove(k);
        if (t != null) { try { t.cancel(); } catch (Throwable ignored) {} }
        lastBroadcastMs.remove(k); freezeGraceUntilMs.remove(k);
        if (manager.isFrozen(w, cx, cz)) manager.unfreezeChunk(e.getChunk());
    }
    private void startIfLoaded(World w, Key k) {
        if (w.getUID().equals(k.worldId()) && w.isChunkLoaded(k.cx(), k.cz()))
            ensureMonitored(w, k.cx(), k.cz());
    }
    private void ensureMonitored(World world, int cx, int cz) {
        if (!active || !monitoringEnabled) return;
        Key k = new Key(world.getUID(), cx, cz);
        if (interest.getOrDefault(k, 0) <= 0) return;
        // computeIfAbsent: two threads racing here can never schedule two tasks for one chunk.
        monitors.computeIfAbsent(k, key -> Bukkit.getRegionScheduler().runAtFixedRate(
                plugin, world, cx, cz, st -> tick(world, key, st), 1L, settings.checkPeriodTicks));
    }
    private void tick(World world, Key k, ScheduledTask st) {
        if (!active || interest.getOrDefault(k, 0) <= 0 || !world.isChunkLoaded(k.cx(), k.cz())) {
            st.cancel();
            monitors.remove(k, st); // only ever our own entry, never a newer task for the same chunk
            return;
        }
        doTick(world, k.cx(), k.cz());
    }
    private void doTick(World world, int cx, int cz) {
        if (!active) return;
        try {
            doTick0(world, cx, cz);
        } catch (Throwable t) {
            /** Core freeze/unfreeze loop: log the first failure so admins can report it, keep
             *  retrying silently on later ticks (monitoring of the chunk is not stopped). */
            if (doTickErrorLogged.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "entities-protection: unexpected error while checking a chunk", t);
            }
        }
    }
    private void doTick0(World world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return;
        boolean frozenNow = manager.isFrozen(world, cx, cz);
        if (!settings.entitiesProtectionEnabled && !frozenNow) return; // nothing could freeze it, nothing to maintain
        Chunk chunk = world.getChunkAt(cx, cz);
        Key k = new Key(world.getUID(), cx, cz);
        Entity[] entities = chunk.getEntities();
        int count = countForLimit(entities);
        boolean justFroze = false;
        if (settings.entitiesProtectionEnabled) {
            /** overload-purge only ever runs right at the moment a chunk is about to freeze (past
             *  its grace period) - never merely because a frozen chunk is still busy, which would
             *  purge every matching entity (e.g. an arrow flying through) on every single check. */
            if (!frozenNow && count >= settings.freezeThreshold) {
                Long graceUntil = freezeGraceUntilMs.get(k);
                if (graceUntil == null || System.currentTimeMillis() >= graceUntil) {
                    if (graceUntil != null) freezeGraceUntilMs.remove(k, graceUntil);
                    if (settings.overloadPurgeEnabled && !settings.overloadPurgeEntityTypes.isEmpty()) {
                        purgeOverload(entities);
                        count = countForLimit(entities);
                    }
                    if (count >= settings.freezeThreshold) {
                        justFroze = manager.freezeChunk(chunk, FrozenChunkManager.FreezeCause.ENTITY);
                        frozenNow = true;
                    }
                }
            } else if (frozenNow && count <= settings.unfreezeThreshold && !manager.isUnfreezeLocked(world, cx, cz)) {
                manager.unfreezeChunk(chunk);
                frozenNow = false;
            }
        }
        if (!frozenNow) return;
        manager.updateEntityCount(world, cx, cz, count);
        if (settings.barrierEnabled && settings.watchItemsEnabled && settings.returnPlayerDroppedItem)
            redirectFrozenItems(world, cx, cz, entities);
        if (settings.barrierEnabled && settings.watchProjectilesEnabled)
            checkProjectileStuck(entities);
        if (settings.instantDespawnEnabled)
            despawnIfAbandoned(world, cx, cz, entities);
        if (settings.frozenChunkRemoveNewArrivals)
            manager.removeNewArrivals(world, cx, cz, entities);
        if (justFroze) maybeBroadcast(world, cx, cz, k, count);
    }
    /** Removes a projectile only once it's genuinely stuck, never one merely passing through - an
     *  arrow/trident counts as stuck solely via {@link AbstractArrow#isInBlock()}; every other
     *  projectile only once its velocity is essentially zero. Fishing bobbers and fireworks are
     *  skipped entirely (a bobber legitimately sits still, an elytra firework moves with its
     *  rider). XP orbs in a frozen chunk count as stuck by mere presence. */
    private void checkProjectileStuck(Entity[] entities) {
        long now = System.currentTimeMillis();
        for (Entity e : entities) {
            boolean stuck;
            if (e instanceof AbstractArrow arrow) stuck = arrow.isInBlock();
            else if (e instanceof ExperienceOrb) stuck = true;
            else if (e instanceof FishHook || e instanceof Firework) continue;
            else if (e instanceof Projectile) stuck = e.getVelocity().lengthSquared() < STUCK_VELOCITY_SQ;
            else continue;
            UUID id = e.getUniqueId();
            if (!e.isValid() || e.isDead() || !stuck) { projectileStuckSinceMs.remove(id); continue; }
            long since = projectileStuckSinceMs.computeIfAbsent(id, x -> now);
            if (now - since >= PROJECTILE_STUCK_REMOVE_MS) {
                e.remove();
                projectileStuckSinceMs.remove(id);
            }
        }
        long lastSweep = lastStuckSweepMs.get();
        if (now - lastSweep >= STUCK_MAP_SWEEP_INTERVAL_MS && lastStuckSweepMs.compareAndSet(lastSweep, now)) {
            projectileStuckSinceMs.entrySet().removeIf(entry -> now - entry.getValue() > STUCK_ENTRY_MAX_AGE_MS);
        }
    }
    private static final int[] EXIT_DX = {0, 0, 1, -1};
    private static final int[] EXIT_DZ = {1, -1, 0, 0};
    private int[] findExitCell(World world, int cx, int cz) {
        for (int i = 0; i < 4; i++) {
            int ncx = cx + EXIT_DX[i], ncz = cz + EXIT_DZ[i];
            if (!manager.isFrozen(world, ncx, ncz) && world.isChunkLoaded(ncx, ncz)) return new int[]{ncx, ncz};
        }
        return null;
    }
    /** Last-resort drop used only if returning an item to its owner's inventory fails (e.g. they
     *  disconnected in the same instant). Never silently discards the item. */
    private void dropAtNearestExit(World world, int cx, int cz, double y, ItemStack stack) {
        int[] found = findExitCell(world, cx, cz);
        int dcx = found != null ? found[0] : cx;
        int dcz = found != null ? found[1] : cz;
        Location exit = new Location(world, (dcx << 4) + 8.0, y, (dcz << 4) + 8.0);
        Bukkit.getRegionScheduler().execute(plugin, world, dcx, dcz, () -> world.dropItemNaturally(exit, stack));
    }
    private void redirectFrozenItems(World world, int cx, int cz, Entity[] entities) {
        for (Entity e : entities) {
            if (!(e instanceof Item item)) continue;
            if (!item.isValid() || item.isDead()) continue;
            if (item.getPickupDelay() == Integer.MAX_VALUE) continue;
            String ownerStr = item.getPersistentDataContainer().get(dropOwnerKey, PersistentDataType.STRING);
            Player owner = null;
            if (ownerStr != null) {
                try {
                    Player candidate = Bukkit.getPlayer(UUID.fromString(ownerStr));
                    if (candidate != null && candidate.isOnline()) owner = candidate;
                } catch (IllegalArgumentException ignored) {}
            }
            int[] exitCell = null;
            if (owner == null) {
                exitCell = findExitCell(world, cx, cz);
                if (exitCell == null) continue; // no safe destination yet; retry next check
            }
            item.setPickupDelay(Integer.MAX_VALUE);
            ItemStack stack = item.getItemStack().clone();
            final double itemY = item.getLocation().getY(); // captured before remove()
            item.remove();
            if (owner != null) {
                final Player finalOwner = owner;
                boolean scheduled = owner.getScheduler().execute(plugin, () -> {
                    finalOwner.getInventory().addItem(stack).values().forEach(rem -> finalOwner.getWorld().dropItemNaturally(finalOwner.getLocation(), rem));
                    finalOwner.updateInventory();
                }, () -> dropAtNearestExit(world, cx, cz, itemY, stack), 1L);
                if (!scheduled) dropAtNearestExit(world, cx, cz, itemY, stack);
                continue;
            }
            final int exitCx = exitCell[0], exitCz = exitCell[1];
            Location exit = new Location(world, (exitCx << 4) + 8.0, itemY, (exitCz << 4) + 8.0);
            Bukkit.getRegionScheduler().execute(plugin, world, exitCx, exitCz, () -> world.dropItemNaturally(exit, stack));
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawnAdaptive(EntitySpawnEvent e) {
        if (!active || !settings.entitiesProtectionEnabled) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player || ent instanceof Item || settings.isIgnored(ent.getType())) return;
        Location eLoc = e.getLocation();
        World w = eLoc.getWorld();
        if (w == null) return;
        int cx = eLoc.getBlockX() >> 4;
        int cz = eLoc.getBlockZ() >> 4;
        Key k = new Key(w.getUID(), cx, cz);
        if (interest.getOrDefault(k, 0) <= 0) return;
        if (fastCheckScheduled.add(k)) {
            Bukkit.getRegionScheduler().runDelayed(plugin, w, cx, cz, t -> {
                fastCheckScheduled.remove(k);
                doTick(w, cx, cz);
            }, 2L);
        }
    }
    /** Instant-despawns eligible entities of a frozen chunk if no (living) player is within
     *  instant-despawn.radius of its center. Must run on the chunk's own region thread. */
    void despawnIfAbandoned(World world, int cx, int cz, Entity[] entities) {
        double r = settings.instantDespawnRadius;
        double rSq = r * r;
        double ccx = (cx << 4) + 8.0, ccz = (cz << 4) + 8.0;
        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            Location pLoc = p.getLocation();
            double dx = pLoc.getX() - ccx, dz = pLoc.getZ() - ccz;
            if (dx * dx + dz * dz <= rSq) return;
        }
        for (Entity e : entities) {
            if (e.isValid() && isEligibleForInstantDespawn(e)) e.remove();
        }
    }
    /** Shared "is this entity allowed to be instant-despawned" rule, used both by the automatic
     *  frozen-chunk safety net and by /chunk despawn. */
    private boolean isEligibleForInstantDespawn(Entity e) {
        if (e instanceof Player) return false;
        if (e instanceof Projectile) return false;
        if (settings.isIgnored(e.getType()) || settings.isInstantDespawnIgnored(e.getType())) return false;
        if (!settings.instantDespawnNamedEntity && e.customName() != null) return false;
        for (Entity passenger : e.getPassengers()) if (passenger instanceof Player) return false;
        return true;
    }
    /** Manually, instantly despawns every eligible entity in a single chunk right now - used by
     *  /chunk despawn. Must be called from the region thread that owns (world, cx, cz). */
    public int instantDespawnChunk(World world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return 0;
        int removed = 0;
        for (Entity e : world.getChunkAt(cx, cz).getEntities()) {
            if (!e.isValid() || !isEligibleForInstantDespawn(e)) continue;
            e.remove();
            removed++;
        }
        return removed;
    }
    private int countForLimit(Entity[] entities) {
        return settings.countNonIgnoredEntities(entities);
    }
    private void purgeOverload(Entity[] entities) {
        for (Entity e : entities) {
            if (e instanceof Player) continue;
            EntityType t = e.getType();
            if (settings.isIgnored(t)) continue;
            if (settings.shouldPurgeProjectileType(t)) e.remove();
        }
    }
    private void maybeBroadcast(World world, int cx, int cz, Key k, int count) {
        if (!settings.broadcast) return;
        long now = System.currentTimeMillis();
        Long last = lastBroadcastMs.put(k, now);
        if (last != null && now - last < BROADCAST_COOLDOWN_MS) return; // freeze/unfreeze flapping
        Component msg = lang.broadcastBase(world, cx, cz).append(lang.component("messages.broadcast-entity-suffix", "&c (entity=%count%)", "count", count));
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Bukkit.getConsoleSender().sendMessage(msg);
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.hasPermission("chunkfreezer.notify")) pl.sendMessage(msg);
            }
        });
    }
    public void shutdown() {
        active = false;
        monitors.values().forEach(t -> { try { t.cancel(); } catch (Throwable ignored) {} });
        monitors.clear(); lastBroadcastMs.clear();
        coverage.clear(); interest.clear(); refreshedAt.clear(); refreshPending.clear();
        projectileStuckSinceMs.clear();
        fastCheckScheduled.clear();
        freezeGraceUntilMs.clear();
    }
    private record Key(UUID worldId, int cx, int cz) {}
}
