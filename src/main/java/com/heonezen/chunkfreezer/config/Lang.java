package com.heonezen.chunkfreezer.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Wraps language.yml - every player/console-facing message the plugin sends, including the
 *  /chunk help menu, so an admin can fully translate or restyle the plugin without touching any
 *  code. Mirrors how Settings reads config.yml: each call site supplies its own English default,
 *  used only if a key is missing from the admin's file (e.g. right after an update adds a new
 *  message). */
public final class Lang {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final FileConfiguration cfg;
    public final String prefix;

    public Lang(FileConfiguration cfg) {
        this.cfg = cfg;
        this.prefix = color(cfg.getString("prefix", "&8[&e⚡&8] "));
    }
    private static String color(String s) { return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s); }
    public String raw(String path, String fallback) { return color(cfg.getString(path, fallback)); }
    public List<String> rawList(String path, List<String> fallback) {
        List<String> src = cfg.isList(path) ? cfg.getStringList(path) : fallback;
        List<String> out = new ArrayList<>(src.size());
        for (String s : src) out.add(color(s));
        return out;
    }
    public String get(String path, String fallback, Object... kv) {
        String s = raw(path, fallback);
        if (kv.length < 2) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int end = s.charAt(i) == '%' ? s.indexOf('%', i + 1) : -1;
            String replacement = end < 0 ? null : findPlaceholder(s, i + 1, end, kv);
            if (replacement != null) { out.append(replacement); i = end + 1; }
            else { out.append(s.charAt(i)); i++; }
        }
        return out.toString();
    }
    private static String findPlaceholder(String s, int keyStart, int keyEnd, Object[] kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = (String) kv[i];
            if (key.length() == keyEnd - keyStart && s.regionMatches(keyStart, key, 0, key.length())) {
                return String.valueOf(kv[i + 1]);
            }
        }
        return null;
    }
    public Component component(String path, String fallback, Object... kv) {
        return LEGACY.deserialize(get(path, fallback, kv));
    }
    public Component prefixed(String path, String fallback, Object... kv) {
        return LEGACY.deserialize(prefix).append(component(path, fallback, kv));
    }
    /** The shared first half of both broadcast lines (entity overload and redstone loop) - see
     *  messages.broadcast-base in language.yml. %x%/%z% are the chunk's approximate world-space
     *  center. */
    public Component broadcastBase(World world, int cx, int cz) {
        return prefixed("messages.broadcast-base", "&7Chunk overloaded &e%world% &7xyz(&f%x%&7, &f~&7, &f%z%&7)", "world", world.getName(), "x", (cx << 4) + 8, "z", (cz << 4) + 8);
    }
}
