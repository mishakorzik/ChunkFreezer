package com.heonezen.chunkfreezer;

import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import com.heonezen.chunkfreezer.listener.BarrierListener;
import com.heonezen.chunkfreezer.listener.ProtectionListener;
import com.heonezen.chunkfreezer.monitor.ChunkMonitor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import com.heonezen.chunkfreezer.redstone.RedstoneLoopProtector;

public final class ChunkFreezerPlugin extends JavaPlugin {

    private Settings settings;
    private FrozenChunkManager frozenChunkManager;
    private ChunkMonitor chunkMonitor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.settings = new Settings(getConfig());
        this.frozenChunkManager = new FrozenChunkManager(this, settings);

        if (settings.freezeType != 0) {
            Bukkit.getPluginManager().registerEvents(new ProtectionListener(this, settings, frozenChunkManager), this);
        }

        if (settings.barrierEnabled) {
            Bukkit.getPluginManager().registerEvents(new BarrierListener(this, settings, frozenChunkManager), this);
        }

        if (settings.redstoneProtectionEnabled && settings.freezeType != 0) {
            Bukkit.getPluginManager().registerEvents(new RedstoneLoopProtector(this, settings, frozenChunkManager), this);
        }

        if (settings.entitiesProtectionEnabled) {
            this.chunkMonitor = new ChunkMonitor(this, settings, frozenChunkManager);
            Bukkit.getPluginManager().registerEvents(chunkMonitor, this);
        } else {
            this.chunkMonitor = null;
        }
    }

    @Override
    public void onDisable() {
        if (chunkMonitor != null) {
            chunkMonitor.shutdown();
        }
        if (frozenChunkManager != null) {
            frozenChunkManager.shutdown();
        }
    }
}
