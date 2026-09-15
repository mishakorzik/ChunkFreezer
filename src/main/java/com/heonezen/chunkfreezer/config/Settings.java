package com.heonezen.chunkfreezer.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.util.*;

public final class Settings {

    public final boolean entitiesProtectionEnabled;
    public final long checkPeriodTicks;
    public final int freezeThreshold;
    public final int unfreezeThreshold;
    public final int entitiesGraceSeconds;
    public final boolean broadcast;
    public final Set<EntityType> ignoredEntityTypes;

    public final boolean barrierEnabled;
    public final boolean watchProjectilesEnabled;
    public final boolean watchItemsEnabled;
    public final boolean returnPlayerDroppedItem;
    public final boolean notifyWhenInFrozen;
    public final boolean watchLiquids;
    public final boolean watchPlayersEnabled;
    public final double  playerBounceMultiplier;
    public final double  damageOnEntry;
    public final int     particlesOnEntry;
    public final boolean watchEntitiesEnabled;
    public final double  entityBounceMultiplier;

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
    public final int redstoneMuteMaxSeconds;
    public final boolean redstoneUnmuteOnPlayerInteract;
    public final boolean redstoneUnmuteOnBorderPower;

    public final int destroyThreshold;
    public final boolean allowCreeperExplosions;

    public final boolean instantDespawnEnabled;
    public final int instantDespawnRadius;
    public final boolean instantDespawnNamedEntity;
    public final Set<EntityType> instantDespawnIgnoreTypes;

    public final Set<EntityType> frozenChunkIgnoredEntityTypes;
    public final Set<Material> frozenChunkIgnoredBlockTypes;

    // Advanced zone: see the "advanced" section in config.yml for warnings before touching these.
    public final Set<Material> extraEntityPlacingItems;
    public final Set<PlayerTeleportEvent.TeleportCause> blockedTeleportCauses;

    public final boolean regionBorderParticlesEnabled;
    public final double  regionBorderParticleDistance;

