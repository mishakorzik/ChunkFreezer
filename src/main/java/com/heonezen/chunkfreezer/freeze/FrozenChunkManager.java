package com.heonezen.chunkfreezer.freeze;

import com.heonezen.chunkfreezer.config.Settings;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FrozenChunkManager {

    public enum FreezeCause {
        ENTITY,
        REDSTONE}

    @SuppressWarnings("unused")
    private final Plugin plugin;
    private final Settings settings;

    private final Map<ChunkId, Map<UUID, EntityState>> frozen = new ConcurrentHashMap<>();
    private final Map<ChunkId, Long> frozenSinceMs = new ConcurrentHashMap<>();
    private final Map<ChunkId, FreezeCause> freezeCause = new ConcurrentHashMap<>();
    private final Map<ChunkId, Long> unfreezeLockUntilMs = new ConcurrentHashMap<>();
    private final Map<ChunkId, Long> redstoneMuteUntilMs = new ConcurrentHashMap<>();

    public FrozenChunkManager(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public boolean isIgnored(EntityType type) { return settings.isIgnored(type); }
    public boolean isFrozen(World world, int chunkX, int chunkZ) { return frozen.containsKey(ChunkId.of(world, chunkX, chunkZ)); }
    public boolean isFrozen(Location loc) { return frozen.containsKey(ChunkId.fromLocation(loc)); }
    public boolean hasAnyFrozenChunks() { return !frozen.isEmpty(); }
    public FreezeCause getFreezeCause(World world, int chunkX, int chunkZ) { return freezeCause.get(ChunkId.of(world, chunkX, chunkZ)); }

    public long frozenAgeSeconds(World world, int chunkX, int chunkZ) {
        Long since = frozenSinceMs.get(ChunkId.of(world, chunkX, chunkZ));
        if (since == null) return -1L;
        long ageMs = System.currentTimeMillis() - since;
        if (ageMs < 0) ageMs = 0;
        return ageMs / 1000L;
    }

    public void lockUnfreezeUntil(World world, int chunkX, int chunkZ, long untilEpochMs) {
        unfreezeLockUntilMs.put(ChunkId.of(world, chunkX, chunkZ), untilEpochMs);
    }

    public boolean isUnfreezeLocked(World world, int chunkX, int chunkZ) {
        Long until = unfreezeLockUntilMs.get(ChunkId.of(world, chunkX, chunkZ));
        return until != null && System.currentTimeMillis() < until;
    }

    public void muteRedstone(World world, int chunkX, int chunkZ, long untilEpochMs) {
        redstoneMuteUntilMs.put(ChunkId.of(world, chunkX, chunkZ), untilEpochMs);
    }

    public boolean isRedstoneMuted(World world, int chunkX, int chunkZ) {
        ChunkId id = ChunkId.of(world, chunkX, chunkZ);
        Long until = redstoneMuteUntilMs.get(id);
        if (until == null) return false;
        if (until == Long.MAX_VALUE) return true;
        if (System.currentTimeMillis() < until) return true;
        redstoneMuteUntilMs.remove(id);
        return false;
    }

    public void unmuteRedstone(World world, int chunkX, int chunkZ) {
        redstoneMuteUntilMs.remove(ChunkId.of(world, chunkX, chunkZ));
    }

    public int purgeEntitiesDownTo(Chunk chunk, int targetCount) {
        if (targetCount < 0) targetCount = 0;

        ChunkId id = ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ());
        Map<UUID, EntityState> states = frozen.get(id);

        int count = countForLimit(chunk);
        if (count <= targetCount) {
            if (states != null) frozenSinceMs.put(id, System.currentTimeMillis());
            return 0;
        }

        List<Entity> candidates = new ArrayList<>();
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Player) continue;
            if (settings.isIgnored(e.getType())) continue;
            candidates.add(e);
        }

        candidates.sort(Comparator.comparingInt(FrozenChunkManager::purgePriority));

        int removed = 0;
        for (Entity e : candidates) {
            if (count <= targetCount) break;
            if (!e.isValid() || e.isDead()) continue;

            UUID uid = e.getUniqueId();
            if (e instanceof LivingEntity le) {
                try {
                    le.setHealth(0.0);
                } catch (Throwable ex) {
                    try { le.damage(Double.MAX_VALUE); } catch (Throwable ignored) { le.remove(); }
                }
            } else {
                e.remove();
            }

            removed++;
            count--;

            if (states != null) states.remove(uid);
        }

        if (states != null) frozenSinceMs.put(id, System.currentTimeMillis());
        return removed;
    }

    private static int purgePriority(Entity e) {
        if (e instanceof Projectile) return 0;
        if (e.getType() == EntityType.ITEM) return 1;
        return 2;
    }

    private int countForLimit(Chunk chunk) {
        int c = 0;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Player) continue;
            if (settings.isIgnored(e.getType())) continue;
            c++;
        }
        return c;
    }

    public void freezeChunk(Chunk chunk) {
        freezeChunk(chunk, FreezeCause.ENTITY);
    }

    public void freezeChunk(Chunk chunk, FreezeCause cause) {
        ChunkId id = ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ());
        if (frozen.containsKey(id)) {
            freezeCause.putIfAbsent(id, cause);
            return;
        }

        freezeCause.put(id, cause);
        frozen.put(id, new ConcurrentHashMap<>());
        frozenSinceMs.put(id, System.currentTimeMillis());

        if (settings.freezeType == 2) {
            Map<UUID, EntityState> states = frozen.get(id);
            for (Entity e : chunk.getEntities()) {
                if (!shouldAffectEntity(e)) continue;
                states.put(e.getUniqueId(), snapshotAndFreeze(e));
            }
        }
    }

    public void unfreezeChunk(Chunk chunk) {
        ChunkId id = ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ());
        Map<UUID, EntityState> states = frozen.remove(id);
        frozenSinceMs.remove(id);
        freezeCause.remove(id);
        unfreezeLockUntilMs.remove(id);
        if (states == null) return;
        if (settings.freezeType == 2) {
            for (Entity e : chunk.getEntities()) {
                if (!shouldAffectEntity(e)) continue;
                EntityState st = states.get(e.getUniqueId());
                if (st != null) restore(e, st);
            }
        }
    }

    public void enforceFrozen(Chunk chunk) {
        ChunkId id = ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ());
        Map<UUID, EntityState> states = frozen.get(id);
        if (states == null) return;
        if (settings.freezeType != 2) return;
        for (Entity e : chunk.getEntities()) {
            if (!shouldAffectEntity(e)) continue;
            states.computeIfAbsent(e.getUniqueId(), _u -> snapshotAndFreeze(e));
            hardFreeze(e);
        }
    }

    private boolean shouldAffectEntity(Entity e) {
        if (e instanceof Player) return false;
        return !settings.isIgnored(e.getType());
    }

    private EntityState snapshotAndFreeze(Entity e) {
        EntityState st = new EntityState();

        st.wasSilent = e.isSilent();
        st.hadGravity = e.hasGravity();

        if (e instanceof LivingEntity le) {
            st.hadAI = le.hasAI();
            st.wasCollidable = le.isCollidable();
            if (e instanceof Mob mob) st.wasAware = mob.isAware();
            else st.wasAware = null;
        }

        hardFreeze(e);
        return st;
    }

    private void hardFreeze(Entity e) {
        e.setSilent(true);
        e.setGravity(false);
        e.setVelocity(new Vector(0, 0, 0));

        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setCollidable(false);
            if (le instanceof Mob mob) {
                mob.setTarget(null);
                mob.setAware(false);
            }
        }
    }

    private void restore(Entity e, EntityState st) {
        e.setSilent(st.wasSilent);
        e.setGravity(st.hadGravity);

        if (e instanceof LivingEntity le) {
            le.setAI(st.hadAI);
            le.setCollidable(st.wasCollidable);
            if (le instanceof Mob mob && st.wasAware != null) mob.setAware(st.wasAware);
        }
    }

    public void shutdown() {
        frozen.clear();
        frozenSinceMs.clear();
        freezeCause.clear();
        unfreezeLockUntilMs.clear();
        redstoneMuteUntilMs.clear();
    }
}
