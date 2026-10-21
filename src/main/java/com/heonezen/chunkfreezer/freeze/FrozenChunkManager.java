package com.heonezen.chunkfreezer.freeze;

import com.heonezen.chunkfreezer.config.Settings;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FrozenChunkManager {
    public enum FreezeCause { ENTITY, REDSTONE }
    public record FrozenInfo(UUID worldId, int cx, int cz, FreezeCause cause, int entityCount, long frozenSinceMs) {}
    private static final class ChunkState {
        final FreezeCause cause;
        final long frozenSinceMs;
        volatile int  lastEntityCount     = 0;

        ChunkState(FreezeCause cause) {
            this.cause = cause;
            this.frozenSinceMs = System.currentTimeMillis();
        }
    }
    private final Settings settings;
    private final Map<ChunkId, ChunkState> chunks = new ConcurrentHashMap<>();
    /** Redstone mute is intentionally tracked separately from freeze state: it is designed to
     *  outlive a chunk's frozen/unfrozen transition (see mute-after-freeze / mute-max-seconds),
     *  so it must not live inside ChunkState, which unfreezeChunk() discards entirely. */
    private final Map<ChunkId, Long> redstoneMuteUntil = new ConcurrentHashMap<>();
    /** Same reasoning for the "don't unfreeze yet" lock: it has to survive a freeze/unfreeze
     *  cycle without ever making an unfrozen chunk look frozen. It used to be stored inside
     *  ChunkState, which meant locking a chunk that wasn't frozen yet created a placeholder
     *  entry - and that placeholder made isFrozen() report true for a chunk that was never
     *  frozen (showing up in /chunk list and in the public API as a frozen chunk). */
    private final Map<ChunkId, Long> unfreezeLockUntil = new ConcurrentHashMap<>();
    /** The entities that were already inside a chunk when it froze. Anything that shows up in a
     *  frozen chunk afterwards is a new arrival and gets removed again - the last-resort net
     *  that keeps a frozen chunk empty even if a future Minecraft version changes how (or
     *  whether) entities are spawned and the usual events stop firing. */
    private final Map<ChunkId, Set<UUID>> frozenBaseline = new ConcurrentHashMap<>();
    /** Chunks that had so many entities when they froze that no baseline could be tracked. A
     *  partial baseline would produce false positives (entities that were there all along being
     *  mistaken for new arrivals), so the sweep is simply skipped for those chunks. */
    private final Set<ChunkId> frozenBaselineSkipped = ConcurrentHashMap.newKeySet();
    /** Upper bound for the baseline set, so a pathologically overloaded chunk can't balloon the
     *  plugin's memory usage. Above this many entities the baseline simply isn't recorded and
     *  the new-arrival sweep is skipped for that chunk. */
    private static final int MAX_BASELINE_ENTITIES = 2048;
    public FrozenChunkManager(Plugin plugin, Settings settings) {
        this.settings = settings;
    }
    public boolean isIgnored(EntityType type) { return settings.isIgnored(type); }
    public boolean hasAnyFrozenChunks() { return !chunks.isEmpty(); }
    public boolean isFrozen(World world, int cx, int cz) {
        return chunks.containsKey(ChunkId.of(world, cx, cz));
    }
    public FreezeCause getFreezeCause(World world, int cx, int cz) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        return st != null ? st.cause : null;
    }
    public void lockUnfreezeUntil(World world, int cx, int cz, long untilMs) {
        unfreezeLockUntil.put(ChunkId.of(world, cx, cz), untilMs);
    }
    public boolean isUnfreezeLocked(World world, int cx, int cz) {
        ChunkId id = ChunkId.of(world, cx, cz);
        Long until = unfreezeLockUntil.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        unfreezeLockUntil.remove(id, until);
        return false;
    }
    public void muteRedstone(World world, int cx, int cz, long untilMs) {
        redstoneMuteUntil.put(ChunkId.of(world, cx, cz), untilMs);
    }
    public boolean isRedstoneMuted(World world, int cx, int cz) {
        ChunkId id = ChunkId.of(world, cx, cz);
        Long until = redstoneMuteUntil.get(id);
        if (until == null) return false;
        if (until == Long.MAX_VALUE) return true;
        if (System.currentTimeMillis() < until) return true;
        redstoneMuteUntil.remove(id);
        return false;
    }
    public void unmuteRedstone(World world, int cx, int cz) {
        redstoneMuteUntil.remove(ChunkId.of(world, cx, cz));
    }
    public void updateEntityCount(World world, int cx, int cz, int count) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        if (st != null) st.lastEntityCount = count;
    }
    public List<FrozenInfo> getFrozenChunks() {
        List<FrozenInfo> out = new ArrayList<>(chunks.size());
        for (Map.Entry<ChunkId, ChunkState> e : chunks.entrySet()) {
            ChunkId id = e.getKey(); ChunkState st = e.getValue();
            out.add(new FrozenInfo(id.worldId(), id.x(), id.z(), st.cause, st.lastEntityCount, st.frozenSinceMs));
        }
        return Collections.unmodifiableList(out);
    }
    public void freezeChunk(Chunk chunk) { freezeChunk(chunk, FreezeCause.ENTITY); }
    public void freezeChunk(Chunk chunk, FreezeCause cause) { chunks.putIfAbsent(ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ()), new ChunkState(cause)); }
    public void unfreezeChunk(Chunk chunk) {
        ChunkId id = ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ());
        chunks.remove(id);
        unfreezeLockUntil.remove(id);
        frozenBaseline.remove(id);
        frozenBaselineSkipped.remove(id);
    }
    /** Removes entities that appeared inside a frozen chunk after it froze, and returns how many
     *  were removed. The entities that were already there when the chunk froze (the ones that
     *  caused it) are never touched, and neither are players, ignored entity types, or types
     *  explicitly listed under instant-despawn.ignore-entity-types / frozen-chunk.ignore-entity-types.
     *
     *  <p>The first call for a chunk records the baseline (which entities it contained when it
     *  froze) and removes nothing; every later call removes whatever turned up since.
     *
     *  <p>Dropped items and projectiles are deliberately left alone: items are moved out of the
     *  chunk (and returned to their owner) by the item handling instead of being deleted, and a
     *  projectile that merely flies through is supposed to survive - only one that gets stuck is
     *  removed. */
    public int removeNewArrivals(World world, int cx, int cz, Entity[] entities) {
        ChunkId id = ChunkId.of(world, cx, cz);
        Set<UUID> baseline = frozenBaseline.get(id);
        if (baseline == null) {
            if (frozenBaselineSkipped.contains(id)) return 0;
            if (entities.length > MAX_BASELINE_ENTITIES) {
                frozenBaselineSkipped.add(id);
                return 0;
            }
            baseline = new HashSet<>(Math.max(16, entities.length * 2));
            for (Entity e : entities) {
                if (e == null || e.isDead() || !e.isValid()) continue;
                if (e instanceof org.bukkit.entity.Player) continue;
                baseline.add(e.getUniqueId());
            }
            frozenBaseline.put(id, baseline);
            return 0;
        }
        int removed = 0;
        for (Entity e : entities) {
            if (e == null || e.isDead() || !e.isValid()) continue;
            if (e instanceof org.bukkit.entity.Player) continue;
            if (e instanceof org.bukkit.entity.Item) continue;
            if (e instanceof org.bukkit.entity.Projectile) continue;
            if (baseline.contains(e.getUniqueId())) continue;
            if (settings.isIgnored(e.getType())) continue;
            if (settings.isFrozenChunkIgnored(e.getType())) continue;
            if (settings.isInstantDespawnIgnored(e.getType())) continue;
            e.remove();
            removed++;
        }
        return removed;
    }
    public void shutdown() {
        chunks.clear(); redstoneMuteUntil.clear(); unfreezeLockUntil.clear();
        frozenBaseline.clear(); frozenBaselineSkipped.clear();
    }
}
