package com.heonezen.chunkfreezer.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Method;

/** Small helpers for the bits of Folia (and Folia forks such as CanvasMC) that the public API
 *  doesn't expose. Everything here degrades gracefully: on plain Paper/Spigot, or if a future
 *  Folia build renames an internal, the caller simply gets a sensible default. */
public final class FoliaSupport {

    private FoliaSupport() {}

    /** Folia's default region section size: 2^4 = 16x16 chunks per section. */
    public static final int DEFAULT_SECTION_SHIFT = 4;

    private static final boolean FOLIA = detectFolia();

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Whether the server ticks the world in independent regions (Folia or a fork of it). */
    public static boolean isFolia() { return FOLIA; }

    /** The region section chunk shift ("grid-exponent") the server actually runs with: a region
     *  is always built out of whole N x N chunk sections where N = 1 &lt;&lt; shift, so region
     *  borders can only ever lie on that grid.
     *
     *  <p>Resolution order: Folia's own {@code TickRegions#getRegionChunkShift()} (exact, already
     *  validated by the server), then {@code threaded-regions.grid-exponent} from
     *  config/paper-global.yml, then the Folia default of 4. */
    public static int regionSectionChunkShift() {
        if (!FOLIA) return DEFAULT_SECTION_SHIFT;
        try {
            Class<?> tickRegions = Class.forName("io.papermc.paper.threadedregions.TickRegions");
            Method m = tickRegions.getMethod("getRegionChunkShift");
            Object v = m.invoke(null);
            if (v instanceof Integer i && i >= 0 && i <= 12) return i;
        } catch (Throwable ignored) {
            // internal API moved/renamed - fall through to the config file
        }
        try {
            File f = new File("config", "paper-global.yml");
            if (f.isFile()) {
                int g = YamlConfiguration.loadConfiguration(f).getInt("threaded-regions.grid-exponent", -1);
                if (g >= 0 && g <= 12) return g;
            }
        } catch (Throwable ignored) {
            // unreadable config - use the default
        }
        return DEFAULT_SECTION_SHIFT;
    }
}
