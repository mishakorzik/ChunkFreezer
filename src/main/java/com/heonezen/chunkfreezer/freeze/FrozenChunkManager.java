package com.heonezen.chunkfreezer.freeze;

import com.heonezen.chunkfreezer.config.Settings;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

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
    private final Plugin plugin;
    /** Swapped on /chunk reload (see {@link #updateSettings}): the manager itself outlives a
     *  reload so that chunks which are frozen right now stay frozen instead of being released the
     *  moment an admin reloads the config. */
    private volatile Settings settings;
    /** Invoked after every change to the set of frozen chunks - used to (un)register the costly
     *  per-entity move listener only while there is at least one frozen chunk. */
    private volatile Runnable frozenSetListener;
    private final Map<ChunkId, ChunkState> chunks = new ConcurrentHashMap<>();
    /** Redstone mute is intentionally tracked separately from freeze state: it is designed to
     *  outlive a chunk's frozen/unfrozen transition (see mute-after-freeze / mute-max-seconds),
     *  so it must not live inside ChunkState, which unfreezeChunk() discards entirely. */
    private final Map<ChunkId, Long> redstoneMuteUntil = new ConcurrentHashMap<>();
    /** Same reasoning for the "don't unfreeze yet" lock: it has to survive a freeze/unfreeze
     *  cycle without ever making an unfrozen chunk look frozen. */
    private final Map<ChunkId, Long> unfreezeLockUntil = new ConcurrentHashMap<>();
    /** The entities that were already inside a chunk when it froze. Anything that shows up in a
     *  frozen chunk afterwards is a new arrival and gets removed again - the last-resort net
     *  that keeps a frozen chunk empty even if a future Minecraft version changes how (or
     *  whether) entities are spawned and the usual events stop firing.
     *
     *  <p>Each set is only ever touched from the region thread that owns its chunk. */
    private final Map<ChunkId, Set<UUID>> frozenBaseline = new ConcurrentHashMap<>();
    /** Chunks that had so many entities when they froze that no baseline could be tracked. A
     *  partial baseline would produce false positives (entities that were there all along being
     *  mistaken for new arrivals), so the sweep is simply skipped for those chunks. */
    private final Set<ChunkId> frozenBaselineSkipped = ConcurrentHashMap.newKeySet();
    /** Upper bound for the baseline set, so a pathologically overloaded chunk can't balloon the
     *  plugin's memory usage. */
    private static final int MAX_BASELINE_ENTITIES = 2048;

    public FrozenChunkManager(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }
    public void updateSettings(Settings settings) { this.settings = settings; }
    public void setFrozenSetListener(Runnable listener) { this.frozenSetListener = listener; }
    private void fireFrozenSetChanged() {
        Runnable r = frozenSetListener;
        if (r == null) return;
        try { r.run(); }
        catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "frozen-chunk listener failed", t); }
    }
    public boolean isIgnored(EntityType type) { return settings.isIgnored(type); }
    public boolean hasAnyFrozenChunks() { return !chunks.isEmpty(); }
    public boolean isFrozen(World world, int cx, int cz) {
        if (world == null || chunks.isEmpty()) return false;
        return chunks.containsKey(ChunkId.of(world, cx, cz));
    }
    /** Whether any chunk within a square radius (in chunks, center included) is frozen. */
    public boolean isNearFrozen(World world, int cx, int cz, int radius) {
        if (world == null || chunks.isEmpty()) return false;
        UUID wid = world.getUID();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                if (chunks.containsKey(new ChunkId(wid, cx + dx, cz + dz))) return true;
        return false;
    }
    public FreezeCause getFreezeCause(World world, int cx, int cz) {
        if (world == null) return null;
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        return st != null ? st.cause : null;
    }
    public void lockUnfreezeUntil(World world, int cx, int cz, long untilMs) {
        unfreezeLockUntil.put(ChunkId.of(world, cx, cz), untilMs);
    }
    public boolean isUnfreezeLocked(World world, int cx, int cz) {
        return unfreezeLockRemainingMs(world, cx, cz) > 0;
    }
    /** Milliseconds left on this chunk's unfreeze lock, 0 if it isn't locked (any more). */
    public long unfreezeLockRemainingMs(World world, int cx, int cz) {
        ChunkId id = ChunkId.of(world, cx, cz);
        Long until = unfreezeLockUntil.get(id);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left > 0) return left;
        unfreezeLockUntil.remove(id, until);
        return 0;
    }
    public void muteRedstone(World world, int cx, int cz, long untilMs) {
        redstoneMuteUntil.put(ChunkId.of(world, cx, cz), untilMs);
    }
    public boolean isRedstoneMuted(World world, int cx, int cz) {
        if (redstoneMuteUntil.isEmpty()) return false;
        ChunkId id = ChunkId.of(world, cx, cz);
        Long until = redstoneMuteUntil.get(id);
        if (until == null) return false;
        if (until == Long.MAX_VALUE) return true;
        if (System.currentTimeMillis() < until) return true;
        // Conditional remove: never drop a fresh mute another thread set in the meantime.
        redstoneMuteUntil.remove(id, until);
        return false;
    }
    public void unmuteRedstone(World world, int cx, int cz) {
        redstoneMuteUntil.remove(ChunkId.of(world, cx, cz));
    }
    public void clearRedstoneMutes() { redstoneMuteUntil.clear(); }
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
    public boolean freezeChunk(Chunk chunk) { return freezeChunk(chunk, FreezeCause.ENTITY); }
    /** Returns true only if the chunk was not frozen before this call. */
    public boolean freezeChunk(Chunk chunk, FreezeCause cause) {
        boolean added = chunks.putIfAbsent(ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ()), new ChunkState(cause)) == null;
        if (added) fireFrozenSetChanged();
        return added;
    }
    public void unfreezeChunk(Chunk chunk) {
        unfreeze(ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ()));
    }
    public void unfreezeChunk(World world, int cx, int cz) {
        unfreeze(ChunkId.of(world, cx, cz));
    }
    private void unfreeze(ChunkId id) {
        boolean removed = chunks.remove(id) != null;
        unfreezeLockUntil.remove(id);
        frozenBaseline.remove(id);
        frozenBaselineSkipped.remove(id);
        if (removed) fireFrozenSetChanged();
    }
    /** Releases every chunk frozen for the given cause - used after a reload that turned the
     *  corresponding protection off, since nothing would ever unfreeze those chunks otherwise. */
    public void releaseCause(FreezeCause cause) {
        List<ChunkId> ids = new ArrayList<>();
        for (Map.Entry<ChunkId, ChunkState> e : chunks.entrySet()) if (e.getValue().cause == cause) ids.add(e.getKey());
        for (ChunkId id : ids) unfreeze(id);
    }
    /** Removes entities that appeared inside a frozen chunk after it froze, and returns how many
     *  were removed. The entities that were already there when the chunk froze (the ones that
     *  caused it) are never touched, and neither are players, ignored entity types, types
     *  explicitly listed under instant-despawn.ignore-entity-types / frozen-chunk.ignore-entity-types,
     *  tamed pets, named entities (unless instant-despawn.named-entity allows it) and anything
     *  carrying a player.
     *
     *  <p>The first call for a chunk records the baseline (which entities it contained when it
     *  froze) and removes nothing; every later call removes whatever turned up since.
     *
     *  <p>Dropped items and projectiles are deliberately left alone: items are moved out of the
     *  chunk (and returned to their owner) by the item handling instead of being deleted, and a
     *  projectile that merely flies through is supposed to survive - only one that gets stuck is
     *  removed. Must be called from the region thread that owns the chunk. */
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
                if (e instanceof Player) continue;
                baseline.add(e.getUniqueId());
            }
            frozenBaseline.put(id, baseline);
            return 0;
        }
        Settings s = settings;
        int removed = 0;
        for (Entity e : entities) {
            if (e == null || e.isDead() || !e.isValid()) continue;
            if (e instanceof Player || e instanceof Item || e instanceof Projectile) continue;
            if (baseline.contains(e.getUniqueId())) continue;
            EntityType type = e.getType();
            if (s.isIgnored(type) || s.isFrozenChunkIgnored(type) || s.isInstantDespawnIgnored(type)) continue;
            if (isShieldedFromSweep(e, s)) continue;
            e.remove();
            removed++;
        }
        return removed;
    }
    /** Entities that must never be deleted silently just for entering a frozen chunk: a player's
     *  tamed pet that followed them, a named mob, or a vehicle/mount a player is sitting on. */
    private static boolean isShieldedFromSweep(Entity e, Settings s) {
        if (e instanceof Tameable t && t.isTamed()) return true;
        if (!s.instantDespawnNamedEntity && e.customName() != null) return true;
        for (Entity passenger : e.getPassengers()) if (passenger instanceof Player) return true;
        return false;
    }
    public void shutdown() {
        frozenSetListener = null;
        chunks.clear(); redstoneMuteUntil.clear(); unfreezeLockUntil.clear();
        frozenBaseline.clear(); frozenBaselineSkipped.clear();
    }
}
