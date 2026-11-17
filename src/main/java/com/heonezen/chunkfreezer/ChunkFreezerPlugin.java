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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class ChunkFreezerPlugin extends JavaPlugin {

    private Settings settings;
    private Lang lang;
    /** Created once and kept across /chunk reload, so chunks that are frozen right now stay
     *  frozen through a reload instead of every crash machine being released at once. */
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
        initComponents();
        Bukkit.getServicesManager().register(ChunkFreezerAPI.class, apiImpl, this, ServicePriority.Normal);
    }
    private void initComponents() {
        settings = new Settings(getConfig(), new File(getDataFolder(), "config.yml"), getLogger());
        lang = loadLang();
        if (manager == null) manager = new FrozenChunkManager(this, settings);
        else manager.updateSettings(settings);
        // A protection that was switched off by the reload would never release its chunks again.
        if (!settings.entitiesProtectionEnabled) manager.releaseCause(FrozenChunkManager.FreezeCause.ENTITY);
        if (!settings.redstoneProtectionEnabled) {
            manager.releaseCause(FrozenChunkManager.FreezeCause.REDSTONE);
            manager.clearRedstoneMutes();
        }
        chunkCommand.setManager(manager);
        chunkCommand.setLang(lang);
        apiImpl.update(manager, settings);

        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new ProtectionListener(this, settings, manager, lang), this);

        barrierListener = new BarrierListener(this, settings, manager, lang);
        pm.registerEvents(barrierListener, this);
        manager.setFrozenSetListener(barrierListener::syncLivingMoveListener);
        barrierListener.syncLivingMoveListener();
        barrierListener.startParticleTasks();
        chunkCommand.setBarrierListener(barrierListener);
        apiImpl.setBarrierListener(barrierListener);

        if (settings.redstoneProtectionEnabled)
            pm.registerEvents(new RedstoneLoopProtector(this, settings, manager, lang), this);

        regionBorderListener = new RegionBorderListener(this, settings);
        pm.registerEvents(regionBorderListener, this);
        regionBorderListener.startTasks();

        /** Always created: /chunk despawn works regardless of whether automatic monitoring is
         *  enabled; entities-protection.enabled still controls the automatic freezing inside it. */
        chunkMonitor = new ChunkMonitor(this, settings, manager, lang);
        pm.registerEvents(chunkMonitor, this);
        chunkMonitor.runStartupScan();
        chunkCommand.setChunkMonitor(chunkMonitor);
    }
    /** Loads language.yml fresh from disk (writing the bundled default first if it's missing). */
    private Lang loadLang() {
        File langFile = new File(getDataFolder(), "language.yml");
        if (!langFile.exists()) saveResource("language.yml", false);
        return new Lang(YamlConfiguration.loadConfiguration(langFile));
    }
    private void registerCommand() {
        PluginCommand cmd = getCommand("chunk");
        if (cmd != null) { cmd.setExecutor(chunkCommand); cmd.setTabCompleter(chunkCommand); }
    }
    private void teardownComponents() {
        if (manager != null) manager.setFrozenSetListener(null);
        if (chunkMonitor != null) { chunkMonitor.shutdown(); chunkMonitor = null; }
        if (barrierListener != null) { barrierListener.shutdown(); barrierListener = null; }
        if (regionBorderListener != null) { regionBorderListener.shutdown(); regionBorderListener = null; }
        if (apiImpl != null) apiImpl.setBarrierListener(null);
        if (chunkCommand != null) { chunkCommand.setBarrierListener(null); chunkCommand.setChunkMonitor(null); }
    }
    /** /chunk reload. Synchronized: two admins reloading at the same moment (on Folia from two
     *  different region threads) must not interleave teardown and init. */
    public synchronized void reloadPlugin() {
        teardownComponents();
        HandlerList.unregisterAll(this);
        reloadConfig();
        initComponents();
    }
    @Override
    public synchronized void onDisable() {
        teardownComponents();
        if (manager != null) { manager.shutdown(); manager = null; }
        Bukkit.getServicesManager().unregisterAll(this);
    }
}
