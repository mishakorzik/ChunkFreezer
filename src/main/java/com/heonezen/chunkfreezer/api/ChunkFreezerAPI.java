package com.heonezen.chunkfreezer.api;

import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Public, read-only API other plugins can use to ask ChunkFreezer about its current state -
 * whether a chunk is frozen, whether a frozen chunk is nearby, whether a player is currently
 * standing in one, and so on. This interface never lets another plugin change ChunkFreezer's
 * state (freeze/unfreeze a chunk itself) - only observe it.
 *
 * <h2>Getting an instance</h2>
 * ChunkFreezer registers its implementation with Bukkit's {@code ServicesManager} as soon as
 * it finishes enabling, so the normal way to get one is:
 * <pre>{@code
 * ChunkFreezerAPI api = Bukkit.getServicesManager().load(ChunkFreezerAPI.class);
 * if (api != null) {
 *     boolean frozen = api.isChunkFrozen(player.getLocation());
 * }
 * }</pre>
 * {@link #get()} is a shorthand for exactly that lookup. Both can return {@code null} - always
 * null-check before use, since ChunkFreezer might not be installed, might not have finished
 * enabling yet (e.g. if your plugin also hooks it during {@code onEnable()} and load order isn't
 * guaranteed), or might be mid-reload.
 *
 * <p>Add ChunkFreezer as a {@code softdepend} (or {@code depend}, if your plugin requires it) in
 * your own {@code plugin.yml} so it's guaranteed to be loaded and enabled before your plugin.
 */
public interface ChunkFreezerAPI {

    /** Shorthand for {@code Bukkit.getServicesManager().load(ChunkFreezerAPI.class)}.
     *  Returns null if ChunkFreezer isn't installed, isn't enabled, or hasn't registered yet. */
    static ChunkFreezerAPI get() {
        return Bukkit.getServicesManager().load(ChunkFreezerAPI.class);
    }

    // Whether the chunk at these chunk coordinates (not block coordinates) is currently frozen.
    boolean isChunkFrozen(World world, int chunkX, int chunkZ);

    // Whether this chunk is currently frozen.
    default boolean isChunkFrozen(Chunk chunk) {
        return isChunkFrozen(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    // Whether the chunk containing this location is currently frozen. False if the location's world is somehow null.
    default boolean isChunkFrozen(Location location) {
        World w = location.getWorld();
        return w != null && isChunkFrozen(w, location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** Whether any frozen chunk exists within {@code radiusInChunks} chunks of the given chunk
     *  coordinates (a square radius, not circular - matches how ChunkFreezer measures every other
     *  radius internally). The chunk at (chunkX, chunkZ) itself is included in the check, so a
     *  radius of 0 behaves the same as {@link #isChunkFrozen(World, int, int)}. Negative radii are
     *  treated as 0; very large radii are silently capped to keep this cheap to call. */
    boolean isNearFrozenChunk(World world, int chunkX, int chunkZ, int radiusInChunks);

    // Same as {@link #isNearFrozenChunk(World, int, int, int)}, centered on a location's chunk.
    default boolean isNearFrozenChunk(Location location, int radiusInChunks) {
        World w = location.getWorld();
        return w != null && isNearFrozenChunk(w, location.getBlockX() >> 4, location.getBlockZ() >> 4, radiusInChunks);
    }

    /** Whether this player (or, if they're riding one, their vehicle) is currently standing inside
     *  a frozen chunk. This reflects ChunkFreezer's own per-player tracking, which refreshes a few
     *  times a second rather than being computed fresh on every call - for most purposes that's
     *  indistinguishable from live, but don't rely on it for anything timing-sensitive down to the
     *  exact tick. False for a null or offline player. */
    boolean isPlayerInFrozenChunk(Player player);

    // Why this chunk is frozen, or null if it isn't currently frozen.
    FrozenChunkManager.FreezeCause getFreezeCause(World world, int chunkX, int chunkZ);

    /** A snapshot of every chunk that is frozen right now, across all worlds. The returned
     *  collection is a point-in-time copy - it won't reflect chunks freezing or unfreezing after
     *  this call returns. */
    Collection<FrozenChunkManager.FrozenInfo> getFrozenChunks();

    /** Whether ChunkFreezer's barrier (the feature that blocks movement/items/projectiles across
     *  a frozen chunk's border) is currently active at all, per its own config. If this is false,
     *  a chunk can still be "frozen" (building/redstone/etc. still restricted inside it) but
     *  nothing stops anyone walking in and out of it. */
    boolean isBarrierEnabled();
}
