package me.clip.placeholderapi.configuration;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.jetbrains.annotations.NotNull;
import java.util.Optional;

import me.clip.placeholderapi.configuration.ExpansionSort;

public final class PlaceholderAPIConfig {

    @NotNull
    private final PlaceholderAPIPlugin plugin;

    public PlaceholderAPIConfig(@NotNull final PlaceholderAPIPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isCloudEnabled() {
        return plugin.getConfig().getBoolean("cloud_enabled", false);
    }

    public boolean checkUpdates() {
        return plugin.getConfig().getBoolean("check_updates", false);
    }

    public boolean isDebugMode() {
        return plugin.getConfig().getBoolean("debug", false);
    }

    public Optional<ExpansionSort> getExpansionSort() {
        final String option = plugin.getConfig().getString("cloud_sorting", ExpansionSort.LATEST.name());

        try {
            return Optional.of(ExpansionSort.valueOf(option.toUpperCase()));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @NotNull
    public String dateFormat() {
        return plugin.getConfig().getString("date_format", "MM/dd/yy HH:mm:ss");
    }

    public boolean detectMaliciousExpansions() {
        return plugin.getConfig().getBoolean("detect_malicious_expansions", true);
    }

}
