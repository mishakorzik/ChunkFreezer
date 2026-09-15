package com.heonezen.chunkfreezer.command;

import com.heonezen.chunkfreezer.ChunkFreezerPlugin;
import com.heonezen.chunkfreezer.config.Lang;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import com.heonezen.chunkfreezer.listener.BarrierListener;
import com.heonezen.chunkfreezer.monitor.ChunkMonitor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class ChunkCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    /** Only ever rendered if language.yml hasn't been wired up yet (see msg()/prefixedMsg()) -
     *  defensive only, never happens once the plugin has finished enabling. */
    private static final List<String> HELP_LINES_FALLBACK = List.of(
            "&e /chunk list&7 - Show frozen chunks",
            "&e /chunk go <world> <x> <z>&7 - Teleport to a chunk",
            "&e /chunk despawn <world> <x> <z>&7 - Instantly despawn entities in that chunk (skips instant-despawn.ignore-entity-types)",
            "&e /chunk reload&7 - Reload configuration");

    private final ChunkFreezerPlugin plugin;
    private volatile FrozenChunkManager manager;
    private volatile BarrierListener barrierListener;
    private volatile ChunkMonitor chunkMonitor;
    private volatile Lang lang;

    public ChunkCommand(ChunkFreezerPlugin plugin, FrozenChunkManager manager) {
        this.plugin = plugin; this.manager = manager;
    }

    public void setManager(FrozenChunkManager manager) { this.manager = manager; }
    public void setBarrierListener(BarrierListener barrierListener) { this.barrierListener = barrierListener; }
    public void setChunkMonitor(ChunkMonitor chunkMonitor) { this.chunkMonitor = chunkMonitor; }
    public void setLang(Lang lang) { this.lang = lang; }
    /** language.yml-backed message, auto-prefixed - for standalone status/error messages. Falls
     *  back to a plain rendering of fallback if language.yml hasn't been wired up yet (defensive
     *  only - never happens once the plugin has finished enabling). */
    private Component prefixedMsg(String path, String fallback, Object... kv) {
        Lang l = lang;
        return l != null ? l.prefixed(path, fallback, kv) : LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', fallback));
    }
    /** Same as prefixedMsg(), without the prefix - for list bullets and help sub-lines shown under
     *  their own already-prefixed header. */
    private Component msg(String path, String fallback, Object... kv) {
        Lang l = lang;
        return l != null ? l.component(path, fallback, kv) : LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', fallback));
    }
    private String rawStr(String path, String fallback, Object... kv) {
        Lang l = lang;
        return l != null ? l.get(path, fallback, kv) : fallback;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("chunkfreezer.admin")) {
            sender.sendMessage(prefixedMsg("commands.no-permission", "&cYou do not have permission to use this command."));
            return true;
        }
        if (args.length == 0) { sendHelp(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "list"    -> handleList(sender);
            case "reload"  -> handleReload(sender);
            case "go"      -> handleGo(sender, args);
            case "despawn" -> handleDespawn(sender, args);
            default        -> sendHelp(sender);
        }
        return true;
    }
    private void handleList(CommandSender sender) {
        List<FrozenChunkManager.FrozenInfo> chunks = manager.getFrozenChunks();
        if (chunks.isEmpty()) { sender.sendMessage(prefixedMsg("commands.list.empty", "&aNo frozen chunks.")); return; }
        sender.sendMessage(prefixedMsg("commands.list.header", "&6&lFrozen Chunks (%count%):", "count", chunks.size()));
        for (FrozenChunkManager.FrozenInfo info : chunks) {
            World world = Bukkit.getWorld(info.worldId());
            String wName = world != null ? world.getName() : info.worldId().toString();
            int bx = (info.cx() << 4) + 8, bz = (info.cz() << 4) + 8;
            String causeStr = info.cause() == FrozenChunkManager.FreezeCause.REDSTONE
                    ? rawStr("commands.list.cause-redstone", "redstone loop")
                    : rawStr("commands.list.cause-entity", "entity=%count%", "count", info.entityCount());
            Component line = msg("commands.list.entry", "&8 » &f%world% &exyz(%x%, ~, %z%)&c (%cause%)",
                    "world", wName, "x", bx, "z", bz, "cause", causeStr)
                    .clickEvent(ClickEvent.runCommand("/chunk go " + wName + " " + bx + " " + bz))
                    .hoverEvent(HoverEvent.showText(msg("commands.list.entry-hover", "&7Click to teleport")));
            sender.sendMessage(line);
        }
    }
    private void handleReload(CommandSender sender) {
        plugin.reloadPlugin();
        sender.sendMessage(prefixedMsg("commands.reload.success", "&aChunkFreezer configuration reloaded."));
    }
    private void handleGo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(prefixedMsg("commands.go.players-only", "&cOnly players can use this command.")); return; }
        World world = parseWorld(sender, "go", args);
        if (world == null) return;
        int[] xz = parseXZ(sender, "go", args);
        if (xz == null) return;
        final int fx = xz[0], fz = xz[1];
        final float yaw = p.getLocation().getYaw();
        final BarrierListener bl = barrierListener;
        world.getChunkAtAsync(fx >> 4, fz >> 4).thenAccept(chunk -> {
            int sy = world.getHighestBlockYAt(fx, fz);
            if (bl != null) bl.allowNextTeleport(p.getUniqueId());
            p.teleportAsync(new org.bukkit.Location(world, fx + 0.5, sy + 1.0, fz + 0.5, yaw, 0f));
        });
    }
    private void handleDespawn(CommandSender sender, String[] args) {
        World world = parseWorld(sender, "despawn", args);
        if (world == null) return;
        int[] xz = parseXZ(sender, "despawn", args);
        if (xz == null) return;
        final int cx = xz[0] >> 4, cz = xz[1] >> 4;
        final ChunkMonitor cm = chunkMonitor;
        if (cm == null) {
            sender.sendMessage(prefixedMsg("commands.despawn.not-ready", "&cChunkFreezer isn't fully loaded yet, try again in a moment."));
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
            if (!world.isChunkLoaded(cx, cz)) {
                sender.sendMessage(prefixedMsg("commands.despawn.chunk-not-loaded", "&cChunk %x%, %z% in %world% isn't loaded.",
                        "x", cx, "z", cz, "world", world.getName()));
                return;
            }
            int removed = cm.instantDespawnChunk(world, cx, cz);
            String entityWord = removed == 1
                    ? rawStr("commands.despawn.entity-singular", "entity")
                    : rawStr("commands.despawn.entity-plural", "entities");
            sender.sendMessage(prefixedMsg("commands.despawn.success",
                    "&aInstantly despawned %removed% %entity% in chunk %x%, %z% (%world%), skipping anything listed under instant-despawn.ignore-entity-types.",
                    "removed", removed, "entity", entityWord, "x", cx, "z", cz, "world", world.getName()));
        });
    }
    /** cmdKey is "go" or "despawn" - picks which of the two commands.<cmdKey> sections in
     *  language.yml supplies the usage/world-not-found text below. */
    private World parseWorld(CommandSender sender, String cmdKey, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(prefixedMsg("commands." + cmdKey + ".usage", "&cUsage: /chunk " + cmdKey + " <world> <x> <z>"));
            return null;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) sender.sendMessage(prefixedMsg("commands." + cmdKey + ".world-not-found", "&cWorld '%world%' not found.", "world", args[1]));
        return world;
    }
    private int[] parseXZ(CommandSender sender, String cmdKey, String[] args) {
        try { return new int[]{ Integer.parseInt(args[2]), Integer.parseInt(args[3]) }; }
        catch (NumberFormatException ex) {
            sender.sendMessage(prefixedMsg("commands." + cmdKey + ".invalid-coordinates", "&cInvalid coordinates."));
            return null;
        }
    }
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(prefixedMsg("commands.help.header", "&6&lChunkFreezer Commands:"));
        Lang l = lang;
        if (l != null) {
            for (String line : l.rawList("commands.help.lines", HELP_LINES_FALLBACK)) sender.sendMessage(LEGACY.deserialize(line));
        } else {
            for (String line : HELP_LINES_FALLBACK) sender.sendMessage(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', line)));
        }
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("chunkfreezer.admin")) return Collections.emptyList();
        if (args.length == 1) return filterStartsWith(Arrays.asList("list", "reload", "go", "despawn"), args[0]);
        boolean isCoordCommand = args[0].equalsIgnoreCase("go") || args[0].equalsIgnoreCase("despawn");
        if (args.length >= 2 && isCoordCommand) {
            if (args.length == 2) {
                List<String> worlds = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) worlds.add(w.getName());
                return filterStartsWith(worlds, args[1]);
            }
            if (args.length == 3) {
                List<String> xs = new ArrayList<>();
                for (FrozenChunkManager.FrozenInfo info : manager.getFrozenChunks()) {
                    World w = Bukkit.getWorld(info.worldId());
                    if (w != null && w.getName().equalsIgnoreCase(args[1])) {
                        xs.add(String.valueOf((info.cx() << 4) + 8));
                    }
                }
                return filterStartsWith(xs, args[2]);
            }
            if (args.length == 4) {
                List<String> zs = new ArrayList<>();
                for (FrozenChunkManager.FrozenInfo info : manager.getFrozenChunks()) {
                    World w = Bukkit.getWorld(info.worldId());
                    if (w == null || !w.getName().equalsIgnoreCase(args[1])) continue;
                    if (String.valueOf((info.cx() << 4) + 8).equals(args[2])) {
                        zs.add(String.valueOf((info.cz() << 4) + 8));
                    }
                }
                return filterStartsWith(zs, args[3]);
            }
        }
        return Collections.emptyList();
    }
    private List<String> filterStartsWith(List<String> options, String prefix) {
        if (prefix.isEmpty()) return options;
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(lower)) result.add(o);
        return result;
    }
}
