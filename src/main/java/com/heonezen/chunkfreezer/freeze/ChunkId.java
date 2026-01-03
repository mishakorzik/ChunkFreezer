package com.heonezen.chunkfreezer.freeze;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public record ChunkId(UUID worldId, int x, int z) {
    public static ChunkId of(World world, int chunkX, int chunkZ) {
        return new ChunkId(world.getUID(), chunkX, chunkZ);
    }

    public static ChunkId fromLocation(Location loc) {
        return of(loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }
}
