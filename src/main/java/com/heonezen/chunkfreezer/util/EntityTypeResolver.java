package com.heonezen.chunkfreezer.util;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;

import java.util.Map;

/** Version-agnostic Material -> EntityType resolver for every "placeable" item ChunkFreezer needs
 *  to recognize - boats/rafts, minecarts, mob buckets, spawn eggs, and a handful of standalone
 *  decorations - kept in exactly one place rather than duplicated (and inevitably drifting out of
 *  sync) as a hardcoded switch statement across multiple classes. See resolvePlaceable() for the
 *  full resolution strategy. */
public final class EntityTypeResolver {

    private EntityTypeResolver() {}

    public static boolean isSpawnEgg(Material m) {
        return m.name().endsWith("_SPAWN_EGG");
    }
    private static final Map<Material, EntityType> DECORATIONS = Map.of(
            Material.ARMOR_STAND,     EntityType.ARMOR_STAND,
            Material.END_CRYSTAL,     EntityType.END_CRYSTAL,
            Material.LEAD,            EntityType.LEASH_KNOT,
            Material.FIREWORK_ROCKET, EntityType.FIREWORK_ROCKET
    );
    private static boolean isKind(EntityType t, Class<?> marker) {
        Class<? extends Entity> clazz = t.getEntityClass();
        return clazz != null && marker.isAssignableFrom(clazz);
    }
    private static EntityType byEntityTypeName(String name) {
        try { return EntityType.valueOf(name); } catch (IllegalArgumentException notAnEntityName) { return null; }
    }
    public static EntityType resolvePlaceable(Material m) {
        EntityType direct = byEntityTypeName(m.name());
        if (direct != null && (isKind(direct, Vehicle.class) || isKind(direct, Hanging.class))) return direct;
        if (m.name().endsWith("_BUCKET")) {
            EntityType bucketed = byEntityTypeName(m.name().substring(0, m.name().length() - "_BUCKET".length()));
            if (bucketed != null) return bucketed;
        }
        return DECORATIONS.get(m);
    }
    public static boolean isPlaceable(ItemStack stack) {
        Material m = stack.getType();
        return isSpawnEgg(m) || resolvePlaceable(m) != null;
    }
    public static EntityType resolveWithMeta(ItemStack stack) {
        Material m = stack.getType();
        if (isSpawnEgg(m) && stack.getItemMeta() instanceof SpawnEggMeta meta) {
            try {
                EntityType t = meta.getSpawnedType();
                if (t != null) return t;
            } catch (UnsupportedOperationException ignored) {
                /** Defensive fallback only - isSpawnEgg() above should already prevent this in
                 *  practice. Falls through to resolvePlaceable below, which returns null for a
                 *  spawn-egg material anyway (it isn't a boat/minecart/bucket/decoration). */
            }
        }
        return resolvePlaceable(m);
    }
}