    public Settings(FileConfiguration cfg, File configFile) {
        this.entitiesProtectionEnabled = cfg.getBoolean("entities-protection.enabled", true);
        this.checkPeriodTicks = Math.max(1L, Math.min(100L, cfg.getLong("entities-protection.check-period-ticks", 40L)));

        int unfreeze = Math.max(0, cfg.getInt("entities-protection.unfreeze-threshold", 50));
        int freeze   = Math.max(1, cfg.getInt("entities-protection.freeze-threshold", 100));
        if (freeze <= unfreeze) freeze = unfreeze + 1;
        this.unfreezeThreshold = unfreeze;
        this.freezeThreshold = freeze;
        this.entitiesGraceSeconds = clampInt(cfg.getInt("entities-protection.grace-seconds", 3), 0, 60);
        this.destroyThreshold = Math.max(0, cfg.getInt("entities-protection.destroy-threshold", 300));
        this.allowCreeperExplosions = cfg.getBoolean("entities-protection.allow-creeper-explosions", true);
        this.broadcast = cfg.getBoolean("broadcast", true);

        List<String> ignoredRaw = cfg.getStringList("entities-protection.ignore-entity-types");
        if (ignoredRaw.isEmpty()) ignoredRaw = cfg.getStringList("ignore-entity-types");
        this.ignoredEntityTypes = parseEntityTypes(ignoredRaw);

        this.barrierEnabled                 = cfg.getBoolean("barrier.enabled", true);
        this.watchProjectilesEnabled        = cfg.getBoolean("barrier.watch-projectiles.enabled", true);
        this.watchItemsEnabled              = cfg.getBoolean("barrier.watch-items.enabled", true);
        this.returnPlayerDroppedItem        = cfg.getBoolean("barrier.watch-items.return-player-dropped-item", true);
        this.notifyWhenInFrozen             = cfg.getBoolean("barrier.watch-players.notify-when-in-frozen", true);
        this.watchLiquids                   = cfg.getBoolean("barrier.watch-liquids.enabled", true);
        this.watchPlayersEnabled            = cfg.getBoolean("barrier.watch-players.enabled", false);
        this.playerBounceMultiplier         = clamp(cfg.getDouble("barrier.watch-players.bounce-multiplier", 0.75), 0.05, 1.25);
        this.damageOnEntry                  = clamp(cfg.getDouble("barrier.watch-players.damage-on-entry", 1.0), 0.0, 20.0);

        int rawParticles                    = cfg.getInt("barrier.watch-players.particles-on-entry", 4);
        this.particlesOnEntry               = rawParticles <= 0 ? 0 : Math.max(2, Math.min(16, rawParticles));
        this.watchEntitiesEnabled           = cfg.getBoolean("barrier.watch-entities.enabled", true);
        this.entityBounceMultiplier         = clamp(cfg.getDouble("barrier.watch-entities.bounce-multiplier", 0.75), 0.05, 1.25);

        this.overloadPurgeEnabled           = cfg.getBoolean("overload-purge.enabled", true);
        this.overloadPurgeEntityTypes       = parseEntityTypes(cfg.getStringList("overload-purge.entity-types"));

        this.redstoneProtectionEnabled      = cfg.getBoolean("redstone-protection.enabled", true);
        this.redstoneWindowTicks            = clampInt(cfg.getInt("redstone-protection.window-ticks", 20), 1, 200);
        this.redstoneMaxEventsPerWindow     = Math.max(1, cfg.getInt("redstone-protection.max-events-per-window", 350));
        this.redstoneRequiredWindows        = clampInt(cfg.getInt("redstone-protection.required-windows", 4), 1, 60);
        this.redstoneMinDistinctBlocks      = clampInt(cfg.getInt("redstone-protection.min-distinct-blocks", 20), 0, 1000);
        this.redstoneGraceSeconds           = clampInt(cfg.getInt("redstone-protection.grace-seconds", 1), 0, 60);
        this.redstoneFreezeSeconds          = clampInt(cfg.getInt("redstone-protection.freeze-seconds", 10), 1, 60);
        this.redstoneMuteAfterFreeze        = cfg.getBoolean("redstone-protection.mute-after-freeze", true);
        this.redstoneMuteMaxSeconds         = clampInt(cfg.getInt("redstone-protection.mute-max-seconds", 0), 0, 86400);
        this.redstoneUnmuteOnPlayerInteract = cfg.getBoolean("redstone-protection.unmute-on-player-interact", true);
        this.redstoneUnmuteOnBorderPower    = cfg.getBoolean("redstone-protection.unmute-on-border-power", true);

        this.instantDespawnEnabled          = cfg.getBoolean("instant-despawn.enabled", true);
        this.instantDespawnRadius           = Math.max(1, cfg.getInt("instant-despawn.radius", 48));
        this.instantDespawnNamedEntity      = cfg.getBoolean("instant-despawn.named-entity", false);
        this.instantDespawnIgnoreTypes      = parseEntityTypes(cfg.getStringList("instant-despawn.ignore-entity-types"));

        this.frozenChunkIgnoredEntityTypes  = parseEntityTypes(cfg.getStringList("frozen-chunk.ignore-entity-types"));
        this.frozenChunkIgnoredBlockTypes   = parseMaterials(cfg.getStringList("frozen-chunk.ignore-blockstate-types"));

        /** Read directly from the file on disk rather than cfg.getStringList(): JavaPlugin#getConfig()
         *  attaches the plugin jar's bundled config.yml as a fallback "defaults" layer, so an entry
         *  missing from the server's own file would otherwise silently resolve to whatever ships
         *  inside the jar instead of being empty. These two lists should be empty whenever the
         *  server's own config doesn't list anything for them - nothing more, nothing implicit. */
        List<String> rawExtraItems = readOwnStringList(configFile, "advanced.extra-entity-placing-items");
        List<String> rawBlockedCauses = readOwnStringList(configFile, "advanced.blocked-teleport-causes");
        this.extraEntityPlacingItems = parseMaterials(rawExtraItems);
        this.blockedTeleportCauses = parseTeleportCauses(rawBlockedCauses);

        this.regionBorderParticlesEnabled = cfg.getBoolean("region-border-particles.enabled", true);
        this.regionBorderParticleDistance = clamp(cfg.getDouble("region-border-particles.distance", 4.0), 2.0, 16.0);
    }
    /** Reads a string list straight from the config file on disk, with no default-configuration
     *  layer attached, so a missing key resolves to an empty list instead of falling back to the
     *  plugin jar's bundled config.yml. */
    private static List<String> readOwnStringList(File configFile, String path) {
        if (configFile == null || !configFile.isFile()) return Collections.emptyList();
        try {
            return YamlConfiguration.loadConfiguration(configFile).getStringList(path);
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    public boolean isIgnored(EntityType t) { return ignoredEntityTypes.contains(t); }
    public boolean shouldPurgeProjectileType(EntityType t) { return overloadPurgeEntityTypes.contains(t); }
    public boolean isInstantDespawnIgnored(EntityType t) { return instantDespawnIgnoreTypes.contains(t); }
    /** Whether this entity type is on the frozen-chunk.ignore-entity-types whitelist - if so, it's
     *  allowed to spawn/be placed inside a frozen chunk despite the barrier normally blocking new
     *  entities there. Covers both a direct spawn and being created via a placing item (e.g. a
     *  COD_BUCKET spawning a COD) - both paths check the same set of entity types. */
    public boolean isFrozenChunkIgnored(EntityType t) { return frozenChunkIgnoredEntityTypes.contains(t); }
    /** Whether this block type is on the frozen-chunk.ignore-blockstate-types whitelist - if so,
     *  the fire-related protections in ProtectionListener (ignite/burn/spread) skip it. */
    public boolean isFrozenChunkBlockIgnored(Material m) { return frozenChunkIgnoredBlockTypes.contains(m); }
    /** Counts non-player, non-ignored entities. Explicitly skips anything already dead/invalid
     *  (e.g. just removed a moment earlier in the same tick, such as by overload-purge) - without
     *  this, re-counting the same already-fetched Entity[] right after removing some of them would
     *  still count the just-removed ones, since remove() doesn't shrink a previously-fetched array,
     *  it only flips the removed entity's own isDead()/isValid() state. */
    public int countNonIgnoredEntities(org.bukkit.entity.Entity[] entities) {
        int c = 0;
        for (org.bukkit.entity.Entity e : entities) {
            if (e == null || e.isDead() || !e.isValid()) continue;
            if (e instanceof org.bukkit.entity.Player) continue;
            if (!isIgnored(e.getType())) c++;
        }
        return c;
    }
    private static Set<EntityType> parseEntityTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        EnumSet<EntityType> set = EnumSet.noneOf(EntityType.class);
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try { set.add(EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        return Collections.unmodifiableSet(set);
    }
    private static Set<Material> parseMaterials(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try { set.add(Material.valueOf(s.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        return Collections.unmodifiableSet(set);
    }
    @SuppressWarnings("deprecation")
    private static Set<PlayerTeleportEvent.TeleportCause> parseTeleportCauses(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        EnumSet<PlayerTeleportEvent.TeleportCause> set = EnumSet.noneOf(PlayerTeleportEvent.TeleportCause.class);
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try { set.add(PlayerTeleportEvent.TeleportCause.valueOf(s.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        return Collections.unmodifiableSet(set);
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
