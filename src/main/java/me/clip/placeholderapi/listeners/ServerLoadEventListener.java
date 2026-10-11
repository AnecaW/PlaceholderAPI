package me.clip.placeholderapi.listeners;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

public class ServerLoadEventListener implements Listener {

    private final PlaceholderAPIPlugin plugin;

    public ServerLoadEventListener(PlaceholderAPIPlugin plugin) {
        this.plugin = plugin;
        // For simplicity, trigger load on next tick if server has already started
        Bukkit.getScheduler().runTaskLater(org.bukkit.Bukkit.getPluginManager().getPlugin("PlugModCore"), () -> {
            try {
                plugin.getLocalExpansionManager().load(Bukkit.getConsoleSender());
            } catch (Exception ignored) {}
        }, 1L);
    }
}
