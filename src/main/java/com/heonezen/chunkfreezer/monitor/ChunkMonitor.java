package com.heonezen.chunkfreezer.monitor;

import com.heonezen.chunkfreezer.config.Lang;
import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import io.papermc.paper.entity.Bucketable;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkMonitor implements Listener {

    private static final long REFRESH_COOLDOWN_MS = 200L;
    /** How long a projectile must stay genuinely stuck - or an ownerless XP orb must sit - in a
     *  frozen chunk before it's cleaned up. */
    private static final long PROJECTILE_STUCK_REMOVE_MS = 3000L;
    /** Velocity (blocks/tick, squared) below which a non-arrow projectile counts as motionless
     *  rather than merely slow - about 0.01 blocks/tick, far below anything still actually flying. */
    private static final double STUCK_VELOCITY_SQ = 1.0E-4;

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;
    private final Lang lang;
    private final NamespacedKey dropOwnerKey;

    private final Map<Key, ScheduledTask> monitors                = new ConcurrentHashMap<>();
    private final Map<Key, Boolean>       msgState                = new ConcurrentHashMap<>();
    private final Map<Key, Long>          lastMsgMs               = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Key>>     coverage                = new ConcurrentHashMap<>();
    private final Map<Key, Integer>       interest                = new ConcurrentHashMap<>();
    private final Map<UUID, Long>         refreshedAt             = new ConcurrentHashMap<>();
    private final Map<UUID, Long>         projectileStuckSinceMs  = new ConcurrentHashMap<>();
    private final Set<Key>                fastCheckScheduled      = ConcurrentHashMap.newKeySet();
    private final Map<Key, Long>          freezeGraceUntilMs      = new ConcurrentHashMap<>();

    public ChunkMonitor(Plugin plugin, Settings settings, FrozenChunkManager manager, Lang lang) {
        this.plugin = plugin;
        this.settings = settings;
        this.manager = manager;
        this.lang = lang;
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
                            despawnIfAbandoned(fw, cx, cz, fw.getChunkAt(cx, cz).getEntities());
                    });
                }
            }
        }, 20L);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.getScheduler().execute(plugin, () -> refreshCoverage(p), null, 1L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player qp = e.getPlayer();
        Location ql = qp.getLocation();
        World qw = ql.getWorld();
        removeCoverage(qp.getUniqueId());
        if (!settings.instantDespawnEnabled || qw == null) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            for (Chunk chunk : qw.getLoadedChunks()) {
                int cx = chunk.getX(), cz = chunk.getZ();
                if (!manager.isFrozen(qw, cx, cz)) continue;
                Bukkit.getRegionScheduler().execute(plugin, qw, cx, cz, () -> {
                    if (qw.isChunkLoaded(cx, cz))
                        despawnIfAbandoned(qw, cx, cz, qw.getChunkAt(cx, cz).getEntities());
                });
            }
        }, 10L);
    }
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
        if (!settings.entitiesProtectionEnabled) return;
        if (p == null || !p.isOnline()) return;
        long now = System.currentTimeMillis();
        Long last = refreshedAt.get(p.getUniqueId());
        if (last != null && now - last < REFRESH_COOLDOWN_MS) return;
        refreshedAt.put(p.getUniqueId(), now);
        World w = p.getWorld();
        Location pLoc = p.getLocation();
        int pcx = pLoc.getBlockX() >> 4;
        int pcz = pLoc.getBlockZ() >> 4;
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
        if (settings.entitiesGraceSeconds > 0) {
            freezeGraceUntilMs.put(k, System.currentTimeMillis() + settings.entitiesGraceSeconds * 1000L);
        }
        if (interest.getOrDefault(k, 0) > 0) ensureMonitored(e.getWorld(), k.cx(), k.cz());
        if (settings.entitiesProtectionEnabled && settings.destroyThreshold > 0) {
            World w = e.getWorld();
            int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
            Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                if (!w.isChunkLoaded(cx, cz)) return;
                Entity[] loadedEntities = w.getChunkAt(cx, cz).getEntities();
                if (settings.countNonIgnoredEntities(loadedEntities) > settings.destroyThreshold) {
                    for (Entity ent : loadedEntities) {
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
        msgState.remove(k); lastMsgMs.remove(k); freezeGraceUntilMs.remove(k);
        if (manager.isFrozen(w, cx, cz)) manager.unfreezeChunk(e.getChunk());
    }
    private void startIfLoaded(World w, Key k) {
        if (w.getUID().equals(k.worldId()) && w.isChunkLoaded(k.cx(), k.cz()))
            ensureMonitored(w, k.cx(), k.cz());
    }
    private void ensureMonitored(World world, int cx, int cz) {
        Key k = new Key(world.getUID(), cx, cz);
        if (monitors.containsKey(k) || interest.getOrDefault(k, 0) <= 0) return;
        ScheduledTask t = Bukkit.getRegionScheduler().runAtFixedRate(
                plugin, world, cx, cz,
                st -> tick(world, cx, cz, st),
                1L, settings.checkPeriodTicks);
        monitors.put(k, t);
    }
    private void tick(World world, int cx, int cz, ScheduledTask st) {
        Key k = new Key(world.getUID(), cx, cz);
        if (interest.getOrDefault(k, 0) <= 0) {
            st.cancel(); monitors.remove(k); msgState.remove(k); lastMsgMs.remove(k);
            return;
        }
        doTick(world, cx, cz);
    }
    private final java.util.concurrent.atomic.AtomicBoolean doTickErrorLogged = new java.util.concurrent.atomic.AtomicBoolean(false);
    private void doTick(World world, int cx, int cz) {
        try {
            doTick0(world, cx, cz);
        } catch (Throwable t) {
            /** Defensive: this is the core freeze/unfreeze decision loop, running on a timer for
             *  every monitored chunk (plus an extra delayed call whenever entities spawn quickly in
             *  one). An uncaught exception here would otherwise repeat every cycle and flood the
             *  console - log it once so admins can still see and report it, then keep retrying
             *  silently on later ticks (this deliberately does NOT stop monitoring the chunk). */
            if (doTickErrorLogged.compareAndSet(false, true)) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "entities-protection: unexpected error while checking a chunk", t);
            }
        }
    }
    private void doTick0(World world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return;
        Chunk chunk = world.getChunkAt(cx, cz);
        Key k = new Key(world.getUID(), cx, cz);
        Entity[] entities = chunk.getEntities();
        int count = countForLimit(entities);
        boolean frozenNow = manager.isFrozen(world, cx, cz);
        /** overload-purge only ever runs here, right at the moment a chunk is about to freeze (past
         *  its grace period) - a one-time attempt to bring the count back under the threshold so it
         *  might not need to freeze at all. It must never run merely because the count is still high
         *  while the chunk is already frozen (or during its grace period): a busy-but-already-frozen
         *  chunk (e.g. a mob farm) would otherwise have every matching entity - including one a
         *  player's own arrow that's simply flying through - purged on every single tick. */
        if (!frozenNow && count >= settings.freezeThreshold) {
            Long graceUntil = freezeGraceUntilMs.get(k);
            if (graceUntil == null || System.currentTimeMillis() >= graceUntil) {
                if (settings.overloadPurgeEnabled && !settings.overloadPurgeEntityTypes.isEmpty()) {
                    purgeOverload(entities);
                    count = countForLimit(entities);
                }
                if (count >= settings.freezeThreshold) {
                    manager.freezeChunk(chunk); frozenNow = true;
                }
            }
        } else if (frozenNow && count <= settings.unfreezeThreshold && !manager.isUnfreezeLocked(world, cx, cz)) {
            manager.unfreezeChunk(chunk); frozenNow = false;
        }
        manager.updateEntityCount(world, cx, cz, count);
        if (frozenNow) {
            if (settings.watchItemsEnabled && settings.returnPlayerDroppedItem)
                redirectFrozenItems(world, cx, cz, entities);
            if (settings.watchProjectilesEnabled)
                checkProjectileStuck(entities);
            if (settings.instantDespawnEnabled)
                despawnIfAbandoned(world, cx, cz, entities);
            cleanupBucketedEntities(entities);
        }
        updateBroadcast(world, cx, cz, k, frozenNow, count, frozenNow ? manager.getFreezeCause(world, cx, cz) : null);
    }
    /** Safety net for a bucketable mob (cod, salmon, pufferfish, tropical fish, axolotl, tadpole)
     *  that ends up in a frozen chunk despite the event-level blocks elsewhere
     *  (ProtectionListener#onBucketEmpty, #onEntitySpawn) - removes it only if
     *  {@link Bucketable#isFromBucket()} reports it was released from a bucket rather than having
     *  spawned or bred there naturally, so anything the chunk already had before it froze is never
     *  touched by this. Runs unconditionally whenever frozen, same as those two handlers. */
    private void cleanupBucketedEntities(Entity[] entities) {
        for (Entity e : entities) {
            if (!(e instanceof Bucketable b) || !b.isFromBucket() || !e.isValid() || e.isDead()) continue;
            if (settings.isIgnored(e.getType()) || settings.isFrozenChunkIgnored(e.getType())) continue;
            e.remove();
        }
    }
    /** Removes a projectile only once it's genuinely stuck, never one merely passing through - an
     *  arrow/trident/spectral arrow counts as stuck solely via {@link AbstractArrow#isInBlock()};
     *  every other projectile (snowballs, thrown potions, fireballs, wind charges, etc.) has no
     *  such "embedded" flag, so it counts as stuck only once its velocity is essentially zero -
     *  anything still meaningfully moving is always left alone, no matter how long it's been
     *  present (a weak shot, or one crossing several frozen chunks in a row, can take longer than
     *  the removal delay while still airborne). A fishing bobber legitimately sits almost still for
     *  as long as its owner is fishing, and an elytra-boost firework's motion isn't its own velocity
     *  but its rider's - both are skipped entirely rather than judged by velocity. Ownerless XP
     *  orbs have no velocity concept worth relying on either, so mere presence counts as stuck for
     *  them - a frozen chunk blocks new entities, so one sitting there is never about to be
     *  collected. */
    private void checkProjectileStuck(Entity[] entities) {
        long now = System.currentTimeMillis();
        for (Entity e : entities) {
            UUID id = e.getUniqueId();
            if (!e.isValid() || e.isDead()) { projectileStuckSinceMs.remove(id); continue; }
            boolean stuck;
            if (e instanceof AbstractArrow arrow) stuck = arrow.isInBlock();
            else if (e instanceof ExperienceOrb) stuck = true;
            else if (e instanceof FishHook || e instanceof Firework) continue;
            else if (e instanceof Projectile) stuck = e.getVelocity().lengthSquared() < STUCK_VELOCITY_SQ;
            else continue;
            if (!stuck) { projectileStuckSinceMs.remove(id); continue; }
            long since = projectileStuckSinceMs.computeIfAbsent(id, k -> now);
            if (now - since >= PROJECTILE_STUCK_REMOVE_MS) {
                e.remove();
                projectileStuckSinceMs.remove(id);
            }
        }
        projectileStuckSinceMs.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
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

            int exitCx = Integer.MIN_VALUE, exitCz = Integer.MIN_VALUE;
            if (owner == null) {
                int[] found = findExitCell(world, cx, cz);
                if (found == null) continue; // no safe destination yet; retry next tick
                exitCx = found[0]; exitCz = found[1];
            }

            item.setPickupDelay(Integer.MAX_VALUE);
            ItemStack stack = item.getItemStack().clone();
            final double itemY = item.getLocation().getY(); // captured before remove() - some Paper versions reject further calls on an already-removed entity
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

            Location exit = new Location(world, (exitCx << 4) + 8.0, itemY, (exitCz << 4) + 8.0);
            final int fExitCx = exitCx, fExitCz = exitCz;
            Bukkit.getRegionScheduler().execute(plugin, world, fExitCx, fExitCz, () -> world.dropItemNaturally(exit, stack));
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawnAdaptive(EntitySpawnEvent e) {
        Entity ent = e.getEntity();
        if (ent instanceof Player || ent instanceof Item || settings.isIgnored(ent.getType())) return;
        World w = ent.getWorld();
        Location eLoc = ent.getLocation();
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
    void despawnIfAbandoned(World world, int cx, int cz, Entity[] entities) {
        double r = settings.instantDespawnRadius;
        double ccx = (cx << 4) + 8.0, ccz = (cz << 4) + 8.0;
        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            Location pLoc = p.getLocation();
            double dx = pLoc.getX() - ccx, dz = pLoc.getZ() - ccz;
            if (dx * dx + dz * dz <= r * r) return;
        }
        for (Entity e : entities) {
            if (isEligibleForInstantDespawn(e)) e.remove();
        }
    }
    /** Shared "is this entity allowed to be instant-despawned" rule, used both by the automatic
     *  frozen-chunk safety net above and by the manual /chunk despawn command - so the command
     *  always respects instant-despawn.ignore-entity-types the same way the automatic path does. */
    private boolean isEligibleForInstantDespawn(Entity e) {
        if (e instanceof Player) return false;
        if (e instanceof Projectile) return false;
        if (settings.isIgnored(e.getType()) || settings.isInstantDespawnIgnored(e.getType())) return false;
        if (!settings.instantDespawnNamedEntity && e.customName() != null) return false;
        return true;
    }
    /** Manually, instantly despawns every eligible entity in a single chunk right now, regardless
     *  of whether the chunk is frozen or whether a player is standing nearby - used by
     *  /chunk despawn. Must be called from the region thread that owns (world, cx, cz). Returns
     *  how many entities were actually removed. */
    public int instantDespawnChunk(World world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) return 0;
        int removed = 0;
        for (Entity e : world.getChunkAt(cx, cz).getEntities()) {
            if (!isEligibleForInstantDespawn(e)) continue;
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
        Component msg = lang.broadcastBase(world, cx, cz);
        if (cause == FrozenChunkManager.FreezeCause.ENTITY) {
            msg = msg.append(lang.component("messages.broadcast-entity-suffix", "&c (entity=%count%)", "count", count));
        }
        final Component finalMsg = msg;
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Bukkit.getConsoleSender().sendMessage(finalMsg);
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.hasPermission("chunkfreezer.notify")) pl.sendMessage(finalMsg);
            }
        });
    }
    public void shutdown() {
        monitors.values().forEach(t -> { try { t.cancel(); } catch (Throwable ignored) {} });
        monitors.clear(); msgState.clear(); lastMsgMs.clear();
        coverage.clear(); interest.clear(); refreshedAt.clear();
        projectileStuckSinceMs.clear();
        fastCheckScheduled.clear();
        freezeGraceUntilMs.clear();
    }
    private record Key(UUID worldId, int cx, int cz) {}
}
