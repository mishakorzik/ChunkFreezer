package com.heonezen.chunkfreezer.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** At most one repeating task per online player, scheduled on that player's own EntityScheduler
 *  (so on Folia it always runs on the region thread that currently owns the player).
 *
 *  <p>Handles the races a naive "containsKey, then put" registry gets wrong: two starts for the
 *  same player never produce two tasks, and a retired/cancelled task can only ever remove its own
 *  registry entry - never a newer task that replaced it (e.g. after a respawn). */
public final class PlayerTasks {

    @FunctionalInterface
    public interface Body { void run(Player player, ScheduledTask task); }

    private static final int MAX_START_ATTEMPTS = 10;

    private final Plugin plugin;
    private final BooleanSupplier wanted;
    private final long delay, period;
    private final Body body;
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PlayerTasks(Plugin plugin, BooleanSupplier wanted, long delay, long period, Body body) {
        this.plugin = plugin; this.wanted = wanted; this.delay = delay; this.period = period; this.body = body;
    }

    public void start(Player p) { start(p, 0); }

    private void start(Player p, int attempt) {
        if (closed || !wanted.getAsBoolean() || p == null || !p.isOnline()) return;
        UUID id = p.getUniqueId();
        if (tasks.containsKey(id)) return;
        AtomicReference<ScheduledTask> self = new AtomicReference<>();
        ScheduledTask t = p.getScheduler().runAtFixedRate(plugin, st -> body.run(p, st), () -> {
            ScheduledTask mine = self.get();
            if (mine != null) tasks.remove(id, mine);
        }, delay, period);
        if (t == null) {
            // The player's scheduler is retired for a moment (e.g. mid-respawn/world change): retry shortly.
            if (attempt < MAX_START_ATTEMPTS) {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, x -> start(p, attempt + 1), 4L);
            }
            return;
        }
        self.set(t);
        if (tasks.putIfAbsent(id, t) != null || closed) {
            cancelQuietly(t);
            tasks.remove(id, t);
        }
    }

    /** Stops the player's task (if any) and starts a fresh one a couple of ticks later. */
    public void restart(Player p) {
        stop(p.getUniqueId());
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, x -> start(p), 2L);
    }

    public void stop(UUID id) {
        ScheduledTask t = tasks.remove(id);
        if (t != null) cancelQuietly(t);
    }

    /** Called from inside a task body that decided to end itself. */
    public void finish(UUID id, ScheduledTask st) {
        cancelQuietly(st);
        tasks.remove(id, st);
    }

    public void close() {
        closed = true;
        tasks.values().forEach(PlayerTasks::cancelQuietly);
        tasks.clear();
    }

    private static void cancelQuietly(ScheduledTask t) {
        try { t.cancel(); } catch (Throwable ignored) {}
    }
}
