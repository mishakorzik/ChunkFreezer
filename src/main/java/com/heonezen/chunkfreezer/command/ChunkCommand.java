package com.heonezen.chunkfreezer.command;

import com.heonezen.chunkfreezer.ChunkFreezerPlugin;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class ChunkCommand implements CommandExecutor, TabCompleter {

    private final ChunkFreezerPlugin plugin;
    private volatile FrozenChunkManager manager;

    public ChunkCommand(ChunkFreezerPlugin plugin, FrozenChunkManager manager) {
        this.plugin = plugin; this.manager = manager;
    }

    public void setManager(FrozenChunkManager manager) { this.manager = manager; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("chunkfreezer.admin")) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) { sendHelp(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "list"   -> handleList(sender);
            case "reload" -> handleReload(sender);
            case "go"     -> handleGo(sender, args);
            default       -> sendHelp(sender);
        }
        return true;
    }
    private void handleList(CommandSender sender) {
        List<FrozenChunkManager.FrozenInfo> chunks = manager.getFrozenChunks();
        if (chunks.isEmpty()) { sender.sendMessage(Component.text("No frozen chunks.", NamedTextColor.GREEN)); return; }
        sender.sendMessage(Component.text("Frozen Chunks (" + chunks.size() + "):", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (FrozenChunkManager.FrozenInfo info : chunks) {
            World world = Bukkit.getWorld(info.worldId());
            String wName = world != null ? world.getName() : info.worldId().toString();
            int bx = (info.cx() << 4) + 8, bz = (info.cz() << 4) + 8;
            String causeStr = info.cause() == FrozenChunkManager.FreezeCause.REDSTONE ? "redstone loop" : "entity=" + info.entityCount();
            Component coords = Component.text("xyz(" + bx + ", ~, " + bz + ")", NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/chunk go " + wName + " " + bx + " " + bz))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text(" ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(wName + " ", NamedTextColor.WHITE))
                    .append(coords)
                    .append(Component.text(" (" + causeStr + ")", NamedTextColor.RED)));
        }
    }
    private void handleReload(CommandSender sender) {
        plugin.reloadPlugin();
        sender.sendMessage(Component.text("ChunkFreezer configuration reloaded.", NamedTextColor.GREEN));
    }
    private void handleGo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED)); return; }
        if (args.length < 4) { sender.sendMessage(Component.text("Usage: /chunk go <world> <x> <z>", NamedTextColor.RED)); return; }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) { sender.sendMessage(Component.text("World '" + args[1] + "' not found.", NamedTextColor.RED)); return; }
        int bx, bz;
        try { bx = Integer.parseInt(args[2]); bz = Integer.parseInt(args[3]); }
        catch (NumberFormatException ex) { sender.sendMessage(Component.text("Invalid coordinates.", NamedTextColor.RED)); return; }
        final int fx = bx, fz = bz;
        world.getChunkAtAsync(fx >> 4, fz >> 4).thenAccept(chunk -> {
            int sy = world.getHighestBlockYAt(fx, fz);
            p.teleportAsync(new org.bukkit.Location(world, fx + 0.5, sy + 1.0, fz + 0.5, p.getLocation().getYaw(), 0f));
        });
    }
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("ChunkFreezer Commands:", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text(" /chunk list", NamedTextColor.YELLOW).append(Component.text(" - Show frozen chunks", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /chunk reload", NamedTextColor.YELLOW).append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /chunk go <world> <x> <z>", NamedTextColor.YELLOW).append(Component.text(" - Teleport to chunk", NamedTextColor.GRAY)));
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("chunkfreezer.admin")) return Collections.emptyList();
        if (args.length == 1) return filterStartsWith(Arrays.asList("list", "reload", "go"), args[0]);
        if (args.length >= 2 && args[0].equalsIgnoreCase("go")) {
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
