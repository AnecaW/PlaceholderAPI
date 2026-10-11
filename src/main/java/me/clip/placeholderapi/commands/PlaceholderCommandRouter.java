package me.clip.placeholderapi.commands;

import me.clip.placeholderapi.*;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import java.util.*;
import java.util.stream.Collectors;

public final class PlaceholderCommandRouter implements CommandExecutor, TabCompleter {
    private final PlaceholderAPIPlugin plugin;
    private static final List<String> ROOT = List.of("parse", "reload", "info", "list", "unload", "ecloud");
    private static final List<String> CLOUD = List.of("list", "search", "download", "info", "placeholders", "update", "refresh");
    public PlaceholderCommandRouter(PlaceholderAPIPlugin plugin) { this.plugin = plugin; }
    private boolean allowed(CommandSender sender, String action) {
        return sender.hasPermission("placeholderapi.admin") || sender.hasPermission("placeholderapi." + action);
    }
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed(sender, action)) { sender.sendMessage("You lack permission: placeholderapi." + action); return true; }
        try {
            var local = plugin.getLocalExpansionManager();
            switch (action) {
                case "info", "list" -> {
                    if (action.equals("info") && args.length >= 2) {
                        var expansion = local.findExpansionByIdentifier(args[1]).orElseThrow(() -> new IllegalArgumentException("Expansion is not registered: " + args[1]));
                        sender.sendMessage(expansion.getIdentifier() + " " + expansion.getVersion() + " by " + expansion.getAuthor() + "; required plugin=" + Objects.toString(expansion.getRequiredPlugin(), "none") + "; persistent=" + expansion.persist());
                    } else sender.sendMessage("PlaceholderAPI module " + plugin.getVersion() + ": " + String.join(", ", PlaceholderAPI.getRegisteredIdentifiers()));
                }
                case "reload" -> plugin.reloadConf(sender);
                case "unload" -> {
                    if (args.length != 2) throw new IllegalArgumentException("Usage: /papi unload <identifier>");
                    sender.sendMessage(local.unload(args[1]) ? "Expansion unloaded. Installed JAR retained for the next reload." : "Expansion is not registered.");
                }
                case "parse" -> {
                    if (args.length < 3) throw new IllegalArgumentException("Usage: /papi parse <player|me|--null> <text>");
                    OfflinePlayer player;
                    if (args[1].equalsIgnoreCase("--null")) player = null;
                    else if (args[1].equalsIgnoreCase("me") && sender instanceof org.bukkit.entity.Player p) player = p;
                    else {
                        player = Bukkit.getPlayerExact(args[1]);
                        if (player == null) player = Bukkit.getOfflinePlayerIfCached(args[1]);
                        if (player == null) throw new IllegalArgumentException("Player is unknown; use an online/cached player or --null.");
                    }
                    var service = plugin.getModuleContext().requireService(org.wannes.plugModCore.api.PlaceholderService.class, "placeholderapi");
                    var result = service.parseDetailed(player, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                    sender.sendMessage(result.text());
                    for (String diagnostic : result.diagnostics()) sender.sendMessage("Placeholder diagnostic: " + diagnostic);
                }
                case "ecloud" -> cloud(sender, args);
                default -> sender.sendMessage("Usage: /papi " + String.join(" | ", ROOT));
            }
        } catch (Exception e) { sender.sendMessage("PlaceholderAPI: " + e.getMessage()); }
        return true;
    }
    private void cloud(CommandSender sender, String[] args) {
        var manager = plugin.getCloudExpansionManager();
        if (!plugin.getPlaceholderAPIConfig().isCloudEnabled()) throw new IllegalStateException("eCloud is disabled; set cloud_enabled: true in the module config.yml.");
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        if (!CLOUD.contains(action)) throw new IllegalArgumentException("Usage: /papi ecloud " + String.join(" | ", CLOUD));
        if (!allowed(sender, "ecloud." + action)) throw new IllegalArgumentException("You lack permission: placeholderapi.ecloud." + action);
        if (action.equals("refresh")) {
            sender.sendMessage("Refreshing official eCloud catalogue...");
            manager.refresh().whenComplete((entries, error) -> plugin.report(sender, error == null ? "eCloud ready: " + entries.size() + " expansions." : "eCloud refresh failed: " + error.getMessage())); return;
        }
        if (manager.isEmpty()) throw new IllegalStateException(manager.isRefreshing() ? "Official catalogue is still loading; try again shortly." : "Catalogue unavailable. Use /papi ecloud refresh. " + Objects.toString(manager.getLastError(), ""));
        if (action.equals("list") || action.equals("search")) {
            String query = action.equals("search") ? (args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)).toLowerCase(Locale.ROOT) : "") : "";
            if (action.equals("search") && query.isBlank()) throw new IllegalArgumentException("Usage: /papi ecloud search <query>");
            List<String> matches = manager.getCloudExpansions().values().stream().filter(e -> (e.getName() + " " + e.getAuthor()).toLowerCase(Locale.ROOT).contains(query)).map(CloudExpansion::getName).sorted().toList();
            int page = action.equals("list") && args.length >= 3 ? Integer.parseInt(args[2]) : 1;
            int pages = Math.max(1, (matches.size() + 19) / 20);
            if (page < 1 || page > pages) throw new IllegalArgumentException("Page must be between 1 and " + pages);
            sender.sendMessage("Official eCloud (" + matches.size() + " results), page " + page + "/" + pages + ": " + String.join(", ", matches.subList((page - 1) * 20, Math.min(page * 20, matches.size())))); return;
        }
        if (args.length < 3) throw new IllegalArgumentException("Usage: /papi ecloud " + action + " <expansion> [version]");
        manager.getCloudExpansions();
        var expansion = manager.findCloudExpansionByName(args[2]).orElseThrow(() -> new IllegalArgumentException("Unknown official expansion: " + args[2]));
        switch (action) {
            case "info" -> sender.sendMessage(expansion.getName() + " by " + expansion.getAuthor() + "; latest " + expansion.getLatestVersion() + "; registered=" + expansion.hasExpansion() + "; update=" + expansion.shouldUpdate() + "\n" + Objects.toString(expansion.getDescription(), "") + "\nDependency: " + Objects.toString(expansion.getDependencyUrl(), "none listed"));
            case "placeholders" -> sender.sendMessage("Catalogue examples: " + String.join(", ", expansion.getPlaceholders() == null ? List.of() : expansion.getPlaceholders()));
            case "download", "update" -> {
                var version = expansion.getVersion(args.length >= 4 && action.equals("download") ? args[3] : expansion.getLatestVersion());
                if (version == null) throw new IllegalArgumentException("Unknown version. Available: " + expansion.getAvailableVersions());
                sender.sendMessage("Downloading & installing " + expansion.getName() + " " + version.getVersion() + "...");
                plugin.getExpansionInstaller().installAndAwait(expansion.getName(), version.getVersion()).whenComplete((state, error) -> plugin.report(sender, error == null ? expansion.getName() + ": validation SUCCESS; loading SUCCESS; registration SUCCESS; status ACTIVE" : "Installation/activation failed: " + error.getMessage()));
            }
        }
    }
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        List<String> choices = List.of(); String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) choices = ROOT.stream().filter(s -> allowed(sender, s)).toList();
        else if (allowed(sender, action)) {
            if (action.equals("ecloud") && args.length == 2) choices = CLOUD.stream().filter(s -> allowed(sender, "ecloud." + s)).toList();
            else if (action.equals("ecloud") && args.length == 3 && List.of("download", "update", "info", "placeholders").contains(args[1].toLowerCase(Locale.ROOT)) && allowed(sender, "ecloud." + args[1].toLowerCase(Locale.ROOT))) choices = new ArrayList<>(plugin.getCloudExpansionManager().getCloudExpansions().keySet());
            else if (action.equals("ecloud") && args.length == 4 && args[1].equalsIgnoreCase("download") && allowed(sender, "ecloud.download")) choices = plugin.getCloudExpansionManager().findCloudExpansionByName(args[2]).map(CloudExpansion::getAvailableVersions).orElse(List.of());
            else if ((action.equals("unload") || action.equals("info")) && args.length == 2) choices = new ArrayList<>(PlaceholderAPI.getRegisteredIdentifiers());
            else if (action.equals("parse") && args.length == 2) { choices = new ArrayList<>(List.of("me", "--null")); for (var player : Bukkit.getOnlinePlayers()) choices.add(player.getName()); }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().collect(Collectors.toList());
    }
}
