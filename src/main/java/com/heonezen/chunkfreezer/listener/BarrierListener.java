package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Lang;
import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.ChunkId;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import com.heonezen.chunkfreezer.util.EntityTypeResolver;
import com.heonezen.chunkfreezer.util.ParticleWalls;
import com.heonezen.chunkfreezer.util.PlayerTasks;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class BarrierListener implements Listener {

    private final Plugin                   plugin;
    private final Settings                 settings;
    private final FrozenChunkManager       frozen;
    private final Lang                     lang;
    private final NamespacedKey            ownerKey;
    private final Map<UUID, WatchState>    watch                = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean>       playerInFrozenCache  = new ConcurrentHashMap<>();
    private final Map<UUID, ChunkId>       lastCheckedChunk     = new ConcurrentHashMap<>();
    private final Map<UUID, Long>          lastNotifyMs         = new ConcurrentHashMap<>();
    private final Set<UUID>                adminTeleportExempt  = ConcurrentHashMap.newKeySet();
    private final Set<UUID>                entityTeleportExempt = ConcurrentHashMap.newKeySet();
    private final Set<UUID>                teleportInFlight     = ConcurrentHashMap.newKeySet();
    private final PlayerTasks              playerTasks;

    private static final long NOTIFY_COOLDOWN_MS = 1000L;
    private static final double MAX_NATURAL_SPEED_SQ = 1.0; // blocks/tick squared; above this treat as external force (piston/knockback), skip bounce
    private static final double MIN_BOUNCE_SPEED = 0.35;    // blocks/tick; guarantees a clearly felt pushback even from a near-standstill approach

    /** Dropped items are only watched if they appear this close (in chunks) to a frozen chunk -
     *  an item farm on the other side of the map has nothing to do with frozen chunks, and
     *  starting a repeating task for every item spawned anywhere would be very expensive. */
    private static final int ITEM_WATCH_RADIUS_CHUNKS = 2;
    private static final long ITEM_WATCH_PERIOD_TICKS = 3L;
    /** Consecutive watch checks an item has to lie still on the ground before watching stops
     *  (~5 seconds). A resting item can't roll into a frozen chunk on its own any more; anything
     *  that later pushes it in is still caught by ChunkMonitor's periodic frozen-chunk sweep. */
    private static final int ITEM_STILL_CHECKS_TO_STOP = 34;

    private static final Particle.DustOptions BORDER_DUST = new Particle.DustOptions(Color.fromRGB(220, 30, 30), 0.85f);

    private final Set<Material> entitySpawningItems;
    private final Set<PlayerTeleportEvent.TeleportCause> blockedCauses;

    /** EntityMoveEvent is the single most expensive event a plugin can listen to: once anyone
     *  listens, Paper builds an event (plus two Locations) for every living entity that moves,
     *  every tick. It is only needed while at least one chunk is frozen, so this listener is
     *  registered only for exactly that time (see {@link #syncLivingMoveListener()}). */
    private final LivingMoveHandler livingMoveHandler = new LivingMoveHandler();
    private final Object livingMoveLock = new Object();
    private boolean livingMoveRegistered;
    private volatile boolean active = true;
    private final AtomicBoolean tickErrorLogged = new AtomicBoolean(false);

    public BarrierListener(Plugin plugin, Settings settings, FrozenChunkManager frozen, Lang lang) {
        this.plugin              = plugin;
        this.settings            = settings;
        this.frozen              = frozen;
        this.lang                = lang;
        this.ownerKey            = new NamespacedKey(plugin, "dropOwner");
        this.entitySpawningItems = settings.extraEntityPlacingItems;
        this.blockedCauses       = settings.blockedTeleportCauses;
        this.playerTasks         = new PlayerTasks(plugin, this::needsPlayerTask, 5L, 6L, this::tickPlayer);
    }

    public void startParticleTasks() {
        if (!needsPlayerTask()) return;
        for (Player p : Bukkit.getOnlinePlayers()) playerTasks.start(p);
    }

    public void shutdown() {
        active = false;
        syncLivingMoveListener();
        playerTasks.close();
        for (WatchState st : watch.values()) {
            ScheduledTask t = st.task;
            if (t != null) try { t.cancel(); } catch (Throwable ignored) {}
        }
        watch.clear();
        playerInFrozenCache.clear();
        lastCheckedChunk.clear();
        lastNotifyMs.clear();
        adminTeleportExempt.clear();
        entityTeleportExempt.clear();
        teleportInFlight.clear();
    }

    /** (Un)registers the EntityMoveEvent handler so that it is registered exactly while it can do
     *  something: barrier + watch-entities on and at least one chunk frozen. Called after every
     *  change to the set of frozen chunks; serialized, so the last call always wins with the
     *  then-current state. */
    public void syncLivingMoveListener() {
        synchronized (livingMoveLock) {
            boolean want = active && plugin.isEnabled() && enabled() && settings.watchEntitiesEnabled && frozen.hasAnyFrozenChunks();
            if (want == livingMoveRegistered) return;
            if (want) Bukkit.getPluginManager().registerEvents(livingMoveHandler, plugin);
            else HandlerList.unregisterAll(livingMoveHandler);
            livingMoveRegistered = want;
        }
    }

    private boolean enabled() { return settings.barrierEnabled; }
    /** Whether this player (or the vehicle they're riding) was inside a frozen chunk as of the
     *  last barrier tick (a few times a second, not a live per-call lookup) - backs the public
     *  API's ChunkFreezerAPI#isPlayerInFrozenChunk(). */
    public boolean isPlayerInFrozenChunk(UUID playerId) {
        return Boolean.TRUE.equals(playerInFrozenCache.get(playerId));
    }
    private static int cx(Location l) { return l.getBlockX() >> 4; }
    private static int cz(Location l) { return l.getBlockZ() >> 4; }
    private static boolean isExempt(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.hasPermission("chunkfreezer.bypass");
    }
    private boolean destFrozen(Location to) {
        World w = to.getWorld();
        return w != null && frozen.isFrozen(w, cx(to), cz(to));
    }
    private int chunkCellsOf(Location loc, double half, int[] outCx, int[] outCz) {
        int minCx = (int) Math.floor(loc.getX() - half) >> 4;
        int maxCx = (int) Math.floor(loc.getX() + half) >> 4;
        int minCz = (int) Math.floor(loc.getZ() - half) >> 4;
        int maxCz = (int) Math.floor(loc.getZ() + half) >> 4;
        int n = 0;
        for (int ix = minCx; ix <= maxCx && n < outCx.length; ix++)
            for (int iz = minCz; iz <= maxCz && n < outCx.length; iz++) {
                outCx[n] = ix; outCz[n] = iz; n++;
            }
        return n;
    }
    private boolean entersNewFrozenChunk(Entity ent, Location from, Location to) {
        return findBlockingFrozenCenter(ent, from, to) != null;
    }
    /** Returns the world-space center of the frozen chunk-cell that {@code to} newly touches
     *  (one {@code from} wasn't already touching), or null if the move doesn't enter new frozen territory. */
    private double[] findBlockingFrozenCenter(Entity ent, Location from, Location to) {
        World w = to.getWorld();
        if (w == null) return null;
        double half = Math.max(ent.getWidth() / 2.0, 0.05);
        double margin = half + 0.05;
        double rx = ((to.getX() % 16) + 16) % 16;
        double rz = ((to.getZ() % 16) + 16) % 16;
        boolean nearBoundary = !(rx > margin && rx < 16 - margin && rz > margin && rz < 16 - margin);
        boolean toPointFrozen = frozen.isFrozen(w, cx(to), cz(to));
        if (!toPointFrozen && !nearBoundary) return null; // fast path: nowhere near any frozen chunk
        int[] toCx = new int[16], toCz = new int[16];
        int toCount = chunkCellsOf(to, half, toCx, toCz);
        int[] fromCx = new int[16], fromCz = new int[16];
        int fromCount = chunkCellsOf(from, half, fromCx, fromCz);
        for (int i = 0; i < toCount; i++) {
            if (!frozen.isFrozen(w, toCx[i], toCz[i])) continue;
            boolean already = false;
            for (int j = 0; j < fromCount; j++) {
                if (fromCx[j] == toCx[i] && fromCz[j] == toCz[i]) { already = true; break; }
            }
            if (!already) return new double[]{(toCx[i] << 4) + 8.0, (toCz[i] << 4) + 8.0};
        }
        return null;
    }
    private boolean needsPlayerTask() {
        return active && enabled() && settings.watchPlayersEnabled;
    }
    private Component frozenChatMessage() {
        return lang.prefixed("messages.frozen-chat", "&cThis chunk is frozen!");
    }
    private void sendFrozen(Player p) {
        p.getScheduler().execute(plugin, () -> p.sendMessage(frozenChatMessage()), null, 1L);
    }
    /** Tells a player their entry attempt was blocked (at most once per second) and applies
     *  damage-on-entry if configured. damage-on-entry: 0 only disables the damage - the chat
     *  warning is still sent, so the player knows why they were bounced back. */
    private void notifyBlocked(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastNotifyMs.get(p.getUniqueId());
        if (last != null && now - last < NOTIFY_COOLDOWN_MS) return;
        lastNotifyMs.put(p.getUniqueId(), now);
        final double damage = settings.watchPlayersEnabled ? settings.damageOnEntry : 0.0;
        p.getScheduler().execute(plugin, () -> {
            if (damage > 0 && p.isValid() && !p.isDead()) p.damage(damage);
            p.sendMessage(frozenChatMessage());
        }, null, 1L);
    }
    /** Called by /chunk go right before teleporting the executing admin, so the very next
     *  teleport (even into a frozen chunk) is allowed through unconditionally - an explicit
     *  admin command should always succeed. Consumed by onPlayerTeleportCleanup. */
    public void allowNextTeleport(UUID playerId) {
        adminTeleportExempt.add(playerId);
    }
    /** Drops a pending one-shot exemption, e.g. when the teleport it was granted for failed
     *  before it ever fired a teleport event. */
    public void revokeTeleportExemption(UUID playerId) {
        adminTeleportExempt.remove(playerId);
    }
    /** Marks this entity's very next PlayerTeleportEvent/EntityTeleportEvent as exempt from the
     *  barrier's own "is the destination frozen" check. Must be called immediately before every
     *  corrective teleport the barrier issues itself - otherwise that correction can land back
     *  inside a chunk that is still frozen and the barrier's own teleport listener would cancel
     *  its own correction. */
    private void exemptOwnCorrection(Entity ent) {
        if (ent instanceof Player pl) adminTeleportExempt.add(pl.getUniqueId());
        else entityTeleportExempt.add(ent.getUniqueId());
    }
    private void clearOwnCorrection(Entity ent) {
        if (ent instanceof Player pl) adminTeleportExempt.remove(pl.getUniqueId());
        else entityTeleportExempt.remove(ent.getUniqueId());
    }
    /** Corrective teleport that also works for a vehicle carrying passengers (a plain teleport
     *  refuses to move an entity that has passengers, which used to leave minecarts/boats with a
     *  rider stuck inside the frozen chunk). */
    private static CompletableFuture<Boolean> correctiveTeleport(Entity ent, Location safe) {
        return ent.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }
    private void returnItemToPlayer(Player p, ItemStack item, Location fallback) {
        boolean scheduled = p.getScheduler().execute(plugin, () -> {
            p.getInventory().addItem(item).values().forEach(rem -> p.getWorld().dropItemNaturally(p.getLocation(), rem));
            p.updateInventory();
        }, () -> dropAt(fallback, item), 1L);
        if (!scheduled) dropAt(fallback, item);
    }
    private void dropAt(Location loc, ItemStack item) {
        if (loc == null) return;
        World w = loc.getWorld();
        if (w == null) return;
        Bukkit.getRegionScheduler().execute(plugin, w, cx(loc), cz(loc), () -> w.dropItemNaturally(loc, item));
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHitInFrozen(ProjectileHitEvent e) {
        if (!enabled() || !settings.watchProjectilesEnabled || !frozen.hasAnyFrozenChunks()) return;
        Entity ent = e.getEntity();
        if (ent instanceof EnderPearl) return;
        Entity hitEntity = e.getHitEntity();
        boolean isSplash = ent instanceof ThrownPotion || ent instanceof ThrownExpBottle;
        if (hitEntity != null && !isSplash) return; // arrows/tridents hitting a living entity: let damage apply normally
        Block hitBlock = e.getHitBlock();
        Location loc = hitEntity != null ? hitEntity.getLocation()
                : hitBlock != null ? hitBlock.getLocation().add(0.5, 0.5, 0.5)
                : ent.getLocation();
        World w = loc.getWorld();
        if (w == null || !frozen.isFrozen(w, cx(loc), cz(loc))) return;
        if (ent instanceof Trident trident && trident.getShooter() instanceof Player shooter && !isExempt(shooter)) {
            e.setCancelled(true);
            ItemStack item = trident.getItemStack().clone();
            trident.remove();
            returnItemToPlayer(shooter, item, loc);
            return;
        }
        if (ent instanceof ThrownPotion potion && potion.getShooter() instanceof Player thrower && !isExempt(thrower)) {
            e.setCancelled(true);
            ItemStack item = potion.getItem().clone();
            potion.remove();
            returnItemToPlayer(thrower, item, loc);
            return;
        }
        if (ent instanceof ThrownExpBottle bottle && bottle.getShooter() instanceof Player thrower && !isExempt(thrower)) {
            e.setCancelled(true);
            bottle.remove();
            returnItemToPlayer(thrower, new ItemStack(Material.EXPERIENCE_BOTTLE, 1), loc);
            return;
        }
        if (!ent.isValid() || ent.isDead()) return;
        final Vector vel = ent.getVelocity().clone();
        e.setCancelled(true);
        if (vel.lengthSquared() > 1e-6) {
            ent.getScheduler().execute(plugin, () -> {
                if (ent.isValid() && !ent.isDead()) ent.setVelocity(vel);
            }, null, 1L);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnderPearlHit(ProjectileHitEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled || !frozen.hasAnyFrozenChunks()) return;
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;
        if (isExempt(p)) return;
        Location loc = pearl.getLocation();
        World w = loc.getWorld();
        if (w == null || !frozen.isFrozen(w, cx(loc), cz(loc))) return;
        e.setCancelled(true);
        Location fallback = loc.clone();
        pearl.remove();
        returnItemToPlayer(p, new ItemStack(Material.ENDER_PEARL, 1), fallback);
        notifyBlocked(p);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled || !frozen.hasAnyFrozenChunks()) return;
        Player p = e.getPlayer();
        if (p.getVehicle() != null || isExempt(p)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (findBlockingFrozenCenter(p, from, to) == null) return;
        e.setCancelled(true);
        p.setVelocity(new Vector());
        notifyBlocked(p);
        UUID id = p.getUniqueId();
        if (!teleportInFlight.add(id)) return; // a correction is already resolving; don't stack another
        Location safe = clampAlongPath(p, from, to);
        Vector bounce = computeBounce(from, to, settings.playerBounceMultiplier);
        exemptOwnCorrection(p);
        p.teleportAsync(safe).whenComplete((ok, err) -> {
            teleportInFlight.remove(id);
            if (Boolean.TRUE.equals(ok)) p.getScheduler().execute(plugin, () -> p.setVelocity(bounce), null, 1L);
            else clearOwnCorrection(p);
        });
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        /** Peek, don't consume here: onPlayerTeleportWithMount is a second HIGHEST-priority
         *  handler for this same event and needs to see the same exemption. The MONITOR-priority
         *  onPlayerTeleportCleanup below removes it once, after both have had a chance to run. */
        if (adminTeleportExempt.contains(p.getUniqueId())) return;
        if (!enabled() || !frozen.hasAnyFrozenChunks()) return;
        if (isExempt(p)) return;
        Location to = e.getTo(), from = e.getFrom();
        if (to == null || from == null) return;
        /** Getting out of bed teleports the player onto the bed - if that bed sits in a frozen
         *  chunk, they would be left standing inside it. Handled regardless of watch-players. */
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.EXIT_BED && destFrozen(to)) {
            Location safe = findNearestUnfrozen(to);
            if (safe != null) { e.setTo(safe); return; }
            e.setCancelled(true); return;
        }
        if (!settings.watchPlayersEnabled) return;
        if (isChorusTeleport(e.getCause()) && destFrozen(to)) {
            Location reroll = rerollChorusDestination(to);
            if (reroll != null) { e.setTo(reroll); return; }
            e.setCancelled(true);
            sendFrozen(p);
            return;
        }
        if (!blockedCauses.contains(e.getCause()) || !destFrozen(to)) return;
        e.setCancelled(true);
    }
    /** Since Minecraft 1.21.2 chorus fruit is a "consumable effect" and its teleport is reported
     *  as CONSUMABLE_EFFECT; CHORUS_FRUIT is only kept (deprecated) for older servers. Checking
     *  just CHORUS_FRUIT meant chorus fruit was never blocked on current versions. */
    @SuppressWarnings({"deprecation", "removal"})
    private static boolean isChorusTeleport(PlayerTeleportEvent.TeleportCause cause) {
        return cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT;
    }
    private Location rerollChorusDestination(Location vanillaTo) {
        World w = vanillaTo.getWorld();
        if (w == null) return null;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 20; i++) {
            double x = vanillaTo.getX() + (rng.nextDouble() - 0.5) * 16.0;
            double z = vanillaTo.getZ() + (rng.nextDouble() - 0.5) * 16.0;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (frozen.isFrozen(w, bx >> 4, bz >> 4)) continue;
            // Only read blocks this thread is allowed to touch (Folia) and that are already loaded.
            if (!w.isChunkLoaded(bx >> 4, bz >> 4) || !Bukkit.isOwnedByCurrentRegion(w, bx >> 4, bz >> 4)) continue;
            int y = w.getHighestBlockYAt(bx, bz) + 1;
            return new Location(w, x, y, z, vanillaTo.getYaw(), vanillaTo.getPitch());
        }
        return null;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleportWithMount(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (adminTeleportExempt.contains(p.getUniqueId())) return;
        Entity veh = p.getVehicle();
        if (veh != null && entityTeleportExempt.contains(veh.getUniqueId())) return;
        if (!enabled() || !settings.watchPlayersEnabled || !frozen.hasAnyFrozenChunks()) return;
        if (veh == null || isExempt(p)) return;
        Location to = e.getTo(), from = e.getFrom();
        if (to == null || from == null || !destFrozen(to)) return;
        e.setCancelled(true);
    }
    /** Runs after every other listener has had a chance to see adminTeleportExempt, then clears
     *  the one-shot flag so it can't leak into a later, unrelated teleport. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleportCleanup(PlayerTeleportEvent e) {
        adminTeleportExempt.remove(e.getPlayer().getUniqueId());
    }
    /** Registered only while at least one chunk is frozen - see {@link #syncLivingMoveListener()}. */
    public final class LivingMoveHandler implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onLivingMove(EntityMoveEvent e) {
            handleLivingMove(e);
        }
    }
    private void handleLivingMove(EntityMoveEvent e) {
        if (!active || !enabled() || !settings.watchEntitiesEnabled || !frozen.hasAnyFrozenChunks()) return;
        LivingEntity le = e.getEntity();
        if (le instanceof Player || settings.isIgnored(le.getType())) return;
        if (!e.hasChangedBlock() && !e.hasChangedPosition()) return;
        Location from = e.getFrom(), to = e.getTo();
        if (!entersNewFrozenChunk(le, from, to)) return;
        boolean hasPlayerPassenger = false, allPlayersExempt = true;
        for (Entity pass : le.getPassengers()) {
            if (!(pass instanceof Player player)) continue;
            hasPlayerPassenger = true;
            if (!isExempt(player)) allPlayersExempt = false;
        }
        if (hasPlayerPassenger && allPlayersExempt) return;
        e.setCancelled(true);
        le.setVelocity(computeBounce(from, to, settings.entityBounceMultiplier));
        for (Entity pass : le.getPassengers()) {
            if (pass instanceof Player player && !isExempt(player)) notifyBlocked(player);
        }
        if (le instanceof Mob mob) mob.setTarget(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent e) {
        if (entityTeleportExempt.contains(e.getEntity().getUniqueId())) return;
        if (!enabled() || !frozen.hasAnyFrozenChunks() || !settings.watchEntitiesEnabled) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player || settings.isIgnored(ent.getType())) return;
        Location to = e.getTo();
        if (to == null) return;
        if (entersNewFrozenChunk(ent, e.getFrom(), to)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityTeleportCleanup(EntityTeleportEvent e) {
        entityTeleportExempt.remove(e.getEntity().getUniqueId());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!enabled() || !settings.watchEntitiesEnabled || !frozen.hasAnyFrozenChunks()) return;
        Vehicle v = e.getVehicle();
        Location to = e.getTo(), from = e.getFrom();
        if (!entersNewFrozenChunk(v, from, to)) return;
        boolean hasPlayerPassenger = false, allPlayersExempt = true;
        for (Entity pass : v.getPassengers()) {
            if (!(pass instanceof Player player)) continue;
            hasPlayerPassenger = true;
            if (!isExempt(player)) allPlayersExempt = false;
        }
        if (hasPlayerPassenger && allPlayersExempt) return;
        if (!teleportInFlight.add(v.getUniqueId())) return; // a correction is already resolving; don't stack another
        Location safe = clampAlongPath(v, from, to);
        for (Entity pass : v.getPassengers()) {
            if (pass instanceof Player player && !isExempt(player)) notifyBlocked(player);
        }
        correctVehicle(v, safe, computeBounce(from, to, settings.entityBounceMultiplier), 0);
    }
    /** Teleports a vehicle back out of a frozen chunk it already physically entered -
     *  VehicleMoveEvent can't be cancelled, so this after-the-fact correction is the only tool
     *  the API gives us. Retries a few times if the teleport itself fails. */
    private void correctVehicle(Vehicle v, Location safe, Vector bounce, int attempt) {
        UUID id = v.getUniqueId();
        if (!active || !v.isValid() || v.isDead()) { teleportInFlight.remove(id); return; }
        exemptOwnCorrection(v);
        correctiveTeleport(v, safe).whenComplete((ok, err) -> {
            if (Boolean.TRUE.equals(ok)) {
                teleportInFlight.remove(id);
                v.getScheduler().execute(plugin, () -> {
                    if (!v.isValid() || v.isDead()) return;
                    v.setVelocity(bounce);
                    if (v instanceof Mob mob) mob.setTarget(null);
                }, null, 1L);
                return;
            }
            clearOwnCorrection(v);
            boolean retried = attempt < 3 && v.getScheduler().execute(plugin, () -> correctVehicle(v, safe, bounce, attempt + 1),
                    () -> teleportInFlight.remove(id), 1L);
            if (!retried) teleportInFlight.remove(id);
        });
    }

    /** Reverse-and-scale the attempted horizontal movement into a bounce-back velocity.
     *  If the attempted delta is far beyond any normal entity movement speed (a piston push,
     *  knockback, etc.), returns a zero vector instead of fighting that external force. */
    private Vector computeBounce(Location from, Location to, double multiplier) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq > MAX_NATURAL_SPEED_SQ) return new Vector();
        if (lenSq < 1e-9) return new Vector();
        double len = Math.sqrt(lenSq);
        double speed = Math.max(len, MIN_BOUNCE_SPEED) * multiplier;
        return new Vector(-dx / len * speed, 0, -dz / len * speed);
    }
    private Location clampAlongPath(Entity ent, Location from, Location to) {
        World w = to.getWorld();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (w == null || (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9)) return from.clone();
        double half = Math.max(ent.getWidth() / 2.0, 0.05);
        double height = Math.max(ent.getHeight(), 0.1);
        double baseY = to.getY();
        int[] permittedCx = new int[16], permittedCz = new int[16];
        int permittedCount = chunkCellsOf(from, half, permittedCx, permittedCz);
        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 10; i++) {
            double mid = (lo + hi) * 0.5;
            double mx = from.getX() + dx * mid;
            double mz = from.getZ() + dz * mid;
            BoundingBox midBox = new BoundingBox(mx - half, baseY, mz - half, mx + half, baseY + height, mz + half);
            if (touchesUnpermittedFrozenCell(midBox, w, permittedCx, permittedCz, permittedCount)) hi = mid; else lo = mid;
        }
        Location safe = to.clone();
        safe.setX(from.getX() + dx * lo);
        safe.setZ(from.getZ() + dz * lo);
        return safe;
    }
    private boolean touchesUnpermittedFrozenCell(BoundingBox box, World w, int[] permittedCx, int[] permittedCz, int permittedCount) {
        int minCx = (int) Math.floor(box.getMinX()) >> 4;
        int maxCx = (int) Math.floor(box.getMaxX()) >> 4;
        int minCz = (int) Math.floor(box.getMinZ()) >> 4;
        int maxCz = (int) Math.floor(box.getMaxZ()) >> 4;
        for (int ix = minCx; ix <= maxCx; ix++) {
            for (int iz = minCz; iz <= maxCz; iz++) {
                if (!frozen.isFrozen(w, ix, iz)) continue;
                boolean permitted = false;
                for (int j = 0; j < permittedCount; j++) {
                    if (permittedCx[j] == ix && permittedCz[j] == iz) { permitted = true; break; }
                }
                if (!permitted) return true;
            }
        }
        return false;
    }
    /** Pre-emptive block for every item that puts something into the world: mob buckets,
     *  water/lava/powder-snow buckets, boats, minecarts, spawn eggs, armor stands, item frames,
     *  leads, ...
     *
     *  <p><b>Deliberately does NOT skip already-cancelled events:</b> since Minecraft 1.21.9 a
     *  bucket is used through {@code Item#use}, reached via the "use item in air" path for which
     *  Paper pre-cancels the PlayerInteractEvent - with ignoreCancelled the handler would never
     *  see bucket uses. Both interaction results are denied explicitly for the same reason. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntitySpawnItemUse(PlayerInteractEvent e) {
        if (!enabled() || !frozen.hasAnyFrozenChunks()) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType().isAir() || !isEntitySpawningItem(item)) return;
        Player p = e.getPlayer();
        // Cheap hash lookups first; only read the item's meta once the target really is frozen.
        if (!targetsFrozenChunk(e, p)) return;
        EntityType resulting = EntityTypeResolver.resolveWithMeta(item);
        if (resulting != null && settings.isFrozenChunkIgnored(resulting)) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        e.setCancelled(true);
        p.getScheduler().execute(plugin, p::updateInventory, null, 1L);
    }
    /** Whether this interaction would put something into a frozen chunk: the clicked block, the
     *  block on the clicked face (where a bucket empties / a mob appears - matters at chunk
     *  borders), or the player's own position when clicking air. */
    private boolean targetsFrozenChunk(PlayerInteractEvent e, Player p) {
        Block clicked = e.getClickedBlock();
        if (clicked == null) return frozen(p.getLocation());
        if (frozenBlock(clicked)) return true;
        org.bukkit.block.BlockFace face = e.getBlockFace();
        if (face != org.bukkit.block.BlockFace.SELF) {
            return frozenBlock(clicked.getRelative(face));
        }
        return false;
    }
    private boolean frozen(Location loc) {
        World w = loc.getWorld();
        return w != null && frozen.isFrozen(w, cx(loc), cz(loc));
    }
    private boolean frozenBlock(Block b) {
        return frozen.isFrozen(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) {
        if (!enabled() || !settings.watchLiquids || !frozen.hasAnyFrozenChunks()) return;
        Block from = e.getBlock(), to = e.getToBlock();
        int fcx = from.getX() >> 4, fcz = from.getZ() >> 4;
        int tcx = to.getX() >> 4, tcz = to.getZ() >> 4;
        if (fcx == tcx && fcz == tcz) return;
        Material type = from.getType();
        if (type != Material.WATER && type != Material.LAVA) return;
        if (frozen.isFrozen(from.getWorld(), tcx, tcz)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!enabled() || !settings.watchItemsEnabled || !settings.returnPlayerDroppedItem || !frozen.hasAnyFrozenChunks()) return;
        Location l = e.getLocation();
        if (!frozen.isNearFrozen(l.getWorld(), cx(l), cz(l), ITEM_WATCH_RADIUS_CHUNKS)) return;
        startWatching(e.getEntity());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent e) {
        if (!enabled() || !settings.watchItemsEnabled || !settings.returnPlayerDroppedItem || !frozen.hasAnyFrozenChunks()) return;
        Player p = e.getPlayer();
        Location pl = p.getLocation();
        if (frozen.isFrozen(pl.getWorld(), cx(pl), cz(pl))) {
            e.setCancelled(true);
            p.getScheduler().execute(plugin, p::updateInventory, null, 1L);
            return;
        }
        Item dropped = e.getItemDrop();
        dropped.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (needsPlayerTask()) playerTasks.start(e.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!needsPlayerTask()) return;
        UUID id = e.getPlayer().getUniqueId();
        playerInFrozenCache.remove(id);
        lastCheckedChunk.remove(id);
        playerTasks.restart(e.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        playerTasks.stop(id);
        playerInFrozenCache.remove(id);
        lastCheckedChunk.remove(id);
        lastNotifyMs.remove(id);
        adminTeleportExempt.remove(id);
        teleportInFlight.remove(id);
    }
    private void tickPlayer(Player p, ScheduledTask st) {
        try {
            tickPlayer0(p, st);
        } catch (Throwable t) {
            /** Safety-net check: log the first failure, keep retrying on later ticks (unlike the
             *  purely cosmetic region border, this must not silently stop protecting the player). */
            if (tickErrorLogged.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "barrier: unexpected error in the frozen-chunk safety-net check", t);
            }
        }
    }
    private void tickPlayer0(Player p, ScheduledTask st) {
        if (!p.isOnline()) { playerTasks.finish(p.getUniqueId(), st); return; }
        if (!p.isValid() || !settings.watchPlayersEnabled) return;
        UUID id = p.getUniqueId();
        if (!frozen.hasAnyFrozenChunks()) {
            playerInFrozenCache.put(id, false);
            lastCheckedChunk.remove(id);
            return;
        }
        Entity vehicle = p.getVehicle();
        Entity ref = vehicle != null ? vehicle : p;
        Location loc = ref.getLocation();
        World w = loc.getWorld();
        if (w == null) return;
        ChunkId here = ChunkId.of(w, cx(loc), cz(loc));
        ChunkId last = lastCheckedChunk.put(id, here);
        boolean nowInFrozen = frozen.isFrozen(w, here.x(), here.z());
        playerInFrozenCache.put(id, nowInFrozen);
        boolean exempt = isExempt(p);
        if (nowInFrozen && last != null && !last.equals(here) && !exempt) {
            /** The player is in a frozen chunk now, but their last known chunk was a different one
             *  - they crossed the border by some means the real-time move-blocking didn't catch
             *  (piston push, water current, ...). Correct it. If last == here they were already
             *  standing in this chunk when it froze, which is deliberately left alone. */
            if (!teleportInFlight.contains(ref.getUniqueId())) {
                Location safe = findNearestUnfrozen(loc);
                if (safe != null) {
                    double awayX = safe.getX() - loc.getX(), awayZ = safe.getZ() - loc.getZ();
                    if (awayX * awayX + awayZ * awayZ > 1e-6) {
                        safe.setDirection(new Vector(awayX, 0, awayZ));
                        safe.setPitch(loc.getPitch());
                    } else {
                        safe.setDirection(loc.getDirection());
                    }
                    UUID refId = ref.getUniqueId();
                    teleportInFlight.add(refId);
                    exemptOwnCorrection(ref);
                    correctiveTeleport(ref, safe).whenComplete((ok, err) -> {
                        teleportInFlight.remove(refId);
                        if (!Boolean.TRUE.equals(ok)) { clearOwnCorrection(ref); return; }
                        p.getScheduler().execute(plugin, () -> p.setVelocity(new Vector()), null, 1L);
                        if (vehicle != null) vehicle.getScheduler().execute(plugin, () -> vehicle.setVelocity(new Vector()), null, 1L);
                    });
                }
            }
            notifyBlocked(p);
            return;
        }
        if (settings.notifyWhenInFrozen && nowInFrozen && !exempt) {
            p.sendActionBar(lang.component("messages.frozen-actionbar", "&cThis chunk is frozen!"));
        }
        if (settings.particlesOnEntry > 0) spawnBorderParticles(p);
    }
    private void spawnBorderParticles(Player p) {
        Location pLoc = p.getLocation();
        World w = pLoc.getWorld();
        if (w == null) return;
        int pcx = cx(pLoc), pcz = cz(pLoc);
        double px = pLoc.getX(), pz = pLoc.getZ(), py = pLoc.getY();
        double d = settings.particlesOnEntry;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int fx = pcx + dx, fz = pcz + dz;
                if (!frozen.isFrozen(w, fx, fz)) continue;
                double x0 = fx << 4, z0 = fz << 4;
                boolean eastFrozen = frozen.isFrozen(w, fx + 1, fz);
                boolean westFrozen = frozen.isFrozen(w, fx - 1, fz);
                boolean southFrozen = frozen.isFrozen(w, fx, fz + 1);
                boolean northFrozen = frozen.isFrozen(w, fx, fz - 1);
                // East face: outer border always visible; a shared internal border (east neighbor also frozen) only shows to a player standing inside this chunk or that neighbor.
                boolean eastInsider = (pcx == fx && pcz == fz) || (pcx == fx + 1 && pcz == fz);
                if ((!eastFrozen || eastInsider) && Math.abs(px - (x0 + 16.0)) <= d && !(pz < z0 && northFrozen) && !(pz > z0 + 16 && southFrozen))
                    ParticleWalls.wallAtX(p, x0 + 16.0, z0, z0 + 16.0, pz, py, d, BORDER_DUST);
                // West face: the west neighbor (if also frozen) already owns this line via its own east face.
                if (!westFrozen && Math.abs(px - x0) <= d && !(pz < z0 && northFrozen) && !(pz > z0 + 16 && southFrozen))
                    ParticleWalls.wallAtX(p, x0, z0, z0 + 16.0, pz, py, d, BORDER_DUST);
                // South face: same insider rule as East.
                boolean southInsider = (pcx == fx && pcz == fz) || (pcx == fx && pcz == fz + 1);
                if ((!southFrozen || southInsider) && Math.abs(pz - (z0 + 16.0)) <= d)
                    ParticleWalls.wallAtZ(p, z0 + 16.0, x0, x0 + 16.0, px, py, d, BORDER_DUST);
                // North face: owned by the north neighbor's south face when both are frozen.
                if (!northFrozen && Math.abs(pz - z0) <= d)
                    ParticleWalls.wallAtZ(p, z0, x0, x0 + 16.0, px, py, d, BORDER_DUST);
            }
        }
    }
    private void startWatching(Item item) {
        if (!active || !item.isValid() || item.isDead()) return;
        UUID id = item.getUniqueId();
        WatchState st = new WatchState();
        st.lastSafe = item.getLocation();
        if (watch.putIfAbsent(id, st) != null) return;
        ScheduledTask task = item.getScheduler().runAtFixedRate(plugin, scheduled -> tickWatch(item, scheduled),
                () -> watch.remove(id, st), ITEM_WATCH_PERIOD_TICKS, ITEM_WATCH_PERIOD_TICKS);
        if (task == null) watch.remove(id, st); else st.task = task;
    }
    private void stopWatching(Item item, ScheduledTask task) {
        task.cancel();
        watch.remove(item.getUniqueId());
    }
    private void tickWatch(Item item, ScheduledTask task) {
        if (!active || !item.isValid() || item.isDead()) { stopWatching(item, task); return; }
        WatchState st = watch.get(item.getUniqueId());
        if (st == null) { task.cancel(); return; }
        Location now = item.getLocation();
        World w = now.getWorld();
        if (w == null) return;
        int icx = cx(now), icz = cz(now);
        if (!frozen.isFrozen(w, icx, icz)) {
            st.lastSafe = now;
            // Nothing frozen nearby any more, or the item has come to rest: no point in watching it.
            if (!frozen.isNearFrozen(w, icx, icz, ITEM_WATCH_RADIUS_CHUNKS)) { stopWatching(item, task); return; }
            if (item.isOnGround() && item.getVelocity().lengthSquared() < 1e-4) {
                if (++st.stillChecks >= ITEM_STILL_CHECKS_TO_STOP) stopWatching(item, task);
            } else {
                st.stillChecks = 0;
            }
            return;
        }
        if (!settings.returnPlayerDroppedItem || item.getPickupDelay() == Integer.MAX_VALUE) { stopWatching(item, task); return; }
        Player owner = resolveOwner(item);
        Location exit = st.lastSafe;
        if (exit == null || exit.getWorld() == null || frozen.isFrozen(exit.getWorld(), cx(exit), cz(exit))) exit = findNearestUnfrozen(now);
        if (owner == null && exit == null) return; // no safe destination yet; keep watching, retry next tick
        item.setPickupDelay(Integer.MAX_VALUE);
        ItemStack stack = item.getItemStack().clone();
        item.remove();
        stopWatching(item, task);
        if (owner != null) {
            returnItemToPlayer(owner, stack, exit != null ? exit : now);
            return;
        }
        dropAt(exit.clone(), stack);
    }
    private Player resolveOwner(Item item) {
        String ownerStr = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerStr == null) return null;
        try {
            Player candidate = Bukkit.getPlayer(UUID.fromString(ownerStr));
            return candidate != null && candidate.isOnline() ? candidate : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
    /** The closest spot just across the nearest unfrozen face of the frozen chunk containing
     *  {@code loc}, at a height where an entity actually fits (so a correction never teleports a
     *  player into a wall), or null if all four neighbours are frozen too. */
    private Location findNearestUnfrozen(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        int fcx = cx(loc), fcz = cz(loc);
        double rx = loc.getX() - (fcx << 4); // 0..16
        double rz = loc.getZ() - (fcz << 4);
        double[] dist = {rx, 16 - rx, rz, 16 - rz};
        int[] ndx  = {-1, 1, 0,  0};
        int[] ndz  = { 0, 0, -1, 1};
        double[] offA = {15.4, 0.6, 0, 0}; // x-offset in target chunk
        double[] offB = {0, 0, 15.4, 0.6}; // z-offset in target chunk
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));
        for (int i : order) {
            int ncx = fcx + ndx[i], ncz = fcz + ndz[i];
            if (frozen.isFrozen(w, ncx, ncz)) continue;
            double bx, bz;
            if (ndx[i] != 0) {
                bx = (ncx << 4) + offA[i];
                bz = Math.max((ncz << 4) + 0.4, Math.min((ncz << 4) + 15.6, loc.getZ()));
            } else {
                bz = (ncz << 4) + offB[i];
                bx = Math.max((ncx << 4) + 0.4, Math.min((ncx << 4) + 15.6, loc.getX()));
            }
            Location out = new Location(w, bx, loc.getY(), bz, loc.getYaw(), loc.getPitch());
            adjustToStandable(out);
            return out;
        }
        return null;
    }
    /** Nudges {@code loc} up (preferred) or down by a few blocks until there are two passable
     *  blocks for the body. Only reads blocks that are loaded and owned by the current thread
     *  (Folia); otherwise leaves the location untouched. */
    private static void adjustToStandable(Location loc) {
        World w = loc.getWorld();
        int bx = loc.getBlockX(), bz = loc.getBlockZ();
        if (w == null || !w.isChunkLoaded(bx >> 4, bz >> 4) || !Bukkit.isOwnedByCurrentRegion(w, bx >> 4, bz >> 4)) return;
        int baseY = loc.getBlockY();
        int minY = w.getMinHeight(), maxY = w.getMaxHeight() - 2;
        for (int dy = 0; dy <= 4; dy++) {
            int y = baseY + dy;
            if (y >= minY && y <= maxY && fits(w, bx, y, bz)) { if (dy != 0) loc.setY(y); return; }
        }
        for (int dy = 1; dy <= 4; dy++) {
            int y = baseY - dy;
            if (y >= minY && y <= maxY && fits(w, bx, y, bz)) { loc.setY(y); return; }
        }
    }
    private static boolean fits(World w, int x, int y, int z) {
        return w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable();
    }
    /** All of the actual Material -> EntityType classification lives in EntityTypeResolver - this
     *  is just the admin-configurable extra list layered on top of it. */
    private boolean isEntitySpawningItem(ItemStack stack) {
        return entitySpawningItems.contains(stack.getType()) || EntityTypeResolver.isPlaceable(stack);
    }
    private static final class WatchState {
        volatile Location      lastSafe;
        volatile ScheduledTask task;
        int                    stillChecks;
    }
}
