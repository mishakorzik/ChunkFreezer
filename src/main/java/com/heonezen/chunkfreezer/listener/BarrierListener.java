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
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BarrierListener implements Listener {

    private final Plugin             plugin;
    private final Settings           settings;
    private final FrozenChunkManager frozen;
    private final NamespacedKey      ownerKey;
    private final Map<UUID, WatchState>    watch         = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> particleTasks = new ConcurrentHashMap<>();

    private static final Particle.DustOptions BORDER_DUST = new Particle.DustOptions(Color.fromRGB(220, 30, 30), 0.85f);

    private static final Component FROZEN_ACTIONBAR = Component.text("This chunk is frozen!", NamedTextColor.RED);

    private static final Set<Material> PLACE_ENTITY_ITEMS = EnumSet.of(
            Material.ARMOR_STAND, Material.END_CRYSTAL,
            Material.ITEM_FRAME, Material.GLOW_ITEM_FRAME,
            Material.PAINTING, Material.LEAD);

    private static final Set<Material> ENTITY_BUCKETS = EnumSet.of(
            Material.COD_BUCKET, Material.SALMON_BUCKET, Material.TROPICAL_FISH_BUCKET,
            Material.PUFFERFISH_BUCKET, Material.AXOLOTL_BUCKET, Material.TADPOLE_BUCKET);

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
    }

    private boolean enabled()                 { return settings.barrierEnabled; }
    private static int cx(Location l)         { return l.getBlockX() >> 4; }
    private static int cz(Location l)         { return l.getBlockZ() >> 4; }
    private static boolean isExempt(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }
    private boolean destFrozen(Location to) {
        World w = to.getWorld();
        return w != null && frozen.isFrozen(w, cx(to), cz(to));
    }
    private boolean needsPlayerTask() {
        return enabled() && settings.watchPlayersEnabled
                && (settings.particlesOnEntry > 0 || settings.notifyWhenInFrozen);
    }
    private void sendFrozen(Player p) {
        p.getScheduler().execute(plugin,
                () -> p.sendMessage(Component.text("This chunk is frozen!", NamedTextColor.RED)), null, 1L);
    }
    private void damagePlayer(Player p) {
        if (!settings.watchPlayersEnabled || settings.damageOnEntry <= 0) return;
        p.getScheduler().execute(plugin, () -> p.damage(settings.damageOnEntry), null, 1L);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHitInFrozen(ProjectileHitEvent e) {
        if (!enabled() || !settings.watchProjectilesEnabled) return;
        Entity ent = e.getEntity();
        if (ent instanceof EnderPearl) return;
        Location loc = ent.getLocation();
        World w = loc.getWorld();
        if (w == null || !frozen.isFrozen(w, cx(loc), cz(loc))) return;
        if (e.getHitEntity() != null) return;
        if (ent instanceof Trident trident && trident.getShooter() instanceof Player shooter) {
            e.setCancelled(true);
            @SuppressWarnings("deprecation") ItemStack item = ((AbstractArrow) trident).getItem().clone();
            trident.remove();
            shooter.getScheduler().execute(plugin, () -> {
                shooter.getInventory().addItem(item).values()
                        .forEach(rem -> shooter.getWorld().dropItemNaturally(shooter.getLocation(), rem));
                shooter.updateInventory();
            }, null, 1L);
            return;
        }
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
        if (!enabled() || !settings.watchPlayersEnabled) return;
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;
        if (isExempt(p)) return;
        Location loc = pearl.getLocation();
        World w = loc.getWorld();
        if (w == null || !frozen.isFrozen(w, cx(loc), cz(loc))) return;
        e.setCancelled(true);
        pearl.remove();
        p.getScheduler().execute(plugin, () -> {
            p.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 1))
                    .values().forEach(rem -> p.getWorld().dropItemNaturally(p.getLocation(), rem));
            p.updateInventory();
            sendFrozen(p);
        }, null, 1L);
        damagePlayer(p);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled) return;
        Player p = e.getPlayer();
        if (isExempt(p) || p.getVehicle() != null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || (cx(from) == cx(to) && cz(from) == cz(to))) return;
        if (!destFrozen(to)) return;
        e.setCancelled(true); e.setTo(from);
        sendFrozen(p);
        damagePlayer(p);
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled) return;
        Player p = e.getPlayer();
        if (isExempt(p)) return;
        Location to = e.getTo(), from = e.getFrom();
        if (to == null || from == null) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.EXIT_BED && destFrozen(to)) {
            Location safe = findNearestUnfrozen(to);
            if (safe != null) { e.setTo(safe); return; }
            e.setCancelled(true); e.setTo(from); return;
        }
        if (!BLOCKED_CAUSES.contains(e.getCause()) || !destFrozen(to)) return;
        e.setCancelled(true); e.setTo(from);
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) sendFrozen(p);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMoveWithMount(PlayerMoveEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled) return;
        Player p = e.getPlayer();
        Entity mount = p.getVehicle();
        if (mount == null || isExempt(p)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || (cx(from) == cx(to) && cz(from) == cz(to))) return;
        if (!destFrozen(to)) return;
        e.setCancelled(true); e.setTo(from);
        final Location back = from.clone(); final Vector oldVel = mount.getVelocity();
        mount.teleportAsync(back);
        mount.getScheduler().execute(plugin, () -> {
            if (!mount.isValid() || mount.isDead()) return;
            Vector push = oldVel.multiply(-settings.playerBounceMultiplier);
            mount.setVelocity(push.lengthSquared() > 1e-6 ? push : new Vector());
            if (mount instanceof Mob mob) { mob.setTarget(null); mob.setAware(false); }
        }, null, 1L);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleportWithMount(PlayerTeleportEvent e) {
        if (!enabled() || !settings.watchPlayersEnabled) return;
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
        if (to == null || (cx(from) == cx(to) && cz(from) == cz(to))) return;
        if (!destFrozen(to)) return;
        e.setCancelled(true); e.setTo(from);
        if (le instanceof Mob mob) mob.setTarget(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent e) {
        if (!enabled() || !frozen.hasAnyFrozenChunks() || !settings.watchEntitiesEnabled) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player || settings.isIgnored(ent.getType())) return;
        Location to = e.getTo();
        if (to != null && destFrozen(to)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!enabled() || !settings.watchEntitiesEnabled) return;
        Vehicle v = e.getVehicle();
        Location to = e.getTo(), from = e.getFrom();
        if (v == null || to == null || from == null || !destFrozen(to)) return;
        final Location back = from.clone(); final Vector oldVel = v.getVelocity();
        v.teleportAsync(back);
        v.getScheduler().execute(plugin, () -> {
            if (!v.isValid() || v.isDead()) return;
            Vector push = oldVel.multiply(-settings.entityBounceMultiplier);
            v.setVelocity(push.lengthSquared() > 1e-6 ? push : new Vector());
        }, null, 1L);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawnItemUse(PlayerInteractEvent e) {
        if (!enabled() || e.getHand() != EquipmentSlot.HAND) return;
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
        if (!enabled() || !settings.watchItemsEnabled || !settings.returnPlayerDroppedItem) return;
        if (!frozen.hasAnyFrozenChunks()) return;
        startWatching(e.getEntity());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent e) {
        if (!enabled() || !settings.watchItemsEnabled) return;
        Player p = e.getPlayer();
        Location pl = p.getLocation();
        if (frozen.isFrozen(pl.getWorld(), cx(pl), cz(pl))) {
            if (!settings.returnPlayerDroppedItem) return;
            e.setCancelled(true);
            p.getScheduler().execute(plugin, p::updateInventory, null, 1L);
            return;
        }
        if (!settings.returnPlayerDroppedItem || !frozen.hasAnyFrozenChunks()) return;
        Item dropped = e.getItemDrop();
        if (dropped == null) return;
        dropped.getPersistentDataContainer()
                .set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!needsPlayerTask()) return;
        startParticleTask(e.getPlayer());
    }
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!needsPlayerTask()) return;
        UUID id = e.getPlayer().getUniqueId();
        ScheduledTask old = particleTasks.remove(id);
        if (old != null) try { old.cancel(); } catch (Throwable ignored) {}
        e.getPlayer().getScheduler().execute(plugin, () -> startParticleTask(e.getPlayer()), null, 5L);
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        ScheduledTask t = particleTasks.remove(id);
        if (t != null) try { t.cancel(); } catch (Throwable ignored) {}
    }
    private void startParticleTask(Player p) {
        if (!needsPlayerTask()) return;
        UUID id = p.getUniqueId();
        if (particleTasks.containsKey(id)) return;
        ScheduledTask t = p.getScheduler().runAtFixedRate(plugin, st -> tickParticles(p, st), () -> particleTasks.remove(id), 5L, 6L);
        if (t != null) particleTasks.put(id, t);
    }
    private void tickParticles(Player p, ScheduledTask st) {
        if (!p.isOnline() || !p.isValid()) { st.cancel(); return; }
        if (!settings.watchPlayersEnabled) return;
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (settings.notifyWhenInFrozen && !isExempt(p)
                && w != null && frozen.isFrozen(w, cx(loc), cz(loc))) {
            p.sendActionBar(FROZEN_ACTIONBAR);
        }
        if (settings.particlesOnEntry > 0 && frozen.hasAnyFrozenChunks())
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
                if (!frozen.isFrozen(w, fx - 1, fz) && Math.abs(px - x0) <= d && !(pz < z0 && frozen.isFrozen(w, fx, fz - 1)) && !(pz > z0 + 16 && frozen.isFrozen(w, fx, fz + 1)))
                    drawWallFixedX(p, x0, z0, z0 + 16.0, pz, py, d);
                if (!frozen.isFrozen(w, fx + 1, fz) && Math.abs(px - (x0 + 16.0)) <= d && !(pz < z0 && frozen.isFrozen(w, fx, fz - 1)) && !(pz > z0 + 16 && frozen.isFrozen(w, fx, fz + 1)))
                    drawWallFixedX(p, x0 + 16.0, z0, z0 + 16.0, pz, py, d);
                if (!frozen.isFrozen(w, fx, fz - 1) && Math.abs(pz - z0) <= d && !(px < x0 && frozen.isFrozen(w, fx - 1, fz)) && !(px > x0 + 16 && frozen.isFrozen(w, fx + 1, fz)))
                    drawWallFixedZ(p, z0, x0, x0 + 16.0, px, py, d);
                if (!frozen.isFrozen(w, fx, fz + 1) && Math.abs(pz - (z0 + 16.0)) <= d && !(px < x0 && frozen.isFrozen(w, fx - 1, fz)) && !(px > x0 + 16 && frozen.isFrozen(w, fx + 1, fz)))
                    drawWallFixedZ(p, z0 + 16.0, x0, x0 + 16.0, px, py, d);
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
        item.setPickupDelay(Integer.MAX_VALUE);
        ItemStack stack = item.getItemStack().clone();
        UUID id = item.getUniqueId();
        String ownerStr = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        item.remove(); task.cancel(); watch.remove(id);
        if (ownerStr != null) {
            try {
                Player owner = Bukkit.getPlayer(UUID.fromString(ownerStr));
                if (owner != null && owner.isOnline()) {
                    owner.getScheduler().execute(plugin, () -> {
                        owner.getInventory().addItem(stack).values().forEach(rem -> owner.getWorld().dropItemNaturally(owner.getLocation(), rem));
                        owner.updateInventory();
                    }, null, 1L);
                    return;
                }
            } catch (IllegalArgumentException ignored) {}
        }
        Location exit = st.lastSafe;
        if (exit != null && exit.getWorld() != null && !frozen.isFrozen(exit.getWorld(), cx(exit), cz(exit))) {
            final World ew = exit.getWorld();
            final Location exitFinal = exit.clone();
            Bukkit.getRegionScheduler().execute(plugin, ew, cx(exit), cz(exit), () -> ew.dropItemNaturally(exitFinal, stack));
        }
    }
    private Location findNearestUnfrozen(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        int[] dxs = {0, 0, 1, -1};
        int[] dzs = {1, -1, 0, 0};
        for (int i = 0; i < 4; i++) {
            int ncx = cx(loc) + dxs[i], ncz = cz(loc) + dzs[i];
            if (!frozen.isFrozen(w, ncx, ncz)) {
                double bx, bz;
                if (dxs[i] != 0) {
                    bx = (ncx << 4) + (dxs[i] == 1 ? 1.5 : 14.5);
                    bz = Math.max((ncz << 4) + 0.5, Math.min((ncz << 4) + 15.5, loc.getZ()));
                } else {
                    bz = (ncz << 4) + (dzs[i] == 1 ? 1.5 : 14.5);
                    bx = Math.max((ncx << 4) + 0.5, Math.min((ncx << 4) + 15.5, loc.getX()));
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
