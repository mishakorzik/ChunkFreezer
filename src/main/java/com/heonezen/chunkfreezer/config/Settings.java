package com.heonezen.chunkfreezer.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Settings {
    public final String prefix;

    public final boolean entitiesProtectionEnabled;
    public final long checkPeriodTicks;
    public final int freezeThreshold;
    public final int unfreezeThreshold;
    public final boolean broadcast;

    public final int freezeType;

    public final Set<EntityType> ignoredEntityTypes;

    public final boolean barrierEnabled;
    public final double bounceMultiplier;
    public final boolean watchProjectiles;
    public final boolean watchItems;
    public final boolean watchVehicles;
    public final boolean returnPlayerDroppedItems;

    public final boolean overloadPurgeEnabled;
    public final Set<EntityType> overloadPurgeEntityTypes;

    public final boolean redstoneProtectionEnabled;
    public final int redstoneWindowTicks;
    public final int redstoneMaxEventsPerWindow;
    public final int redstoneRequiredWindows;
    public final int redstoneMinDistinctBlocks;
    public final int redstoneGraceSeconds;
    public final int redstoneFreezeSeconds;
    public final boolean redstoneMuteAfterFreeze;
    public final int redstoneMuteMaxSeconds; // 0 = until manual restart
    public final boolean redstoneUnmuteOnPlayerInteract;
    public final boolean redstoneUnmuteOnBorderPower;

    public Settings(FileConfiguration cfg) {
        String rawPrefix = cfg.getString("prefix", "&8[&e⚡&8] ");
        this.prefix = ChatColor.translateAlternateColorCodes('&', rawPrefix);

        this.entitiesProtectionEnabled = cfg.getBoolean("entities-protection.enabled", true);
        this.checkPeriodTicks = Math.max(60L, cfg.getLong("entities-protection.check-period-ticks", cfg.getLong("check-period-ticks", 100)));

        int ft = cfg.getInt("entities-protection.freeze-type", cfg.getInt("freeze-type", 2));
        if (ft < 0) ft = 0;
        if (ft > 2) ft = 2;
        this.freezeType = ft;

        int unfreeze = Math.max(0, cfg.getInt("entities-protection.unfreeze-threshold", cfg.getInt("unfreeze-threshold", 20)));
        int freeze = Math.max(1, cfg.getInt("entities-protection.freeze-threshold", cfg.getInt("freeze-threshold", 50)));
        if (freeze <= unfreeze) freeze = unfreeze + 1;

        this.unfreezeThreshold = unfreeze;
        this.freezeThreshold = freeze;

        this.broadcast = cfg.getBoolean("broadcast", true);

        List<String> ignoredRaw = cfg.getStringList("entities-protection.ignore-entity-types");
        if (ignoredRaw == null || ignoredRaw.isEmpty()) ignoredRaw = cfg.getStringList("ignore-entity-types");
        this.ignoredEntityTypes = parseEntityTypeSet(ignoredRaw);

        this.barrierEnabled = cfg.getBoolean("barrier.enabled", true);
        this.bounceMultiplier = clamp(cfg.getDouble("barrier.bounce-multiplier", 0.85), 0.05, 1.25);
        this.watchProjectiles = cfg.getBoolean("barrier.watch-projectiles", true);
        this.watchItems = cfg.getBoolean("barrier.watch-items", true);
        this.watchVehicles = cfg.getBoolean("barrier.watch-vehicles", true);
        this.returnPlayerDroppedItems = cfg.getBoolean("barrier.return-player-dropped-items", true);

        this.overloadPurgeEnabled = cfg.getBoolean("overload-purge.enabled", true);
        this.overloadPurgeEntityTypes = parseEntityTypeSet(cfg.getStringList("overload-purge.entity-types"));

        this.redstoneProtectionEnabled = cfg.getBoolean("redstone-protection.enabled", true);
        this.redstoneWindowTicks = clampInt(cfg.getInt("redstone-protection.window-ticks", 20), 1, 200);

        this.redstoneMaxEventsPerWindow = Math.max(1, cfg.getInt("redstone-protection.max-events-per-window", 350));
        this.redstoneRequiredWindows = clampInt(cfg.getInt("redstone-protection.required-windows", 4), 1, 60);

        this.redstoneMinDistinctBlocks = clampInt(cfg.getInt("redstone-protection.min-distinct-blocks", 20), 0, 1000);
        this.redstoneGraceSeconds = clampInt(cfg.getInt("redstone-protection.grace-seconds", 1), 0, 60);

        this.redstoneFreezeSeconds = clampInt(cfg.getInt("redstone-protection.freeze-seconds", 10), 1, 60);

        this.redstoneMuteAfterFreeze = cfg.getBoolean("redstone-protection.mute-after-freeze", true);
        this.redstoneMuteMaxSeconds = clampInt(cfg.getInt("redstone-protection.mute-max-seconds", 0), 0, 86400);

        this.redstoneUnmuteOnPlayerInteract = cfg.getBoolean("redstone-protection.unmute-on-player-interact", true);
        this.redstoneUnmuteOnBorderPower = cfg.getBoolean("redstone-protection.unmute-on-border-power", true);
    }

    public boolean isIgnored(EntityType type) { return ignoredEntityTypes.contains(type); }
    public boolean shouldPurgeProjectileType(EntityType type) { return overloadPurgeEntityTypes.contains(type); }

    private static Set<EntityType> parseEntityTypeSet(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();

        EnumSet<EntityType> set = EnumSet.noneOf(EntityType.class);
        for (String s : raw) {
            if (s == null) continue;
            String key = s.trim();
            if (key.isEmpty()) continue;

            try {
                set.add(EntityType.valueOf(key.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) { }
        }
        return Collections.unmodifiableSet(set);
    }

    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private static int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
