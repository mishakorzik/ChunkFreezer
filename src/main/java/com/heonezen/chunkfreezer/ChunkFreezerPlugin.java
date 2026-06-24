package com.heonezen.chunkfreezer;

import com.heonezen.chunkfreezer.command.ChunkCommand;
import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import com.heonezen.chunkfreezer.listener.BarrierListener;
import com.heonezen.chunkfreezer.listener.ProtectionListener;
import com.heonezen.chunkfreezer.monitor.ChunkMonitor;
import com.heonezen.chunkfreezer.redstone.RedstoneLoopProtector;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkFreezerPlugin extends JavaPlugin {

    private Settings settings;
    private FrozenChunkManager manager;
    private ChunkMonitor chunkMonitor;
    private BarrierListener barrierListener;
    private ChunkCommand chunkCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chunkCommand = new ChunkCommand(this, null);
        registerCommand();
        initComponents();
    }
    private void initComponents() {
        settings = new Settings(getConfig());
        manager  = new FrozenChunkManager(this, settings);
        if (chunkCommand != null) chunkCommand.setManager(manager);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this, settings, manager), this);
        barrierListener = new BarrierListener(this, settings, manager);
        Bukkit.getPluginManager().registerEvents(barrierListener, this);
        barrierListener.startParticleTasks();
        if (settings.redstoneProtectionEnabled)
            Bukkit.getPluginManager().registerEvents(new RedstoneLoopProtector(this, settings, manager), this);
        if (settings.entitiesProtectionEnabled) {
            chunkMonitor = new ChunkMonitor(this, settings, manager);
            Bukkit.getPluginManager().registerEvents(chunkMonitor, this);
            chunkMonitor.runStartupScan();
        } else {
            chunkMonitor = null;
        }
    }
    private void registerCommand() {
        PluginCommand cmd = getCommand("chunk");
        if (cmd != null) { cmd.setExecutor(chunkCommand); cmd.setTabCompleter(chunkCommand); }
    }
    public void reloadPlugin() {
        if (chunkMonitor != null) { chunkMonitor.shutdown(); chunkMonitor = null; }
        if (barrierListener != null) { barrierListener.shutdown(); barrierListener = null; }
        if (manager != null) { manager.shutdown(); manager = null; }
        HandlerList.unregisterAll(this);
        registerCommand();
        reloadConfig();
        initComponents();
    }
    @Override
    public void onDisable() {
        if (chunkMonitor != null) chunkMonitor.shutdown();
        if (barrierListener != null) barrierListener.shutdown();
        if (manager != null) manager.shutdown();
    }
}
