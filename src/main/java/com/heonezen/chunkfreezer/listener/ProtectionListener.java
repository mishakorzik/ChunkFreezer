package com.heonezen.chunkfreezer.listener;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

public final class ProtectionListener implements Listener {

    private final Plugin plugin;
    private final Settings settings;
    private final FrozenChunkManager manager;

    public ProtectionListener(Plugin plugin, Settings settings, FrozenChunkManager manager) {
        this.plugin = plugin;
        this.settings = settings;
        this.manager = manager;
    }

    private boolean frozen(Location l) {
        if (l == null || l.getWorld() == null) return false;
        return manager.isFrozen(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    private boolean frozen(Block b) { return b != null && frozen(b.getLocation()); }
    private boolean enabled() { return settings.freezeType != 0; }
    private boolean itemRedirectEnabled() { return settings.barrierEnabled && settings.watchItems && settings.freezeType != 0; }

    private static long packChunk(int x, int z) { return (((long) x) << 32) ^ (z & 0xFFFFFFFFL); }
    private static final int[] DIR_X = {1, -1, 0, 0};
    private static final int[] DIR_Z = {0, 0, 1, -1};

    private static double clamp(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static DropPoint borderPoint(Location origin, int fromFrozenCx, int fromFrozenCz, int toUnfrozenCx, int toUnfrozenCz) {
        int minX = toUnfrozenCx << 4;
        int minZ = toUnfrozenCz << 4;

        double x = (toUnfrozenCx << 4) + 8.0;
        double z = (toUnfrozenCz << 4) + 8.0;

        if (toUnfrozenCx == fromFrozenCx + 1) {
            x = minX + 0.45;
            z = clamp(origin.getZ(), minZ + 0.45, minZ + 16.0 - 0.45);
        } else if (toUnfrozenCx == fromFrozenCx - 1) {
            x = minX + 16.0 - 0.45;
            z = clamp(origin.getZ(), minZ + 0.45, minZ + 16.0 - 0.45);
        } else if (toUnfrozenCz == fromFrozenCz + 1) {
            z = minZ + 0.45;
            x = clamp(origin.getX(), minX + 0.45, minX + 16.0 - 0.45);
        } else if (toUnfrozenCz == fromFrozenCz - 1) {
            z = minZ + 16.0 - 0.45;
            x = clamp(origin.getX(), minX + 0.45, minX + 16.0 - 0.45);
        }

        return new DropPoint(x, z);
    }

    private static double adjustYForDrop(World w, int bx, int bz, double startY) {
        int y = (int) Math.floor(startY);
        int minY = w.getMinHeight() + 1;
        int maxY = w.getMaxHeight() - 1;
        if (y < minY) y = minY;
        if (y > maxY) y = maxY;

        for (int dy = 0; dy <= 16 && (y + dy) <= maxY; dy++) {
            if (!w.getBlockAt(bx, y + dy, bz).getType().isSolid()) {
                return (y + dy) + 0.05;
            }
        }
        for (int dy = 1; dy <= 16 && (y - dy) >= minY; dy++) {
            if (!w.getBlockAt(bx, y - dy, bz).getType().isSolid()) {
                return (y - dy) + 0.05;
            }
        }
        return y + 0.05;
    }

    private Exit findNearestUnfrozenExit(World w, int startCx, int startCz, Location origin) {
        if (w == null) return null;
        if (!manager.isFrozen(w, startCx, startCz)) return null;

        final ArrayDeque<Node> q = new ArrayDeque<>();
        final HashSet<Long> visited = new HashSet<>();

        q.add(new Node(startCx, startCz, 0));
        visited.add(packChunk(startCx, startCz));

        Exit best = null;
        int bestChunkDistance = Integer.MAX_VALUE;
        int visitedCount = 0;
        double bestDistSq = Double.POSITIVE_INFINITY;

        while (!q.isEmpty() && visitedCount < 50000) {
            Node cur = q.poll();
            visitedCount++;

            if (cur.dist() > bestChunkDistance) break;
            for (int i = 0; i < 4; i++) {
                int nx = cur.x() + DIR_X[i];
                int nz = cur.z() + DIR_Z[i];

                if (!manager.isFrozen(w, nx, nz)) {
                    int exitDist = cur.dist() + 1;
                    if (exitDist > bestChunkDistance) continue;

                    DropPoint p = borderPoint(origin, cur.x(), cur.z(), nx, nz);
                    double dx = p.x() - origin.getX();
                    double dz = p.z() - origin.getZ();
                    double distSq = (dx * dx) + (dz * dz);

                    if (exitDist < bestChunkDistance || distSq < bestDistSq) {
                        bestChunkDistance = exitDist;
                        bestDistSq = distSq;
                        best = new Exit(cur.x(), cur.z(), nx, nz);
                    }
                    continue;
                }

                if (cur.dist() + 1 >= bestChunkDistance) continue;
                long key = packChunk(nx, nz);
                if (visited.add(key)) {
                    q.add(new Node(nx, nz, cur.dist() + 1));
                }
            }
        }

        return best;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!enabled()) return;

        Entity clicked = e.getRightClicked();
        if (clicked instanceof Player) return;
        if (manager.isIgnored(clicked.getType())) return;
        if (frozen(clicked.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!enabled()) return;
        if (e.getEntity() instanceof Player) return;
        if (manager.isIgnored(e.getEntity().getType())) return;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!itemRedirectEnabled()) return;
        if (e.getEntity() instanceof Player) return;
        if (!frozen(e.getEntity().getLocation())) return;

        Location deathLoc = e.getEntity().getLocation();
        World w = deathLoc.getWorld();
        if (w == null) return;

        Exit exit = findNearestUnfrozenExit(w, deathLoc.getBlockX() >> 4, deathLoc.getBlockZ() >> 4, deathLoc);
        if (exit == null) return;

        DropPoint point = borderPoint(deathLoc, exit.frozenX(), exit.frozenZ(), exit.unfrozenX(), exit.unfrozenZ());
        List<ItemStack> drops = new ArrayList<>(e.getDrops());
        e.getDrops().clear();
        e.setDroppedExp(0);
        Bukkit.getRegionScheduler().execute(plugin, w, exit.unfrozenX(), exit.unfrozenZ(), () -> {
            int bx = (int) Math.floor(point.x());
            int bz = (int) Math.floor(point.z());
            Location dropLoc = new Location(w, point.x(), adjustYForDrop(w, bx, bz, clampY(w, deathLoc.getY())), point.z(), deathLoc.getYaw(), deathLoc.getPitch());
            for (ItemStack is : drops) {
                if (is == null || is.getType().isAir()) continue;
                w.dropItem(dropLoc, is);
            }
        });
    }

    private static double clampY(World w, double y) {
        double min = w.getMinHeight() + 1;
        double max = w.getMaxHeight() - 1;
        if (y < min) return min;
        if (y > max) return max;
        return y;
    }

    private record Node(int x, int z, int dist) {}
    private record Exit(int frozenX, int frozenZ, int unfrozenX, int unfrozenZ) {}
    private record DropPoint(double x, double z) {}

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        if (!enabled()) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        if (ent instanceof org.bukkit.entity.Item) return;
        if (manager.isIgnored(ent.getType())) return;
        if (frozen(e.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!itemRedirectEnabled()) return;
        if (!frozen(e.getLocation())) return;

        Location spawnLoc = e.getLocation();
        World w = spawnLoc.getWorld();
        if (w == null) return;

        Exit exit = findNearestUnfrozenExit(w, spawnLoc.getBlockX() >> 4, spawnLoc.getBlockZ() >> 4, spawnLoc);
        if (exit == null) return;

        org.bukkit.entity.Item item = e.getEntity();
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType().isAir()) return;

        DropPoint point = borderPoint(spawnLoc, exit.frozenX(), exit.frozenZ(), exit.unfrozenX(), exit.unfrozenZ());
        e.setCancelled(true);

        ItemStack toDrop = stack.clone();
        Bukkit.getRegionScheduler().execute(plugin, w, exit.unfrozenX(), exit.unfrozenZ(), () -> {
            if (toDrop.getType().isAir()) return;
            int bx = (int) Math.floor(point.x());
            int bz = (int) Math.floor(point.z());
            Location dropLoc = new Location(w, point.x(), adjustYForDrop(w, bx, bz, clampY(w, spawnLoc.getY())), point.z(), spawnLoc.getYaw(), spawnLoc.getPitch());
            w.dropItem(dropLoc, toDrop);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (!enabled()) return;
        if (manager.isIgnored(e.getEntityType())) return;
        if (frozen(e.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        if (!enabled()) return;

        Block b = e.getBlock();
        if (b == null) return;

        World w = b.getWorld();
        int cx = b.getX() >> 4;
        int cz = b.getZ() >> 4;

        boolean frozen = manager.isFrozen(w, cx, cz);
        boolean muted = settings.redstoneProtectionEnabled && manager.isRedstoneMuted(w, cx, cz);

        if (!frozen && muted && settings.redstoneUnmuteOnBorderPower
                && isBorderBlock(b.getX(), b.getZ())
                && e.getOldCurrent() == 0 && e.getNewCurrent() > 0
                && isSignalCarrier(b.getType())) {
            manager.unmuteRedstone(w, cx, cz);
            return;
        }

        if (frozen || muted) {
            e.setNewCurrent(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        if (!enabled()) return;
        if (settings.freezeType != 2) return;
        if (frozen(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) {
        if (!enabled()) return;
        if (settings.freezeType != 2) return;
        if (frozen(e.getBlock()) || frozen(e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidLevel(FluidLevelChangeEvent e) {
        if (!enabled()) return;
        if (settings.freezeType != 2) return;
        if (frozen(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!enabled()) return;
        if (settings.freezeType != 2) return;
        if (frozen(e.getBlock())) { e.setCancelled(true); return; }
        for (Block moved : e.getBlocks()) {
            if (frozen(moved)) { e.setCancelled(true); return; }
            Block dest = moved.getRelative(e.getDirection());
            if (frozen(dest)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!enabled()) return;
        if (settings.freezeType != 2) return;
        if (frozen(e.getBlock())) { e.setCancelled(true); return; }
        for (Block moved : e.getBlocks()) {
            if (frozen(moved)) { e.setCancelled(true); return; }
            if (frozen(moved.getRelative(e.getDirection().getOppositeFace()))) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent e) {
        if (!enabled()) return;
        if (frozen(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!enabled()) return;
        if (frozen(e.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!enabled()) return;
        if (frozen(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!enabled()) return;
        if (frozen(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!enabled()) return;
        if (frozen(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (!enabled()) return;
        if (frozen(e.getBlock()) || frozen(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractUnmute(PlayerInteractEvent e) {
        if (!enabled()) return;
        if (!settings.redstoneProtectionEnabled) return;
        if (!settings.redstoneUnmuteOnPlayerInteract) return;

        Block b = e.getClickedBlock();
        if (b == null) return;

        Material m = b.getType();
        Action a = e.getAction();
        if (!isRedstoneTriggerBlock(m, a)) return;

        World w = b.getWorld();
        int cx = b.getX() >> 4;
        int cz = b.getZ() >> 4;

        if (manager.isRedstoneMuted(w, cx, cz)) {
            manager.unmuteRedstone(w, cx, cz);
        }
    }

    private static boolean isRedstoneTriggerBlock(Material m, Action action) {
        if (m == Material.LEVER) return true;
        if (Tag.BUTTONS.isTagged(m)) return true;
        if (Tag.PRESSURE_PLATES.isTagged(m)) return true;
        return false;
    }

    private static boolean isSignalCarrier(Material m) {
        return m == Material.REDSTONE_WIRE
                || m == Material.REPEATER
                || m == Material.COMPARATOR
                || m == Material.REDSTONE_TORCH
                || m == Material.REDSTONE_WALL_TORCH;
    }

    private static boolean isBorderBlock(int x, int z) {
        int rx = x & 15;
        int rz = z & 15;
        return rx == 0 || rx == 15 || rz == 0 || rz == 15;
    }

}
