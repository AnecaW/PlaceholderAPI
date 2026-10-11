package me.clip.placeholderapi.scheduler;

import me.clip.placeholderapi.scheduler.bukkit.BukkitScheduledTask;
import me.clip.placeholderapi.scheduler.scheduling.schedulers.TaskScheduler;
import me.clip.placeholderapi.scheduler.scheduling.tasks.MyScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.wannes.plugModCore.api.ModuleContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** This shim owns only PlaceholderAPI tasks, never all PlugModCore tasks. */
public final class ModuleScheduler implements TaskScheduler {
    private final ModuleContext context;
    private final List<MyScheduledTask> tasks = new CopyOnWriteArrayList<>();
    public ModuleScheduler(ModuleContext context) { this.context = context; }
    private MyScheduledTask schedule(Runnable action, long delay, long period, boolean async) {
        var owner = me.clip.placeholderapi.expansion.manager.LocalExpansionManager.currentRuntime();
        var callback = new java.util.concurrent.atomic.AtomicReference<Runnable>(owner == null ? action : () -> owner.invoke(() -> { action.run(); return null; }));
        Runnable guarded = () -> { Runnable run = period > 0 ? callback.get() : callback.getAndSet(null); if (run != null) run.run(); };
        var bukkitTask = context.schedule(guarded, delay, period, async);
        if (owner != null) owner.own(bukkitTask, () -> callback.set(null));
        MyScheduledTask task = new BukkitScheduledTask(bukkitTask, period > 0);
        tasks.add(task); return task;
    }
    public boolean isGlobalThread() { return Bukkit.isPrimaryThread(); }
    public boolean isRegionThread(org.bukkit.Location location) { return Bukkit.isPrimaryThread(); }
    public boolean isEntityThread(org.bukkit.entity.Entity entity) { return Bukkit.isPrimaryThread(); }
    public MyScheduledTask runTask(Runnable action) { return schedule(action, 0, 0, false); }
    public MyScheduledTask runTaskLater(Runnable action, long delay) { return schedule(action, delay, 0, false); }
    public MyScheduledTask runTaskTimer(Runnable action, long delay, long period) { return schedule(action, delay, period, false); }
    public MyScheduledTask runTaskAsynchronously(Runnable action) { return schedule(action, 0, 0, true); }
    public MyScheduledTask runTaskLaterAsynchronously(Runnable action, long delay) { return schedule(action, delay, 0, true); }
    public MyScheduledTask runTaskTimerAsynchronously(Runnable action, long delay, long period) { return schedule(action, delay, period, true); }
    public void execute(Runnable action) { runTask(action); }
    public void cancelTasks() { for (MyScheduledTask task : tasks) task.cancel(); tasks.clear(); }
    public void cancelTasks(Plugin plugin) {
        if (plugin != context.getHostPlugin()) throw new IllegalArgumentException("Cannot cancel another plugin's tasks");
        cancelTasks();
    }
    public <T> Future<T> callSyncMethod(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        runTask(() -> { try { result.complete(action.call()); } catch (Exception e) { result.completeExceptionally(e); } });
        context.manage(() -> result.cancel(false));
        return result;
    }
}
