package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Lang;
import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import com.heonezen.chunkfreezer.util.EntityTypeResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.*;

public final class ProtectionListener implements Listener {

    private static final int   BFS_LIMIT = 256;
    private static final int[] DIR_X     = {1, -1, 0, 0};
    private static final int[] DIR_Z     = {0, 0, 1, -1};

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;
    private final Lang lang;

    public ProtectionListener(Plugin plugin, Settings settings, FrozenChunkManager manager, Lang lang) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.lang = lang;
    }
    private boolean frozen(Location l) {
        return l != null && l.getWorld() != null && manager.isFrozen(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }
    private boolean frozen(Block b) {
        return b != null && frozen(b.getLocation());
    }
    private boolean itemRedirect() {
        return settings.barrierEnabled && settings.watchItemsEnabled && settings.returnPlayerDroppedItem;
    }
    private static long packChunk(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    private static DropPoint borderPt(Location o, int fx, int fz, int tx, int tz) {
        int minX = tx << 4, minZ = tz << 4;
        double x, z;
        if (tx == fx + 1) {
            x = minX + 1.0;
            z = clamp(o.getZ(), minZ + 1.0, minZ + 15.0);
        }
        else if (tx == fx - 1) {
            x = minX + 15.0;
            z = clamp(o.getZ(), minZ + 1.0, minZ + 15.0);
        }
        else if (tz == fz + 1) {
            z = minZ + 1.0;
            x = clamp(o.getX(), minX + 1.0, minX + 15.0);
        }
        else {
            z = minZ + 15.0;
            x = clamp(o.getX(), minX + 1.0, minX + 15.0); 
        }
        return new DropPoint(x, z);
    }
    private static double safeY(World w, int bx, int bz, double startY) {
        int y = Math.max(w.getMinHeight() + 1, Math.min(w.getMaxHeight() - 1, (int) startY));
        for (int d = 0; d <= 16; d++) {
            int ty = y + d;
            if (ty <= w.getMaxHeight() - 1 && !w.getBlockAt(bx, ty, bz).getType().isSolid()) return ty + 0.05;
        }
        for (int d = 1; d <= 16; d++) {
            int ty = y - d;
            if (ty >= w.getMinHeight() + 1 && !w.getBlockAt(bx, ty, bz).getType().isSolid()) return ty + 0.05;
        }
        return y + 0.05;
    }
    private Exit findExit(World w, int sx, int sz, Location origin) {
        if (w == null || !manager.isFrozen(w, sx, sz)) return null;
        ArrayDeque<Node> q = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        q.add(new Node(sx, sz, 0)); visited.add(packChunk(sx, sz));
        Exit best = null; int bestDist = Integer.MAX_VALUE; double bestSq = Double.MAX_VALUE;
        int iters = 0;
        while (!q.isEmpty() && iters++ < BFS_LIMIT) {
            Node cur = q.poll();
            if (cur.dist() > bestDist) break;
            for (int i = 0; i < 4; i++) {
                int nx = cur.x() + DIR_X[i], nz = cur.z() + DIR_Z[i];
                if (!manager.isFrozen(w, nx, nz)) {
                    int d = cur.dist() + 1;
                    if (d > bestDist) continue;
                    DropPoint p = borderPt(origin, cur.x(), cur.z(), nx, nz);
                    double sq = Math.pow(p.x() - origin.getX(), 2) + Math.pow(p.z() - origin.getZ(), 2);
                    if (d < bestDist || sq < bestSq) { bestDist = d; bestSq = sq; best = new Exit(cur.x(), cur.z(), nx, nz); }
                } else if (cur.dist() + 1 < bestDist && visited.add(packChunk(nx, nz))) {
                    q.add(new Node(nx, nz, cur.dist() + 1));
                }
            }
        }
        return best;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity c = e.getRightClicked();
        if (c instanceof Player || manager.isIgnored(c.getType())) return;
        if (frozen(c.getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Location loc = e.getEntity().getLocation();
        if (!frozen(loc)) return;
        World w = loc.getWorld();
        Exit exit = findExit(w, loc.getBlockX() >> 4, loc.getBlockZ() >> 4, loc);
        if (exit == null) return;
        DropPoint pt = borderPt(loc, exit.fx(), exit.fz(), exit.tx(), exit.tz());
        List<ItemStack> drops = new ArrayList<>(e.getDrops());
        /** The XP is moved out together with the items - it used to be zeroed and simply lost. */
        final int exp = Math.max(0, e.getDroppedExp());
        if (drops.isEmpty() && exp == 0) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Bukkit.getRegionScheduler().execute(plugin, w, exit.tx(), exit.tz(), () -> {
            double y = clamp(loc.getY(), w.getMinHeight() + 1, w.getMaxHeight() - 1);
            Location dl = new Location(w, pt.x(), safeY(w, blockOf(pt.x()), blockOf(pt.z()), y), pt.z());
            for (ItemStack is : drops) if (is != null && !is.getType().isAir()) w.dropItem(dl, is);
            if (exp > 0) w.spawn(dl, ExperienceOrb.class, orb -> orb.setExperience(exp));
        });
    }
    private static int blockOf(double coord) { return (int) Math.floor(coord); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        Entity ent = e.getEntity();
        if (ent instanceof Player || ent instanceof Item) return;
        if (manager.isIgnored(ent.getType()) || settings.isFrozenChunkIgnored(ent.getType())) return;
        if (!frozen(e.getLocation())) return;
        /** Firework implements Bukkit's Projectile interface (it can be "shot" from a crossbow),
         *  but it is NOT a projectile for this plugin's purposes: watch-projectiles.enabled is
         *  about arrows/tridents/snowballs/etc. getting stuck in a frozen chunk and cleaned up
         *  later, an entirely different concern from whether a firework can be launched inside a
         *  frozen chunk at all. Without this explicit exclusion, fireworks would silently slip
         *  through the projectile check below (since Firework instanceof Projectile is always
         *  true) regardless of watch-projectiles' actual setting. */
        if (settings.watchProjectilesEnabled && ent instanceof Projectile && !(ent instanceof Firework)) return;
        e.setCancelled(true);
    }
    /** Blocks emptying a bucket into a frozen chunk. This is the second line of defence for
     *  buckets: the primary one is the pre-emptive PlayerInteractEvent block in BarrierListener,
     *  which stops the item before it's used at all. This handler is what catches the cases that
     *  pre-emptive block can't see - and it's also the only one that works on Minecraft versions
     *  where the interact event isn't fired for a bucket use at all.
     *
     *  <p>Worth knowing: since Minecraft 1.21.9 a bucket is used through {@code Item#use} (the
     *  server's "use item in air" path) instead of {@code Item#useOn}, and mob buckets are a
     *  separate {@code MobBucketItem} class whose contents are water - so emptying one fires
     *  PlayerBucketEmptyEvent exactly like a water bucket does, and cancelling that event stops
     *  both the water and the mob (the mob is only released once the bucket was successfully
     *  emptied).
     *
     *  <p>A plain liquid bucket has no entity type, so it is always blocked. A mob bucket resolves
     *  to the entity it releases (COD_BUCKET -> COD) and therefore honours
     *  frozen-chunk.ignore-entity-types exactly like onEntitySpawn does (e.g. "COD" in that list
     *  keeps allowing COD_BUCKET, as documented in config.yml); chunkfreezer.bypass exempts the
     *  movement barrier, not this. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        /** The block the bucket is emptied into. It is null when the event was built with the
         *  deprecated PlayerBucketEvent constructor (which passes no block at all), so it has to
         *  be checked before it is used - the server itself always fills it in. */
        Block target = e.getBlock();
        if (target == null || !frozen(target)) return;
        EntityType resulting = EntityTypeResolver.resolvePlaceable(e.getBucket());
        if (resulting != null && (manager.isIgnored(resulting) || settings.isFrozenChunkIgnored(resulting))) return;
        e.setCancelled(true);
    }
    /** A player may not fall asleep inside a frozen chunk.
     *
     *  <p>Sleeping there is a way to keep an overloaded chunk alive: a sleeping player counts as
     *  "somebody is around", and the plugin only cleans a frozen chunk up (instant-despawn) when
     *  no player is near - so a player could camp a frozen chunk by sleeping in it and keep the
     *  pile of entities that froze the chunk in the first place. It also leaves the player
     *  standing inside the frozen chunk once they get up, which is exactly what the barrier is
     *  there to prevent.
     *
     *  <p>This event is the only hook available for it: the server moves the player into the bed
     *  with a plain position update rather than a teleport, so there is no PlayerTeleportEvent to
     *  intercept - the bed entry itself has to be refused. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        Block bed = e.getBed();
        if (bed == null || !frozen(bed.getLocation())) return;
        Player p = e.getPlayer();
        if (p.hasPermission("chunkfreezer.bypass")) return;
        e.setCancelled(true);
        if (lang != null) p.sendMessage(lang.prefixed("messages.frozen-bed", "&cYou can't sleep in a frozen chunk."));
    }
    /** A player must not respawn inside a frozen chunk.
     *
     *  <p>This is the other half of the bed protection: the bed itself is refused, but a player
     *  whose respawn point was already set to a bed in that chunk (before it froze) would still
     *  come back to life inside it - and the server moves them there with a plain position
     *  update, so there is no PlayerTeleportEvent to intercept. The respawn location is moved to
     *  the closest spot outside the frozen chunk instead, or to the world spawn if there is no
     *  such spot. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) {
        Location respawn = e.getRespawnLocation();
        if (respawn == null || !frozen(respawn)) return;
        World w = respawn.getWorld();
        if (w == null) return;
        Exit exit = findExit(w, respawn.getBlockX() >> 4, respawn.getBlockZ() >> 4, respawn);
        if (exit != null) {
            DropPoint pt = borderPt(respawn, exit.fx(), exit.fz(), exit.tx(), exit.tz());
            e.setRespawnLocation(new Location(w, pt.x(), respawn.getY(), pt.z(), respawn.getYaw(), respawn.getPitch()));
            return;
        }
        e.setRespawnLocation(w.getSpawnLocation());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!itemRedirect() || !frozen(e.getLocation())) return;
        Location loc = e.getLocation(); World w = loc.getWorld(); if (w == null) return;
        Exit exit = findExit(w, loc.getBlockX() >> 4, loc.getBlockZ() >> 4, loc);
        if (exit == null) return;
        ItemStack stack = e.getEntity().getItemStack();
        if (stack == null || stack.getType().isAir()) return;
        DropPoint pt = borderPt(loc, exit.fx(), exit.fz(), exit.tx(), exit.tz());
        ItemStack toDrop = stack.clone();
        e.setCancelled(true);
        Bukkit.getRegionScheduler().execute(plugin, w, exit.tx(), exit.tz(), () -> {
            if (toDrop.getType().isAir()) return;
            double y = clamp(loc.getY(), w.getMinHeight() + 1, w.getMaxHeight() - 1);
            w.dropItem(new Location(w, pt.x(), safeY(w, blockOf(pt.x()), blockOf(pt.z()), y), pt.z()), toDrop);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        Block b = e.getBlock(); if (b == null) return;
        World w = b.getWorld(); int cx = b.getX() >> 4, cz = b.getZ() >> 4;
        boolean isFrozen = manager.isFrozen(w, cx, cz);
        boolean muted = settings.redstoneProtectionEnabled && manager.isRedstoneMuted(w, cx, cz);
        if (!isFrozen && muted && settings.redstoneUnmuteOnBorderPower && isBorder(b.getX(), b.getZ()) && e.getOldCurrent() == 0 && e.getNewCurrent() > 0 && isSignal(b.getType())) {
            manager.unmuteRedstone(w, cx, cz); return;
        }
        if (isFrozen || muted) e.setNewCurrent(0);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent e) {
        if (!frozen(e.getEntity().getLocation())) return;
        if (settings.allowCreeperExplosions && e.getEntity() instanceof Creeper) return;
        e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!frozen(e.getLocation())) return;
        if (settings.allowCreeperExplosions && e.getEntity() instanceof Creeper) return;
        e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (frozen(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!frozen(e.getBlock())) return;
        if (settings.isFrozenChunkBlockIgnored(Material.FIRE)) return;
        e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!frozen(e.getBlock())) return;
        if (settings.isFrozenChunkBlockIgnored(Material.FIRE)) return;
        e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (!frozen(e.getBlock()) && !frozen(e.getSource())) return;
        Block source = e.getSource();
        if (source != null && settings.isFrozenChunkBlockIgnored(source.getType())) return;
        e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractUnmute(PlayerInteractEvent e) {
        if (!settings.redstoneProtectionEnabled || !settings.redstoneUnmuteOnPlayerInteract) return;
        Block b = e.getClickedBlock();
        if (b == null || !isTrigger(b.getType())) return;
        World w = b.getWorld(); int cx = b.getX() >> 4, cz = b.getZ() >> 4;
        if (manager.isRedstoneMuted(w, cx, cz)) manager.unmuteRedstone(w, cx, cz);
    }
    private static boolean isTrigger(Material m) {
        return m == Material.LEVER || Tag.BUTTONS.isTagged(m) || Tag.PRESSURE_PLATES.isTagged(m);
    }
    private static boolean isSignal(Material m) {
        return m == Material.REDSTONE_WIRE || m == Material.REPEATER || m == Material.COMPARATOR || m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH;
    }
    private static boolean isBorder(int x, int z) {
        int rx = x & 15, rz = z & 15;
        return rx == 0 || rx == 15 || rz == 0 || rz == 15;
    }
    private record Node(int x, int z, int dist) {}
    private record Exit(int fx, int fz, int tx, int tz) {}
    private record DropPoint(double x, double z) {}
}
