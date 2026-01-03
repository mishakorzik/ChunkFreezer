package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BarrierListener implements Listener {

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager frozen;
    private final NamespacedKey ownerKey;

    private final Map<UUID, WatchState> watch = new ConcurrentHashMap<>();
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

    public BarrierListener(Plugin plugin, Settings settings, FrozenChunkManager frozen) {
        this.plugin = plugin;
        this.settings = settings;
        this.frozen = frozen;
        this.ownerKey = new NamespacedKey(plugin, "dropOwner"); }

    private boolean enabled() { return settings.barrierEnabled && settings.freezeType != 0; }
    private static int chunkX(Location l) { return l.getBlockX() >> 4; }
    private static int chunkZ(Location l) { return l.getBlockZ() >> 4; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLivingMove(EntityMoveEvent e) {
        if (!enabled()) return;

        LivingEntity le = e.getEntity();
        if (le instanceof Player) return;
        if (settings.isIgnored(le.getType())) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        World w = to.getWorld();
        if (w == null) return;

        int fcx = chunkX(from), fcz = chunkZ(from);
        int tcx = chunkX(to), tcz = chunkZ(to);

        if (fcx == tcx && fcz == tcz) return;
        if (frozen.isFrozen(w, tcx, tcz)) {
            e.setCancelled(true);
            e.setTo(from);
            if (le instanceof Mob mob) mob.setTarget(null);
            if (settings.freezeType == 2) {
                final Location back = from.clone();
                final Vector vel = le.getVelocity();
                le.getScheduler().execute(plugin, () -> {
                    if (!le.isValid() || le.isDead()) return;
                    le.teleportAsync(back);
                    Vector push = vel.multiply(-settings.bounceMultiplier);
                    if (push.lengthSquared() > 1.0E-6) le.setVelocity(push);
                    else le.setVelocity(new Vector(0, 0, 0));
                    if (le instanceof Mob m) {
                        m.setTarget(null);
                        m.setAware(false);
                    }
                }, null, 1L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent e) {
        if (!enabled()) return;

        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        if (settings.isIgnored(ent.getType())) return;

        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;
        if (frozen.isFrozen(to.getWorld(), chunkX(to), chunkZ(to))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawnItemUse(PlayerInteractEvent e) {
        if (!enabled()) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = e.getItem();
        if (item == null || item.getType() == Material.AIR) return;
        if (!isEntitySpawningItem(item)) return;

        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();

        Location ref = (clicked != null) ? clicked.getLocation() : p.getLocation();
        World w = ref.getWorld();
        if (w == null) return;
        if (!frozen.isFrozen(w, chunkX(ref), chunkZ(ref))) return;

        e.setCancelled(true);
        p.getScheduler().execute(plugin, p::updateInventory, null, 1L);
    }

    private boolean isEntitySpawningItem(ItemStack stack) {
        Material m = stack.getType();
        if (Tag.ITEMS_BOATS.isTagged(m) || Tag.ITEMS_CHEST_BOATS.isTagged(m)) return true;
        if (m == Material.BAMBOO_RAFT || m == Material.BAMBOO_CHEST_RAFT) return true;
        if (isMinecartItem(m)) return true;
        if (PLACE_ENTITY_ITEMS.contains(m)) return true;
        if (ENTITY_BUCKETS.contains(m)) return true;
        ItemMeta meta = stack.getItemMeta();
        return meta instanceof SpawnEggMeta;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!enabled()) return;
        if (!settings.watchItems) return;
        if (!frozen.hasAnyFrozenChunks()) return;
        startWatching(e.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent e) {
        if (!enabled()) return;
        if (!settings.returnPlayerDroppedItems) return;

        Player p = e.getPlayer();

        Location pl = p.getLocation();
        if (frozen.isFrozen(pl.getWorld(), chunkX(pl), chunkZ(pl))) {
            e.setCancelled(true);
            p.getScheduler().execute(plugin, p::updateInventory, null, 1L);
            return;
        }

        Item dropped = e.getItemDrop();
        if (dropped != null) {
            dropped.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
            if (settings.watchItems && frozen.hasAnyFrozenChunks()) startWatching(dropped);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!enabled()) return;
        if (!settings.watchVehicles) return;

        Vehicle v = e.getVehicle();
        Location to = e.getTo();
        Location from = e.getFrom();
        if (v == null || to == null || from == null) return;

        World w = to.getWorld();
        if (w == null) return;
        if (!frozen.isFrozen(w, chunkX(to), chunkZ(to))) return;

        final Location back = from.clone();
        final Vector oldVel = v.getVelocity();

        v.teleportAsync(back);
        v.getScheduler().execute(plugin, () -> {
            if (!v.isValid() || v.isDead()) return;
            Vector push = oldVel.multiply(-settings.bounceMultiplier);
            if (push.lengthSquared() > 1.0E-6) v.setVelocity(push);
            else v.setVelocity(new Vector(0, 0, 0));
        }, null, 1L);
    }

    private void startWatching(Entity entity) {
        if (!enabled()) return;
        if (!(entity instanceof Item)) return;
        if (!entity.isValid() || entity.isDead()) return;

        UUID id = entity.getUniqueId();
        if (watch.containsKey(id)) return;

        WatchState st = new WatchState();
        st.lastSafe = entity.getLocation().clone();
        st.lastVelocity = entity.getVelocity();
        watch.put(id, st);

        ScheduledTask task = entity.getScheduler().runAtFixedRate(
                plugin,
                scheduled -> tickWatch((Item) entity, scheduled),
                () -> watch.remove(id),
                1L,
                1L
        );

        if (task == null) watch.remove(id);
        else st.task = task;
    }

    private void tickWatch(Item item, ScheduledTask task) {
        if (item == null || !item.isValid() || item.isDead()) {
            task.cancel();
            watch.remove(item.getUniqueId());
            return;
        }

        Location now = item.getLocation();
        World w = now.getWorld();
        if (w == null) return;

        int cx = chunkX(now);
        int cz = chunkZ(now);

        boolean inFrozen = frozen.isFrozen(w, cx, cz);

        WatchState st = watch.get(item.getUniqueId());
        if (st == null) {
            st = new WatchState();
            st.lastSafe = now.clone();
            st.lastVelocity = item.getVelocity();
            watch.put(item.getUniqueId(), st);
        }

        if (!inFrozen) {
            st.lastSafe = now.clone();
            st.lastVelocity = item.getVelocity();
            return;
        }

        UUID owner = readOwner(item);
        if (owner != null) {
            Player p = Bukkit.getPlayer(owner);
            if (p != null && p.isOnline()) {
                ItemStack stack = item.getItemStack().clone();
                p.getScheduler().execute(plugin, () -> {
                    p.getInventory().addItem(stack).values()
                            .forEach(rem -> p.getWorld().dropItemNaturally(p.getLocation(), rem));
                    p.updateInventory();
                }, null, 1L);

                item.remove();
                task.cancel();
                watch.remove(item.getUniqueId());
                return;
            }
        }

        Location back = (st.lastSafe != null) ? st.lastSafe.clone() : now.clone();
        Vector vel = item.getVelocity();
        if (vel.lengthSquared() < 1.0E-8 && st.lastVelocity != null) vel = st.lastVelocity;

        final Vector bounced = vel.multiply(-settings.bounceMultiplier);
        item.teleportAsync(back);

        item.getScheduler().execute(plugin, () -> {
            if (!item.isValid() || item.isDead()) return;
            if (bounced.lengthSquared() > 1.0E-6) item.setVelocity(bounced);
            else item.setVelocity(new Vector(0, 0, 0));
        }, null, 1L);
    }

    private UUID readOwner(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        String s = pdc.get(ownerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    private static boolean isMinecartItem(Material m) {
        return m == Material.MINECART
                || m == Material.CHEST_MINECART
                || m == Material.FURNACE_MINECART
                || m == Material.HOPPER_MINECART
                || m == Material.TNT_MINECART
                || m == Material.COMMAND_BLOCK_MINECART;
    }

    private static final class WatchState {
        volatile Location lastSafe;
        volatile Vector lastVelocity;
        volatile ScheduledTask task;
    }
}
