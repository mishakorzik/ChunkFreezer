package com.heonezen.chunkfreezer.api;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import com.heonezen.chunkfreezer.listener.BarrierListener;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;

/** Internal implementation of {@link ChunkFreezerAPI}. Not part of the public API surface itself
 *  - other plugins should only ever reference the {@link ChunkFreezerAPI} interface, obtained via
 *  {@link ChunkFreezerAPI#get()} or the ServicesManager, never this class directly.
 *
 *  <p>One instance of this class is created for the plugin's entire lifetime and registered with
 *  the ServicesManager exactly once - {@code /chunk reload} swaps out the manager/settings/
 *  barrier-listener it delegates to (via {@link #update}/{@link #setBarrierListener}) rather than
 *  replacing the registered instance itself. That way, a plugin that looked up the API once in
 *  its own {@code onEnable()} and held on to the reference (a very common pattern, even though
 *  calling {@link ChunkFreezerAPI#get()} fresh each time is the more defensive choice) keeps
 *  seeing live data across a ChunkFreezer reload instead of a frozen, stale snapshot. */
public final class ChunkFreezerAPIImpl implements ChunkFreezerAPI {

    /** Square radius cap for isNearFrozenChunk(): keeps a badly-behaved caller (e.g. radius =
     *  Integer.MAX_VALUE) from turning one API call into an unbounded scan. */
    private static final int MAX_RADIUS = 64;

    private volatile FrozenChunkManager manager;
    private volatile Settings settings;
    private volatile BarrierListener barrierListener;

    // Called once at startup and again after every /chunk reload.
    public void update(FrozenChunkManager manager, Settings settings) {
        this.manager = manager;
        this.settings = settings;
    }
    // Set (or cleared, with null) whenever BarrierListener is (re)created/torn down.
    public void setBarrierListener(BarrierListener barrierListener) {
        this.barrierListener = barrierListener;
    }
    @Override
    public boolean isChunkFrozen(World world, int chunkX, int chunkZ) {
        FrozenChunkManager m = manager;
        return world != null && m != null && m.isFrozen(world, chunkX, chunkZ);
    }
    @Override
    public boolean isNearFrozenChunk(World world, int chunkX, int chunkZ, int radiusInChunks) {
        FrozenChunkManager m = manager;
        if (world == null || m == null || !m.hasAnyFrozenChunks()) return false;
        int r = Math.max(0, Math.min(MAX_RADIUS, radiusInChunks));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (m.isFrozen(world, chunkX + dx, chunkZ + dz)) return true;
            }
        }
        return false;
    }
    @Override
    public boolean isPlayerInFrozenChunk(Player player) {
        if (player == null || !player.isOnline()) return false;
        BarrierListener bl = barrierListener;
        return bl != null && bl.isPlayerInFrozenChunk(player.getUniqueId());
    }
    @Override
    public FrozenChunkManager.FreezeCause getFreezeCause(World world, int chunkX, int chunkZ) {
        FrozenChunkManager m = manager;
        return world == null || m == null ? null : m.getFreezeCause(world, chunkX, chunkZ);
    }
    @Override
    public Collection<FrozenChunkManager.FrozenInfo> getFrozenChunks() {
        FrozenChunkManager m = manager;
        return m == null ? Collections.emptyList() : m.getFrozenChunks();
    }
    @Override
    public boolean isBarrierEnabled() {
        Settings s = settings;
        return s != null && s.barrierEnabled;
    }
}
