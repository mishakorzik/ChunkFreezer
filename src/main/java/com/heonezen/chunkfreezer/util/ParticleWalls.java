package com.heonezen.chunkfreezer.util;

import org.bukkit.Particle;
import org.bukkit.entity.Player;

/** Draws a vertical "wall" of dust particles visible only to one player. Shared by the frozen
 *  chunk border (red) and the Folia region border (blue/orange) so both look and cost the same.
 *  Must be called from the thread that owns the player. */
public final class ParticleWalls {

    private ParticleWalls() {}

    private static final double STEP = 0.5;
    private static final double BELOW = 1.0;
    private static final double ABOVE = 3.0;

    /** Wall on the plane x = {@code x}, spanning z in [zMin, zMax] clipped to {@code pz +- d}. */
    public static void wallAtX(Player p, double x, double zMin, double zMax, double pz, double py, double d, Particle.DustOptions dust) {
        double from = Math.max(zMin, pz - d), to = Math.min(zMax, pz + d);
        for (double z = from; z <= to; z += STEP)
            for (double y = py - BELOW; y <= py + ABOVE; y += STEP)
                p.spawnParticle(Particle.DUST, x, y, z, 1, dust);
    }

    /** Wall on the plane z = {@code z}, spanning x in [xMin, xMax] clipped to {@code px +- d}. */
    public static void wallAtZ(Player p, double z, double xMin, double xMax, double px, double py, double d, Particle.DustOptions dust) {
        double from = Math.max(xMin, px - d), to = Math.min(xMax, px + d);
        for (double x = from; x <= to; x += STEP)
            for (double y = py - BELOW; y <= py + ABOVE; y += STEP)
                p.spawnParticle(Particle.DUST, x, y, z, 1, dust);
    }
}
