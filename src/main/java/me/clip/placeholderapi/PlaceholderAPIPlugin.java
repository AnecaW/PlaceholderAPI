package me.clip.placeholderapi;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.wannes.plugModCore.api.ModuleContext;

import me.clip.placeholderapi.configuration.PlaceholderAPIConfig;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import me.clip.placeholderapi.expansion.manager.CloudExpansionManager;
import me.clip.placeholderapi.scheduler.UniversalScheduler;
import me.clip.placeholderapi.scheduler.scheduling.schedulers.TaskScheduler;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * A lightweight shim that provides the subset of methods other PlaceholderAPI classes expect
 * while running inside a PlugModCore module (not a full JavaPlugin).
 */
public final class PlaceholderAPIPlugin {

    private static PlaceholderAPIPlugin instance;

    private final ModuleContext context;
    private final File dataFolder;
    private FileConfiguration config;
    private final Logger logger;

    private final PlaceholderAPIConfig placeholderConfig;
    private final LocalExpansionManager localExpansionManager;
    private final CloudExpansionManager cloudExpansionManager;
    private final me.clip.placeholderapi.expansion.manager.ExpansionInstaller installer;
    public me.clip.placeholderapi.expansion.manager.ExpansionInstaller getExpansionInstaller() { return installer; }
    private final me.clip.placeholderapi.adventure.BukkitAudiencesShim adventureShim;
    private final me.clip.placeholderapi.scheduler.ModuleScheduler scheduler;

