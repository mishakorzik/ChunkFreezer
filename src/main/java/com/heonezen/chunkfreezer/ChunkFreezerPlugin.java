package com.heonezen.chunkfreezer;

import com.heonezen.chunkfreezer.api.ChunkFreezerAPI;
import com.heonezen.chunkfreezer.api.ChunkFreezerAPIImpl;
import com.heonezen.chunkfreezer.command.ChunkCommand;
import com.heonezen.chunkfreezer.config.Lang;
import com.heonezen.chunkfreezer.config.Settings;
import com.heonezen.chunkfreezer.freeze.FrozenChunkManager;
import com.heonezen.chunkfreezer.listener.BarrierListener;
import com.heonezen.chunkfreezer.listener.ProtectionListener;
import com.heonezen.chunkfreezer.listener.RegionBorderListener;
import com.heonezen.chunkfreezer.monitor.ChunkMonitor;
import com.heonezen.chunkfreezer.redstone.RedstoneLoopProtector;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkFreezerPlugin extends JavaPlugin {

    private Settings settings;
    private Lang lang;
    private FrozenChunkManager manager;
    private ChunkMonitor chunkMonitor;
    private BarrierListener barrierListener;
    private RegionBorderListener regionBorderListener;
    private ChunkCommand chunkCommand;
    private ChunkFreezerAPIImpl apiImpl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chunkCommand = new ChunkCommand(this, null);
        registerCommand();
        apiImpl = new ChunkFreezerAPIImpl();
        Bukkit.getServicesManager().register(ChunkFreezerAPI.class, apiImpl, this, ServicePriority.Normal);
        initComponents();
    }
    private void initComponents() {
        settings = new Settings(getConfig(), new java.io.File(getDataFolder(), "config.yml"));
        lang = loadLang();
        manager  = new FrozenChunkManager(this, settings);
        if (chunkCommand != null) { chunkCommand.setManager(manager); chunkCommand.setLang(lang); }
        apiImpl.update(manager, settings);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this, settings, manager), this);
        barrierListener = new BarrierListener(this, settings, manager, lang);
        Bukkit.getPluginManager().registerEvents(barrierListener, this);
        barrierListener.startParticleTasks();
        if (chunkCommand != null) chunkCommand.setBarrierListener(barrierListener);
        apiImpl.setBarrierListener(barrierListener);
        if (settings.redstoneProtectionEnabled)
            Bukkit.getPluginManager().registerEvents(new RedstoneLoopProtector(this, settings, manager, lang), this);
        regionBorderListener = new RegionBorderListener(this, settings);
        Bukkit.getPluginManager().registerEvents(regionBorderListener, this);
        regionBorderListener.startTasks();
        /** Always created: /chunk despawn (manual instant-despawn) works regardless of whether
         *  automatic entities-protection monitoring is enabled. entities-protection.enabled still
         *  fully controls the automatic freeze-on-overload scanning inside ChunkMonitor itself. */
        chunkMonitor = new ChunkMonitor(this, settings, manager, lang);
        Bukkit.getPluginManager().registerEvents(chunkMonitor, this);
        chunkMonitor.runStartupScan();
        if (chunkCommand != null) chunkCommand.setChunkMonitor(chunkMonitor);
    }
    /** Loads language.yml fresh from disk (writing the bundled default first if it's missing),
     *  exactly like config.yml's own load/reload lifecycle - so editing either file and running
     *  /chunk reload always picks up the latest content, never a cached copy. */
    private Lang loadLang() {
        java.io.File langFile = new java.io.File(getDataFolder(), "language.yml");
        if (!langFile.exists()) saveResource("language.yml", false);
        return new Lang(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(langFile));
    }
    private void registerCommand() {
        PluginCommand cmd = getCommand("chunk");
        if (cmd != null) { cmd.setExecutor(chunkCommand); cmd.setTabCompleter(chunkCommand); }
    }
    public void reloadPlugin() {
        if (chunkMonitor != null) { chunkMonitor.shutdown(); chunkMonitor = null; }
        if (barrierListener != null) { barrierListener.shutdown(); barrierListener = null; }
        if (regionBorderListener != null) { regionBorderListener.shutdown(); regionBorderListener = null; }
        if (apiImpl != null) apiImpl.setBarrierListener(null);
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
        if (regionBorderListener != null) regionBorderListener.shutdown();
        if (manager != null) manager.shutdown();
        Bukkit.getServicesManager().unregisterAll(this);
    }
}
