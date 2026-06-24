package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.*;

public final class ProtectionListener implements Listener {

    private static final int   BFS_LIMIT = 512;
    private static final int[] DIR_X = {1, -1, 0, 0};
    private static final int[] DIR_Z = {0, 0, 1, -1};

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;

    public ProtectionListener(Plugin plugin, Settings settings, FrozenChunkManager manager) { this.plugin = plugin; this.settings = settings; this.manager = manager; }
    private boolean frozen(Location l) { return l != null && l.getWorld() != null && manager.isFrozen(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4); }
    private boolean frozen(Block b) { return b != null && frozen(b.getLocation()); }
    private boolean itemRedirect() { return settings.barrierEnabled && settings.watchItemsEnabled && settings.returnPlayerDroppedItem; }
    private static long packChunk(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static DropPoint borderPt(Location o, int fx, int fz, int tx, int tz) {
        int minX = tx << 4, minZ = tz << 4;
        double x, z;
        if      (tx == fx + 1) { x = minX + 1.5;    z = clamp(o.getZ(), minZ + 1.5, minZ + 14.5); }
        else if (tx == fx - 1) { x = minX + 14.5;   z = clamp(o.getZ(), minZ + 1.5, minZ + 14.5); }
        else if (tz == fz + 1) { z = minZ + 1.5;    x = clamp(o.getX(), minX + 1.5, minX + 14.5); }
        else                   { z = minZ + 14.5;   x = clamp(o.getX(), minX + 1.5, minX + 14.5); }
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
        e.getDrops().clear(); e.setDroppedExp(0);
        Bukkit.getRegionScheduler().execute(plugin, w, exit.tx(), exit.tz(), () -> {
            double y = clamp(loc.getY(), w.getMinHeight() + 1, w.getMaxHeight() - 1);
            Location dl = new Location(w, pt.x(), safeY(w, (int) pt.x(), (int) pt.z(), y), pt.z());
            drops.stream().filter(is -> is != null && !is.getType().isAir()).forEach(is -> w.dropItem(dl, is));
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        Entity ent = e.getEntity();
        if (ent instanceof Player || ent instanceof Item) return;
        if (manager.isIgnored(ent.getType())) return;
        if (!frozen(e.getLocation())) return;
        if (settings.watchProjectilesEnabled && ent instanceof Projectile) return;
        if (settings.allowFireworks && ent instanceof Firework) return;
        e.setCancelled(true);
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
        ItemStack toDrop = stack.clone(); e.setCancelled(true);
        Bukkit.getRegionScheduler().execute(plugin, w, exit.tx(), exit.tz(), () -> {
            if (toDrop.getType().isAir()) return;
            double y = clamp(loc.getY(), w.getMinHeight() + 1, w.getMaxHeight() - 1);
            w.dropItem(new Location(w, pt.x(), safeY(w, (int) pt.x(), (int) pt.z(), y), pt.z()), toDrop);
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
    public void onIgnite(BlockIgniteEvent e) { if (frozen(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (frozen(e.getBlock())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (frozen(e.getBlock()) || frozen(e.getSource())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractUnmute(PlayerInteractEvent e) {
        if (!settings.redstoneProtectionEnabled || !settings.redstoneUnmuteOnPlayerInteract) return;
        Block b = e.getClickedBlock();
        if (b == null || !isTrigger(b.getType())) return;
        World w = b.getWorld(); int cx = b.getX() >> 4, cz = b.getZ() >> 4;
        if (manager.isRedstoneMuted(w, cx, cz)) manager.unmuteRedstone(w, cx, cz);
    }
    private static boolean isTrigger(Material m) { return m == Material.LEVER || Tag.BUTTONS.isTagged(m) || Tag.PRESSURE_PLATES.isTagged(m); }
    private static boolean isSignal(Material m) { return m == Material.REDSTONE_WIRE || m == Material.REPEATER || m == Material.COMPARATOR || m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH; }
    private static boolean isBorder(int x, int z) { int rx = x & 15, rz = z & 15; return rx == 0 || rx == 15 || rz == 0 || rz == 15; }
    private record Node(int x, int z, int dist) {}
    private record Exit(int fx, int fz, int tx, int tz) {}
    private record DropPoint(double x, double z) {}
}