    private PlaceholderAPIPlugin(ModuleContext ctx) {
        this.context = ctx;
        this.dataFolder = ctx.getDataFolder();
        this.logger = ctx.getLogger();

        // ensure data folder exists
        if (!dataFolder.exists()) dataFolder.mkdirs();

        // load or create config.yml
        File cfgFile = new File(dataFolder, "config.yml");
        if (!cfgFile.exists()) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) {
                    Files.copy(in, cfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    cfgFile.createNewFile();
                }
            } catch (Exception e) {
                logger.warning("Failed to write default config: " + e.getMessage());
            }
        }

        this.config = YamlConfiguration.loadConfiguration(cfgFile);

        this.placeholderConfig = new PlaceholderAPIConfig(this);
        this.localExpansionManager = new LocalExpansionManager(this);
        this.cloudExpansionManager = new CloudExpansionManager(this);
        this.installer = new me.clip.placeholderapi.expansion.manager.ExpansionInstaller(this, cloudExpansionManager);
        this.adventureShim = new me.clip.placeholderapi.adventure.BukkitAudiencesShim();
        this.scheduler = new me.clip.placeholderapi.scheduler.ModuleScheduler(ctx);

        // expose instance to static accessors used across the port
        instance = this;
    }

    public void reloadConf(org.bukkit.command.CommandSender sender) {
        reloadConfig();
        getLocalExpansionManager().reloadAsync(true).whenComplete((n, e) -> report(sender, e == null ? "Reloaded " + n + " expansions." : "Reload failed: " + e.getMessage()));

        if (getPlaceholderAPIConfig().isCloudEnabled()) {
            getCloudExpansionManager().load();
        } else {
            getCloudExpansionManager().kill();
        }
    }

    public void reloadConfig() {
        File cfgFile = new File(dataFolder, "config.yml");
        if (!cfgFile.exists()) return;
        this.config = YamlConfiguration.loadConfiguration(cfgFile);
    }

    public void saveConfig() {
        File cfgFile = new File(dataFolder, "config.yml");
        try {
            getConfig().save(cfgFile);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save expansion configuration", e);
        }
    }

    public static void init(ModuleContext ctx) {
        if (instance == null) {
            instance = new PlaceholderAPIPlugin(ctx);
            instance.onEnable();
        }
    }

    public static void shutdown() {
        if (instance != null) {
            instance.onDisable();
            instance = null;
        }
    }

    private void onEnable() {
        context.manage(installer);
        registerPermissions();
        context.registerCommand("papi", new me.clip.placeholderapi.commands.PlaceholderCommandRouter(this), "Manage PlaceholderAPI expansions", "/papi", "placeholderapi");
        context.registerListener(localExpansionManager);
        context.registerService(org.wannes.plugModCore.api.PlaceholderService.class, new org.wannes.plugModCore.api.PlaceholderService() {
            public String parse(org.bukkit.OfflinePlayer player, String text) { return PlaceholderAPI.setPlaceholders(player, text); }
            public org.wannes.plugModCore.api.PlaceholderResult parseDetailed(org.bukkit.OfflinePlayer player, String text) { return PlaceholderAPI.parseDetailed(player, text); }
            public String parseBrackets(org.bukkit.OfflinePlayer player, String text) { return PlaceholderAPI.setBracketPlaceholders(player, text); }
            public java.util.Set<String> identifiers() { return PlaceholderAPI.getRegisteredIdentifiers(); }
            public AutoCloseable register(ModuleContext owner, org.wannes.plugModCore.api.PlaceholderProvider provider) {
                var expansion = new me.clip.placeholderapi.expansion.PlaceholderExpansion() {
                    public String getIdentifier() { return provider.identifier(); }
                    public String getAuthor() { return owner.getModuleId(); }
                    public String getVersion() { return provider.version(); }
                    public boolean persist() { return true; }
                    public String onRequest(org.bukkit.OfflinePlayer player, String parameters) { return provider.resolve(player, parameters); }
                };
                if (!expansion.register()) throw new IllegalStateException("Placeholder identifier " + provider.identifier() + " is already registered or registration was rejected");
                class Registration implements AutoCloseable {
                    private me.clip.placeholderapi.expansion.PlaceholderExpansion registered = expansion;
                    public void close() { if (registered != null) { if (registered.isRegistered()) registered.unregister(); registered = null; } }
                }
                Registration registration = new Registration();
                try { return owner.manage(registration); }
                catch (RuntimeException failure) { registration.close(); throw failure; }
            }
        });
        // load expansions from disk
        try {
            localExpansionManager.load(Bukkit.getConsoleSender());
        } catch (Exception e) {
            logger.warning("Failed to load expansions: " + e.getMessage());
        }

        if (placeholderConfig.isCloudEnabled()) {
            cloudExpansionManager.load();
        }
    }

    private void registerPermissions() {
        java.util.Map<String, Boolean> children = new java.util.LinkedHashMap<>();
        for (String action : java.util.List.of("parse", "reload", "info", "list", "unload", "ecloud", "ecloud.list", "ecloud.search", "ecloud.download", "ecloud.info", "ecloud.placeholders", "ecloud.update", "ecloud.refresh")) {
            String name = "placeholderapi." + action;
            children.put(name, true);
            addPermission(new org.bukkit.permissions.Permission(name, org.bukkit.permissions.PermissionDefault.FALSE));
        }
        addPermission(new org.bukkit.permissions.Permission("placeholderapi.admin", "Manage PlaceholderAPI expansions", org.bukkit.permissions.PermissionDefault.OP, children));
    }

    private void addPermission(org.bukkit.permissions.Permission permission) {
        var manager = Bukkit.getPluginManager();
        if (manager.getPermission(permission.getName()) != null) return;
        manager.addPermission(permission);
        context.manage(() -> manager.removePermission(permission));
    }

    private void onDisable() {
        RuntimeException failure = null;
        for (Runnable cleanup : java.util.List.<Runnable>of(installer::close, scheduler::cancelTasks, cloudExpansionManager::kill, localExpansionManager::kill)) {
            try { cleanup.run(); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }

    public static PlaceholderAPIPlugin getInstance() {
        if (instance == null) throw new IllegalStateException("PlaceholderAPIPlugin not initialized");
        return instance;
    }

    public File getDataFolder() { return dataFolder; }
    public ModuleContext getModuleContext() { return context; }
    public Logger getLogger() { return logger; }
    public FileConfiguration getConfig() { return config; }
    public PlaceholderAPIConfig getPlaceholderAPIConfig() { return placeholderConfig; }
    public LocalExpansionManager getLocalExpansionManager() { return localExpansionManager; }
    public CloudExpansionManager getCloudExpansionManager() { return cloudExpansionManager; }

    public TaskScheduler getScheduler() {
        return scheduler;
    }

    public String getVersion() {
        try {
            return context.getVersion();
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    public org.bukkit.plugin.PluginDescriptionFile getDescription() {
        org.bukkit.plugin.Plugin host = org.bukkit.Bukkit.getPluginManager().getPlugin("PlugModCore");
        if (host != null && host.getDescription() != null) return host.getDescription();
        try {
            return new org.bukkit.plugin.PluginDescriptionFile("PlaceholderAPI", getVersion(), "me.clip.placeholderapi.PlaceholderAPIModule");
        } catch (NoSuchMethodError | Exception e) {
            // fallback: return null-like object via anonymous subclass emulation is not possible; return host description or a minimal object
            return org.bukkit.Bukkit.getServer().getPluginManager().getPlugins().length > 0 ? org.bukkit.Bukkit.getServer().getPluginManager().getPlugins()[0].getDescription() : null;
        }
    }

    public me.clip.placeholderapi.adventure.BukkitAudiencesShim getAdventure() {
        return adventureShim;
    }

    public static java.text.SimpleDateFormat getDateFormat() {
        try {
            return new java.text.SimpleDateFormat(getInstance().getPlaceholderAPIConfig().dateFormat());
        } catch (final IllegalArgumentException ex) {
            getInstance().getLogger().warning("Configured date format is invalid, using default");
            return new java.text.SimpleDateFormat("MM/dd/yy HH:mm:ss");
        }
    }

    public org.bukkit.Server getServer() {
        return org.bukkit.Bukkit.getServer();
    }

    public static me.clip.placeholderapi.expansion.Version getServerVersion() {
        return new me.clip.placeholderapi.expansion.Version(Bukkit.getBukkitVersion(), true);
    }

    public void report(org.bukkit.command.CommandSender sender, String message) {
        if (Bukkit.isPrimaryThread()) sender.sendMessage(message);
        else try { context.schedule(() -> sender.sendMessage(message), 0, 0, false); }
        catch (IllegalStateException ignored) { logger.info(message); }
    }
}
