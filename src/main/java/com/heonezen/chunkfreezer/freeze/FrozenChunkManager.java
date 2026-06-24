package com.heonezen.chunkfreezer.freeze;

import com.heonezen.chunkfreezer.config.Settings;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FrozenChunkManager {
    public enum FreezeCause { ENTITY, REDSTONE }
    public record FrozenInfo(UUID worldId, int cx, int cz, FreezeCause cause, int entityCount) {}
    private static final class ChunkState {
        final FreezeCause cause;
        final long frozenSinceMs;
        volatile long unfreezeLockUntilMs = 0L;
        volatile long redstoneMuteUntilMs = 0L;
        volatile int  lastEntityCount     = 0;

        ChunkState(FreezeCause cause) {
            this.cause = cause;
            this.frozenSinceMs = System.currentTimeMillis();
        }
    }
    private final Settings settings;
    private final Map<ChunkId, ChunkState> chunks = new ConcurrentHashMap<>();
    public FrozenChunkManager(Plugin plugin, Settings settings) {
        this.settings = settings;
    }
    public boolean isIgnored(EntityType type) { return settings.isIgnored(type); }
    public boolean hasAnyFrozenChunks() { return !chunks.isEmpty(); }
    public boolean isFrozen(World world, int cx, int cz) {
        return chunks.containsKey(ChunkId.of(world, cx, cz));
    }
    public boolean isFrozen(Location loc) {
        return chunks.containsKey(ChunkId.fromLocation(loc));
    }
    public FreezeCause getFreezeCause(World world, int cx, int cz) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        return st != null ? st.cause : null;
    }
    public void lockUnfreezeUntil(World world, int cx, int cz, long untilMs) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        if (st != null) st.unfreezeLockUntilMs = untilMs;
    }
    public boolean isUnfreezeLocked(World world, int cx, int cz) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        return st != null && System.currentTimeMillis() < st.unfreezeLockUntilMs;
    }
    public void muteRedstone(World world, int cx, int cz, long untilMs) {
        ChunkId id = ChunkId.of(world, cx, cz);
        ChunkState st = chunks.get(id);
        if (st != null) {
            st.redstoneMuteUntilMs = untilMs;
        } else {
            ChunkState ghost = new ChunkState(FreezeCause.REDSTONE);
            ghost.redstoneMuteUntilMs = untilMs;
            chunks.putIfAbsent(id, ghost);
        }
    }
    public boolean isRedstoneMuted(World world, int cx, int cz) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        if (st == null) return false;
        long until = st.redstoneMuteUntilMs;
        if (until == Long.MAX_VALUE) return true;
        if (until == 0L) return false;
        if (System.currentTimeMillis() < until) return true;
        st.redstoneMuteUntilMs = 0L;
        return false;
    }
    public void unmuteRedstone(World world, int cx, int cz) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        if (st != null) st.redstoneMuteUntilMs = 0L;
    }
    public void updateEntityCount(World world, int cx, int cz, int count) {
        ChunkState st = chunks.get(ChunkId.of(world, cx, cz));
        if (st != null) st.lastEntityCount = count;
    }
    public List<FrozenInfo> getFrozenChunks() {
        List<FrozenInfo> out = new ArrayList<>(chunks.size());
        for (Map.Entry<ChunkId, ChunkState> e : chunks.entrySet()) {
            ChunkId id = e.getKey(); ChunkState st = e.getValue();
            out.add(new FrozenInfo(id.worldId(), id.x(), id.z(), st.cause, st.lastEntityCount));
        }
        return Collections.unmodifiableList(out);
    }
    public void freezeChunk(Chunk chunk) { freezeChunk(chunk, FreezeCause.ENTITY); }
    public void freezeChunk(Chunk chunk, FreezeCause cause) { chunks.putIfAbsent(ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ()), new ChunkState(cause)); }
    public void unfreezeChunk(Chunk chunk) { chunks.remove(ChunkId.of(chunk.getWorld(), chunk.getX(), chunk.getZ())); }
    public void shutdown() { chunks.clear(); }
}
