package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BarrierListener implements Listener {

    private final Plugin             plugin;
    private final Settings           settings;
    private final FrozenChunkManager frozen;
    private final NamespacedKey      ownerKey;
    private final Map<UUID, WatchState>    watch               = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> particleTasks       = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean>       playerInFrozenCache = new ConcurrentHashMap<>();

    private static final Particle.DustOptions BORDER_DUST = new Particle.DustOptions(Color.fromRGB(220, 30, 30), 0.85f);
    private static final Component FROZEN_ACTIONBAR = Component.text("⚠ this chunk is frozen ⚠", NamedTextColor.RED);
    private static final Random RNG = new Random();

    private static final Set<Material> PLACE_ENTITY_ITEMS = EnumSet.of(
            Material.ARMOR_STAND,
            Material.END_CRYSTAL,
            Material.ITEM_FRAME,
            Material.GLOW_ITEM_FRAME,
            Material.PAINTING,
            Material.LEAD);

    private static final Set<Material> ENTITY_BUCKETS = EnumSet.of(
            Material.COD_BUCKET,
            Material.SALMON_BUCKET,
            Material.TROPICAL_FISH_BUCKET,
            Material.PUFFERFISH_BUCKET,
            Material.AXOLOTL_BUCKET,
            Material.TADPOLE_BUCKET);

    @SuppressWarnings("deprecation")
    private static final Set<PlayerTeleportEvent.TeleportCause> BLOCKED_CAUSES = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.DISMOUNT,
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.UNKNOWN);

    public BarrierListener(Plugin plugin, Settings settings, FrozenChunkManager frozen) {
        this.plugin   = plugin;
        this.settings = settings;
        this.frozen   = frozen;
        this.ownerKey = new NamespacedKey(plugin, "dropOwner");
    }

    public void startParticleTasks() {
        if (!needsPlayerTask()) return;
        for (Player p : Bukkit.getOnlinePlayers()) startParticleTask(p);
    }
    public void shutdown() {
        particleTasks.values().forEach(t -> { try { t.cancel(); } catch (Throwable ignored) {} });
        particleTasks.clear();
        playerInFrozenCache.clear();
    }
    private boolean enabled()         { return settings.barrierEnabled; }
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
    /* Returns the world-space center of the frozen chunk-cell that {@code to} newly touches
       (one {@code from} wasn't already touching), or null if the move doesn't enter new frozen territory. */
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
        return enabled() && settings.watchPlayersEnabled;
    }
    private void sendFrozen(Player p) {
        p.getScheduler().execute(plugin, () -> p.sendMessage(Component.text("This chunk is frozen!", NamedTextColor.RED)), null, 1L);
    }
    private void notifyBlocked(Player p) {
        p.getScheduler().execute(plugin, () -> {
            p.sendMessage(Component.text("This chunk is frozen!", NamedTextColor.RED));
            if (settings.watchPlayersEnabled && settings.damageOnEntry > 0) {
                p.damage(settings.damageOnEntry);
            }
        }, null, 1L);
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
        org.bukkit.block.Block hitBlock = e.getHitBlock();
        Location loc = hitEntity != null ? hitEntity.getLocation() : hitBlock != null ? hitBlock.getLocation().add(0.5, 0.5, 0.5) : ent.getLocation();
        World w = loc.getWorld();
        if (w == null) return;
        Location fallback = loc;
        boolean inFrozen = frozen.isFrozen(w, cx(loc), cz(loc));
        if (!inFrozen) return;
        if (ent instanceof Trident trident && trident.getShooter() instanceof Player shooter && !isExempt(shooter)) {
            e.setCancelled(true);
            @SuppressWarnings("deprecation") ItemStack item = ((AbstractArrow) trident).getItem().clone();
            trident.remove();
            returnItemToPlayer(shooter, item, fallback);
            return;
        }
        if (ent instanceof ThrownPotion potion && potion.getShooter() instanceof Player thrower && !isExempt(thrower)) {
            e.setCancelled(true);
            ItemStack item = potion.getItem().clone();
            potion.remove();
            returnItemToPlayer(thrower, item, fallback);
            return;
        }
        if (ent instanceof ThrownExpBottle bottle && bottle.getShooter() instanceof Player thrower && !isExempt(thrower)) {
            e.setCancelled(true);
            bottle.remove();
            returnItemToPlayer(thrower, new ItemStack(Material.EXPERIENCE_BOTTLE, 1), fallback);
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
        if (isExempt(p) || p.getVehicle() != null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (findBlockingFrozenCenter(p, from, to) == null) return;
        Location safe = clampAlongPath(p, from, to);
        e.setTo(safe);
        p.setVelocity(new Vector());
        notifyBlocked(p);
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled || !frozen.hasAnyFrozenChunks()) return;
        Player p = e.getPlayer();
        if (isExempt(p)) return;
        Location to = e.getTo(), from = e.getFrom();
        if (to == null || from == null) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.EXIT_BED && destFrozen(to)) {
            Location safe = findNearestUnfrozen(to);
            if (safe != null) { e.setTo(safe); return; }
            e.setCancelled(true); e.setTo(from); return;
        }
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT && destFrozen(to)) {
            Location reroll = rerollChorusDestination(to);
            if (reroll != null) { e.setTo(reroll); return; }
            e.setCancelled(true); e.setTo(from);
            sendFrozen(p);
            return;
        }
        if (!BLOCKED_CAUSES.contains(e.getCause()) || !destFrozen(to)) return;
        e.setCancelled(true); e.setTo(from);
    }
    private Location rerollChorusDestination(Location vanillaTo) {
        World w = vanillaTo.getWorld();
        if (w == null) return null;
        for (int i = 0; i < 20; i++) {
            double dx = (RNG.nextDouble() - 0.5) * 16.0;
            double dz = (RNG.nextDouble() - 0.5) * 16.0;
            double x = vanillaTo.getX() + dx;
            double z = vanillaTo.getZ() + dz;
            int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            Location candidate = new Location(w, x, y, z, vanillaTo.getYaw(), vanillaTo.getPitch());
            if (!frozen.isFrozen(w, cx(candidate), cz(candidate))) return candidate;
        }
        return null;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleportWithMount(PlayerTeleportEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled || !frozen.hasAnyFrozenChunks()) return;
        Player p = e.getPlayer();
        if (p.getVehicle() == null || isExempt(p)) return;
        Location to = e.getTo(), from = e.getFrom();
        if (to == null || from == null || !destFrozen(to)) return;
        e.setCancelled(true); e.setTo(from);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLivingMove(EntityMoveEvent e) {
        if (!enabled() || !settings.watchEntitiesEnabled || !frozen.hasAnyFrozenChunks()) return;
        LivingEntity le = e.getEntity();
        if (le instanceof Player || settings.isIgnored(le.getType())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (!entersNewFrozenChunk(le, from, to)) return;
        Location safe = clampAlongPath(le, from, to);
        e.setCancelled(true); e.setTo(safe);
        if (le instanceof Mob mob) mob.setTarget(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent e) {
        if (!enabled() || !frozen.hasAnyFrozenChunks() || !settings.watchEntitiesEnabled) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player || settings.isIgnored(ent.getType())) return;
        Location to = e.getTo();
        if (to == null) return;
        Location from = e.getFrom();
        if (entersNewFrozenChunk(ent, from, to)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!enabled() || !settings.watchEntitiesEnabled || !frozen.hasAnyFrozenChunks()) return;
        Vehicle v = e.getVehicle();
        Location to = e.getTo(), from = e.getFrom();
        if (v == null || to == null || from == null) return;
        if (!entersNewFrozenChunk(v, from, to)) return;
        boolean hasPlayerPassenger = false, allPlayersExempt = true;
        for (Entity pass : v.getPassengers()) {
            if (!(pass instanceof Player player)) continue;
            hasPlayerPassenger = true;
            if (!isExempt(player)) allPlayersExempt = false;
        }
        if (hasPlayerPassenger && allPlayersExempt) return;
        Location safe = clampAlongPath(v, from, to);
        v.teleportAsync(safe);
        for (Entity pass : v.getPassengers()) {
            if (pass instanceof Player player && !isExempt(player)) {
                notifyBlocked(player);
            }
        }
        v.getScheduler().execute(plugin, () -> {
            if (!v.isValid() || v.isDead()) return;
            v.setVelocity(new Vector());
            if (v instanceof Mob mob) { mob.setTarget(null); mob.setAware(false); }
        }, null, 1L);
    }
    private Location clampAlongPath(Entity ent, Location from, Location to) {
        World w = to.getWorld();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (w == null || (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9)) return from.clone();
        double half = Math.max(ent.getWidth() / 2.0, 0.05);
        double height = Math.max(ent.getHeight(), 0.1);
        double baseY = to.getY();
        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 10; i++) {
            double mid = (lo + hi) * 0.5;
            double mx = from.getX() + dx * mid;
            double mz = from.getZ() + dz * mid;
            BoundingBox midBox = new BoundingBox(mx - half, baseY, mz - half, mx + half, baseY + height, mz + half);
            if (boxTouchesFrozen(midBox, w)) hi = mid; else lo = mid;
        }
        Location safe = to.clone();
        safe.setX(from.getX() + dx * lo);
        safe.setZ(from.getZ() + dz * lo);
        return safe;
    }
    private boolean boxTouchesFrozen(BoundingBox box, World w) {
        int minCx = (int) Math.floor(box.getMinX()) >> 4;
        int maxCx = (int) Math.floor(box.getMaxX()) >> 4;
        int minCz = (int) Math.floor(box.getMinZ()) >> 4;
        int maxCz = (int) Math.floor(box.getMaxZ()) >> 4;
        for (int ix = minCx; ix <= maxCx; ix++)
            for (int iz = minCz; iz <= maxCz; iz++)
                if (frozen.isFrozen(w, ix, iz)) return true;
        return false;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawnItemUse(PlayerInteractEvent e) {
        if (!enabled() || !frozen.hasAnyFrozenChunks() || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() == Material.AIR || !isEntitySpawningItem(item)) return;
        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        Location ref = clicked != null ? clicked.getLocation() : p.getLocation();
        World w = ref.getWorld();
        if (w == null || !frozen.isFrozen(w, cx(ref), cz(ref))) return;
        e.setCancelled(true);
        p.getScheduler().execute(plugin, p::updateInventory, null, 1L);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) {
        if (!enabled() || !settings.watchLiquids || !frozen.hasAnyFrozenChunks()) return;
        Block from = e.getBlock(), to = e.getToBlock();
        Material type = from.getType();
        if (type != Material.WATER && type != Material.LAVA) return;
        int fcx = from.getX() >> 4, fcz = from.getZ() >> 4;
        int tcx = to.getX() >> 4, tcz = to.getZ() >> 4;
        if (fcx == tcx && fcz == tcz) return;
        World w = from.getWorld();
        if (w != null && frozen.isFrozen(w, tcx, tcz)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!enabled() || !settings.watchItemsEnabled || !settings.returnPlayerDroppedItem || !frozen.hasAnyFrozenChunks()) return;
        startWatching(e.getEntity());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent e) {
        if (!enabled() || !settings.watchItemsEnabled || !frozen.hasAnyFrozenChunks()) return;
        Player p = e.getPlayer();
        Location pl = p.getLocation();
        if (frozen.isFrozen(pl.getWorld(), cx(pl), cz(pl))) {
            if (!settings.returnPlayerDroppedItem) return;
            e.setCancelled(true);
            p.getScheduler().execute(plugin, p::updateInventory, null, 1L);
            return;
        }
        if (!settings.returnPlayerDroppedItem) return;
        Item dropped = e.getItemDrop();
        if (dropped == null) return;
        dropped.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!needsPlayerTask()) return;
        startParticleTask(e.getPlayer());
    }
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!needsPlayerTask()) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        ScheduledTask old = particleTasks.remove(id);
        if (old != null) try { old.cancel(); } catch (Throwable ignored) {}
        playerInFrozenCache.remove(id);
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> startParticleTask(p), 2L);
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        ScheduledTask t = particleTasks.remove(id);
        if (t != null) try { t.cancel(); } catch (Throwable ignored) {}
        playerInFrozenCache.remove(id);
    }
    private void startParticleTask(Player p) {
        startParticleTask(p, 0);
    }
    private void startParticleTask(Player p, int attempt) {
        if (!needsPlayerTask() || !p.isOnline()) return;
        UUID id = p.getUniqueId();
        if (particleTasks.containsKey(id)) return;
        ScheduledTask t = p.getScheduler().runAtFixedRate(plugin, st -> tickParticles(p, st), () -> particleTasks.remove(id), 5L, 6L);
        if (t != null) { particleTasks.put(id, t); return; }
        if (attempt < 10) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> startParticleTask(p, attempt + 1), 4L);
        }
    }
    private void tickParticles(Player p, ScheduledTask st) {
        if (!p.isOnline() || !p.isValid()) { st.cancel(); return; }
        if (!settings.watchPlayersEnabled) return;
        if (!frozen.hasAnyFrozenChunks()) {
            playerInFrozenCache.put(p.getUniqueId(), false);
            return;
        }
        Location loc = p.getLocation();
        World w = loc.getWorld();
        boolean nowInFrozen = w != null && frozen.isFrozen(w, cx(loc), cz(loc));
        boolean wasInFrozen = Boolean.TRUE.equals(playerInFrozenCache.put(p.getUniqueId(), nowInFrozen));
        if (nowInFrozen && !isExempt(p) && !wasInFrozen) {
            Location safe = findNearestUnfrozen(loc);
            if (safe != null) {
                double awayX = safe.getX() - loc.getX(), awayZ = safe.getZ() - loc.getZ();
                if (awayX * awayX + awayZ * awayZ > 1e-6) {
                    safe.setDirection(new Vector(awayX, 0, awayZ));
                    safe.setPitch(loc.getPitch());
                } else {
                    safe.setDirection(loc.getDirection());
                }
                p.teleportAsync(safe);
            }
            sendFrozen(p);
            return;
        }
        if (settings.notifyWhenInFrozen && !isExempt(p) && nowInFrozen) {
            p.sendActionBar(FROZEN_ACTIONBAR);
        }
        if (settings.particlesOnEntry > 0)
            spawnBorderParticles(p);
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
                // East face: outer border always visible; a shared internal border (east neighbor
                // also frozen) only shows to a player standing inside this chunk or that neighbor.
                boolean eastInsider = (pcx == fx && pcz == fz) || (pcx == fx + 1 && pcz == fz);
                if ((!eastFrozen || eastInsider) && Math.abs(px - (x0 + 16.0)) <= d && !(pz < z0 && northFrozen) && !(pz > z0 + 16 && southFrozen))
                    drawWallFixedX(p, x0 + 16.0, z0, z0 + 16.0, pz, py, d);
                // West face: the west neighbor (if also frozen) already owns this line via its
                // own east face, so only draw here for a genuine outer border.
                if (!westFrozen && Math.abs(px - x0) <= d && !(pz < z0 && northFrozen) && !(pz > z0 + 16 && southFrozen))
                    drawWallFixedX(p, x0, z0, z0 + 16.0, pz, py, d);
                // South face: same insider rule as East.
                boolean southInsider = (pcx == fx && pcz == fz) || (pcx == fx && pcz == fz + 1);
                if ((!southFrozen || southInsider) && Math.abs(pz - (z0 + 16.0)) <= d)
                    drawWallFixedZ(p, z0 + 16.0, x0, x0 + 16.0, px, py, d);
                // North face: owned by the north neighbor's south face when both are frozen.
                if (!northFrozen && Math.abs(pz - z0) <= d)
                    drawWallFixedZ(p, z0, x0, x0 + 16.0, px, py, d);
            }
        }
    }
    private void drawWallFixedX(Player p, double bx, double zMin, double zMax, double pz, double py, double d) {
        double zFrom = Math.max(zMin, pz - d), zTo = Math.min(zMax, pz + d);
        for (double z = zFrom; z <= zTo; z += 0.5)
            for (double y = py - 1.0; y <= py + 3.0; y += 0.5)
                p.spawnParticle(Particle.DUST, bx, y, z, 1, BORDER_DUST);
    }
    private void drawWallFixedZ(Player p, double bz, double xMin, double xMax, double px, double py, double d) {
        double xFrom = Math.max(xMin, px - d), xTo = Math.min(xMax, px + d);
        for (double x = xFrom; x <= xTo; x += 0.5)
            for (double y = py - 1.0; y <= py + 3.0; y += 0.5)
                p.spawnParticle(Particle.DUST, x, y, bz, 1, BORDER_DUST);
    }
    private void startWatching(Entity entity) {
        if (!(entity instanceof Item) || !entity.isValid() || entity.isDead()) return;
        UUID id = entity.getUniqueId();
        if (watch.containsKey(id)) return;
        WatchState st = new WatchState();
        st.lastSafe     = entity.getLocation().clone();
        st.lastVelocity = entity.getVelocity().clone();
        watch.put(id, st);
        ScheduledTask task = entity.getScheduler().runAtFixedRate(plugin, scheduled -> tickWatch((Item) entity, scheduled), () -> watch.remove(id), 3L, 3L);
        if (task == null) watch.remove(id); else st.task = task;
    }
    private void tickWatch(Item item, ScheduledTask task) {
        if (!item.isValid() || item.isDead()) {
            task.cancel(); watch.remove(item.getUniqueId()); return;
        }
        WatchState st = watch.get(item.getUniqueId());
        if (st == null) { task.cancel(); return; }
        Location now = item.getLocation();
        World w = now.getWorld();
        if (w == null) return;
        if (!frozen.isFrozen(w, cx(now), cz(now))) {
            st.lastSafe = now.clone(); st.lastVelocity = item.getVelocity().clone(); return;
        }
        if (!settings.returnPlayerDroppedItem) { task.cancel(); watch.remove(item.getUniqueId()); return; }
        if (item.getPickupDelay() == Integer.MAX_VALUE) { task.cancel(); watch.remove(item.getUniqueId()); return; }

        String ownerStr = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        Player owner = null;
        if (ownerStr != null) {
            try {
                Player candidate = Bukkit.getPlayer(UUID.fromString(ownerStr));
                if (candidate != null && candidate.isOnline()) owner = candidate;
            } catch (IllegalArgumentException ignored) {}
        }
        Location exit = st.lastSafe;
        boolean hasValidExit = owner != null || (exit != null && exit.getWorld() != null && !frozen.isFrozen(exit.getWorld(), cx(exit), cz(exit)));
        if (!hasValidExit) return; // no safe destination yet; keep watching, retry next tick

        item.setPickupDelay(Integer.MAX_VALUE);
        ItemStack stack = item.getItemStack().clone();
        UUID id = item.getUniqueId();
        item.remove(); task.cancel(); watch.remove(id);

        if (owner != null) {
            final Player finalOwner = owner;
            finalOwner.getScheduler().execute(plugin, () -> {
                finalOwner.getInventory().addItem(stack).values().forEach(rem -> finalOwner.getWorld().dropItemNaturally(finalOwner.getLocation(), rem));
                finalOwner.updateInventory();
            }, null, 1L);
            return;
        }

        final World ew = exit.getWorld();
        final Location exitFinal = exit.clone();
        Bukkit.getRegionScheduler().execute(plugin, ew, cx(exit), cz(exit), () -> ew.dropItemNaturally(exitFinal, stack));
    }
    private Location findNearestUnfrozen(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        int fcx = cx(loc), fcz = cz(loc);
        double rx = loc.getX() - (fcx << 4); // 0..16
        double rz = loc.getZ() - (fcz << 4);
        // distances to each face from player position inside frozen chunk
        double[] dist = {rx, 16 - rx, rz, 16 - rz};
        int[] ndx  = {-1, 1, 0,  0};
        int[] ndz  = { 0, 0, -1, 1};
        double[] offA = {15.4, 0.6, 0, 0}; // x-offset in target chunk
        double[] offB = {0, 0, 15.4, 0.6}; // z-offset in target chunk
        Integer[] order = {0, 1, 2, 3};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));
        for (int i : order) {
            int ncx = fcx + ndx[i], ncz = fcz + ndz[i];
            if (!frozen.isFrozen(w, ncx, ncz)) {
                double bx, bz;
                if (ndx[i] != 0) {
                    bx = (ncx << 4) + offA[i];
                    bz = Math.max((ncz << 4) + 0.4, Math.min((ncz << 4) + 15.6, loc.getZ()));
                } else {
                    bz = (ncz << 4) + offB[i];
                    bx = Math.max((ncx << 4) + 0.4, Math.min((ncx << 4) + 15.6, loc.getX()));
                }
                return new Location(w, bx, loc.getY(), bz);
            }
        }
        return null;
    }
    private boolean isEntitySpawningItem(ItemStack stack) {
        Material m = stack.getType();
        if (Tag.ITEMS_BOATS.isTagged(m) || Tag.ITEMS_CHEST_BOATS.isTagged(m)) return true;
        if (m == Material.BAMBOO_RAFT || m == Material.BAMBOO_CHEST_RAFT) return true;
        if (m == Material.MINECART || m == Material.CHEST_MINECART || m == Material.FURNACE_MINECART || m == Material.HOPPER_MINECART || m == Material.TNT_MINECART || m == Material.COMMAND_BLOCK_MINECART) return true;
        if (PLACE_ENTITY_ITEMS.contains(m) || ENTITY_BUCKETS.contains(m)) return true;
        return stack.getItemMeta() instanceof SpawnEggMeta;
    }
    private static final class WatchState {
        volatile Location      lastSafe;
        volatile Vector        lastVelocity;
        volatile ScheduledTask task;
    }
}
