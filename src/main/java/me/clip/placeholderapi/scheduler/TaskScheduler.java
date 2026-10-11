package me.clip.placeholderapi.scheduler;

public interface TaskScheduler {
    void runTask(Runnable r);
    void runTaskLater(Runnable r, long ticks);
    void runTaskAsync(Runnable r);
}
