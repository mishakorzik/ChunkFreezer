package com.heonezen.chunkfreezer.util;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;

import java.util.Map;
import java.util.Set;

/** Version-agnostic Material -> EntityType resolver for every "placeable" item ChunkFreezer needs
 *  to recognize - boats/rafts, minecarts, mob buckets, spawn eggs, and a handful of standalone
 *  decorations - kept in exactly one place rather than duplicated (and inevitably drifting out of
 *  sync) as a hardcoded switch statement across multiple classes. See resolvePlaceable() for the
 *  full resolution strategy. */
public final class EntityTypeResolver {

    private EntityTypeResolver() {}

    /** Whether this material is a spawn egg - checked by name (every vanilla spawn egg is named
     *  <mob>_SPAWN_EGG) rather than via `stack.getItemMeta() instanceof SpawnEggMeta`. On current
     *  Paper (seen on 1.21.11), that instanceof check can also match items that are NOT actually
     *  spawn eggs - the underlying ItemMeta implementation class is shared more broadly than the
     *  interface name suggests - and calling SpawnEggMeta#getSpawnedType() on one of those throws
     *  UnsupportedOperationException ("Must check item type to get spawned type") instead of
     *  returning something usable. Checking the material name first avoids ever reaching that call
     *  for anything that isn't genuinely a spawn egg. */
    public static boolean isSpawnEgg(Material m) {
        return m.name().endsWith("_SPAWN_EGG");
    }
    /** Materials with no vanilla naming convention or shared entity interface this can derive an
     *  EntityType from: the one true name mismatch (LEAD item -> LEASH_KNOT entity) plus three
     *  single-purpose entities (armor stands, end crystals, firework rockets) that don't happen to
     *  implement Vehicle or Hanging like everything else this resolves. This list has barely
     *  changed in years and is not where new-version breakage happens; buckets, boats, minecarts,
     *  and item frames are, which is exactly why those are all resolved generically instead. */
    private static final Map<Material, EntityType> DECORATIONS = Map.of(
            Material.ARMOR_STAND,     EntityType.ARMOR_STAND,
            Material.END_CRYSTAL,     EntityType.END_CRYSTAL,
            Material.LEAD,            EntityType.LEASH_KNOT,
            Material.FIREWORK_ROCKET, EntityType.FIREWORK_ROCKET
    );
    /** Whether an EntityType's underlying entity class is this general "kind" of thing - checked
     *  through Bukkit's own class hierarchy (Vehicle, Hanging, ...) rather than a hardcoded list of
     *  specific types, so a brand new implementor added in a future Minecraft version is included
     *  automatically. False for an EntityType with no registered entity class at all (a handful of
     *  types, e.g. UNKNOWN, have none). */
    private static boolean isKind(EntityType t, Class<?> marker) {
        Class<? extends Entity> clazz = t.getEntityClass();
        return clazz != null && marker.isAssignableFrom(clazz);
    }
    private static EntityType byEntityTypeName(String name) {
        try { return EntityType.valueOf(name); } catch (IllegalArgumentException notAnEntityName) { return null; }
    }
    /** Item-name suffix shared by every bucket item. */
    private static final String BUCKET_SUFFIX = "_BUCKET";
    /** Buckets whose contents are a liquid/powder the player empties into the world (water, lava,
     *  powder snow) - as opposed to a mob bucket, which releases a living creature. */
    private static final Set<String> LIQUID_BUCKET_BASES = Set.of("WATER", "LAVA", "POWDER_SNOW");
    /** The one bucket that never places anything into the world: the milk bucket is drunk by the
     *  player, so it must never be treated as a "placing" item (blocking it would stop players
     *  from drinking milk next to a frozen chunk, which nothing in this plugin asks for). */
    private static final Set<String> NON_PLACING_BUCKETS = Set.of("MILK_BUCKET");
    /** The item name a bucket is built from, e.g. COD_BUCKET -> COD. */
    private static String bucketBaseName(Material m) {
        String n = m.name();
        return n.substring(0, n.length() - BUCKET_SUFFIX.length());
    }
    /** Whether this item is a bucket whose contents get placed into the world when used - water,
     *  lava, powder snow, or a mob (fish, axolotl, tadpole, ...). The milk bucket is excluded: it
     *  is consumed by the player and never places anything. */
    public static boolean isPlacingBucket(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.endsWith(BUCKET_SUFFIX) && !NON_PLACING_BUCKETS.contains(n);
    }
    /** Whether this item is a bucket that releases a living creature (a "mob bucket"). Unlike
     *  {@link #resolvePlaceable(Material)} this never returns false for an unknown bucket: a mob
     *  bucket added by a future Minecraft version - whose entity type this plugin's API doesn't
     *  know about yet - is still recognized as a mob bucket here, so it stays blocked in a frozen
     *  chunk instead of quietly becoming a hole in the protection. */
    public static boolean isMobBucket(Material m) {
        if (!isPlacingBucket(m)) return false;
        return !LIQUID_BUCKET_BASES.contains(bucketBaseName(m));
    }
    /** Version-agnostic resolver covering every boat/raft, minecart, mob bucket, and hanging
     *  decoration: Minecraft keeps each one's Material and EntityType name in lockstep (OAK_BOAT/
     *  OAK_BOAT, BAMBOO_RAFT/BAMBOO_RAFT, CHEST_MINECART/CHEST_MINECART, ITEM_FRAME/ITEM_FRAME,
     *  COD_BUCKET -> COD once "_BUCKET" is stripped) for every variant it has ever added, and the
     *  resulting entity always implements Vehicle (boats, rafts, minecarts) or Hanging (item
     *  frames, paintings) - so resolving by name plus that interface check means a brand new wood
     *  type, minecart variant, bucketable mob, or hanging decoration in a future Minecraft version
     *  is recognized with no plugin update, unlike a hardcoded per-item list that quietly stops
     *  recognizing anything added after it was written. Falls back to the small fixed DECORATIONS
     *  map for the few materials that don't fit any of those shapes. Deliberately does NOT match on
     *  material name alone outside them - a thrown item like a snowball or wind charge shares its
     *  name with an EntityType too, but throwing one isn't "placing an entity" for this resolver's
     *  purposes; it's a projectile, and neither Snowball nor WindCharge implements Vehicle or
     *  Hanging, so they're correctly never matched here. Returns null for anything not recognized
     *  as one of these categories at all. */
    public static EntityType resolvePlaceable(Material m) {
        if (m == null) return null;
        EntityType direct = byEntityTypeName(m.name());
        if (direct != null && (isKind(direct, Vehicle.class) || isKind(direct, Hanging.class))) return direct;
        if (isMobBucket(m)) {
            EntityType bucketed = byEntityTypeName(bucketBaseName(m));
            if (bucketed != null) return bucketed;
        }
        return DECORATIONS.get(m);
    }
    /** Cheap, NBT-free check for "would using this item place/spawn an entity" - safe to call on
     *  every interaction, since it never touches the item's meta (see resolveWithMeta for the
     *  slower, meta-aware version needed for spawn eggs specifically).
     *
     *  <p>Buckets are included on purpose, and not only the mob ones: water, lava and powder snow
     *  are placed into the world too, and a frozen chunk must refuse those as well. Any
     *  {@code *_BUCKET} item counts, so a bucket added by a future Minecraft version is covered
     *  without a plugin update; the milk bucket is the single exception (it is drunk, not
     *  placed). */
    public static boolean isPlaceable(ItemStack stack) {
        Material m = stack.getType();
        return isSpawnEgg(m) || resolvePlaceable(m) != null || isPlacingBucket(m);
    }
    /** Best-effort guess at which EntityType this item would create if used right now - reads the
     *  item's meta for spawn eggs, so unlike isPlaceable() this isn't free; only call it once
     *  actually needed (e.g. after already confirming the interaction is inside a frozen chunk).
     *  Returns null for anything not recognized at all. */
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
