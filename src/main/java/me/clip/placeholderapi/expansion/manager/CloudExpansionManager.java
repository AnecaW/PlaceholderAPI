package me.clip.placeholderapi.expansion.manager;

import com.google.gson.Gson;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Asynchronous official catalogue and downloads. Installed files are not active expansions. */
public final class CloudExpansionManager {
    public static final String USER_AGENT = "PlugModCore-PlaceholderAPI";
    private final PlaceholderAPIPlugin plugin;
    private final ECloudClient client;
    private volatile Map<String, CloudExpansion> cache = Map.of();
    private final Map<String, CompletableFuture<File>> downloads = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Map<String, CloudExpansion>> refresh;
    private volatile boolean stopped;
    private volatile long generation;
    private volatile String lastError;
    public CloudExpansionManager(PlaceholderAPIPlugin plugin) { this(plugin, new ECloudClient()); }
    public CloudExpansionManager(PlaceholderAPIPlugin plugin, ECloudClient client) { this.plugin = plugin; this.client = client; }
    public void load() { refresh().whenComplete((entries, error) -> { if (error != null && !stopped) plugin.getLogger().log(Level.WARNING, "Cannot refresh official eCloud catalogue; previous catalogue retained", error); }); }
    public synchronized CompletableFuture<Map<String, CloudExpansion>> refresh() {
        if (!plugin.getPlaceholderAPIConfig().isCloudEnabled()) return CompletableFuture.failedFuture(new IllegalStateException("eCloud is disabled in config.yml (cloud_enabled)"));
        stopped = false;
        if (refresh != null && !refresh.isDone()) return refresh;
        long epoch = generation;
        CompletableFuture<Map<String, CloudExpansion>> result = new CompletableFuture<>(); refresh = result;
        try {
            plugin.getModuleContext().schedule(() -> {
                try {
                    Path saved = plugin.getDataFolder().toPath().resolve("cache/ecloud.json");
                    if (cache.isEmpty() && Files.isRegularFile(saved) && Files.size(saved) <= 8 * 1024 * 1024) {
                        try { var previous = ECloudClient.parseCatalogue(Files.readString(saved)); synchronized (this) { if (!stopped && generation == epoch) cache = previous; } }
                        catch (IOException invalidCache) { plugin.getLogger().warning("Ignoring invalid cached eCloud metadata: " + invalidCache.getMessage()); }
                    }
                    Map<String, CloudExpansion> updated = client.fetch();
                    synchronized (this) {
                        if (stopped || generation != epoch) throw new CancellationException("eCloud refresh cancelled");
                        cache = updated; lastError = null;
                    }
                    Path folder = plugin.getDataFolder().toPath().resolve("cache"); Files.createDirectories(folder);
                    Path temporary = Files.createTempFile(folder, "catalogue-", ".tmp");
                    try {
                        Files.writeString(temporary, new Gson().toJson(updated));
                        try { Files.move(temporary, folder.resolve("ecloud.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, folder.resolve("ecloud.json"), StandardCopyOption.REPLACE_EXISTING); }
                    } finally { Files.deleteIfExists(temporary); }
                    result.complete(getCloudExpansions());
                } catch (Exception e) { lastError = e.getMessage(); result.completeExceptionally(e); }
            }, 0, 0, true);
        } catch (RuntimeException e) { result.completeExceptionally(e); }
        return result;
    }
    public synchronized void kill() {
        stopped = true; generation++;
        if (refresh != null && !refresh.isDone()) refresh.completeExceptionally(new CancellationException("Module stopped"));
        for (var future : downloads.values()) future.cancel(false);
        downloads.clear(); cache = Map.of();
    }
    public void clean() { kill(); }
    public void fetch() { load(); }
    public String getLastError() { return lastError; }
    public boolean isRefreshing() { return refresh != null && !refresh.isDone(); }
    public boolean isEmpty() { return cache.isEmpty(); }
    public Map<String, CloudExpansion> getCloudExpansions() {
        for (CloudExpansion entry : cache.values()) {
            var installed = plugin.getLocalExpansionManager().findExpansionByFile(installedFile(entry));
            if (installed.isEmpty()) installed = plugin.getLocalExpansionManager().findExpansionByIdentifier(entry.getName());
            entry.setHasExpansion(installed.isPresent());
            entry.setShouldUpdate(installed.map(e -> !e.getVersion().equals(entry.getLatestVersion())).orElse(false));
        }
        return cache;
    }
    public Map<String, CloudExpansion> getCloudExpansionsInstalled() { return getCloudExpansions().entrySet().stream().filter(e -> e.getValue().hasExpansion()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)); }
    public Map<String, CloudExpansion> getCloudExpansionsByAuthor(String author) { return cache.entrySet().stream().filter(e -> author.equalsIgnoreCase(e.getValue().getAuthor())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)); }
    public Set<String> getCloudExpansionAuthors() { return cache.values().stream().map(CloudExpansion::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet()); }
    public int getCloudExpansionAuthorCount() { return getCloudExpansionAuthors().size(); }
    public int getCloudUpdateCount() { return (int) getCloudExpansions().values().stream().filter(CloudExpansion::shouldUpdate).count(); }
    public Optional<CloudExpansion> findCloudExpansionByName(String name) { return name == null ? Optional.empty() : Optional.ofNullable(cache.get(name.toLowerCase(Locale.ROOT).replace(' ', '_'))); }
    public boolean isDownloading(CloudExpansion expansion) { return downloads.containsKey(expansion.getName().toLowerCase(Locale.ROOT).replace(' ', '_')); }
    public File installedFile(CloudExpansion expansion) {
        return new File(plugin.getLocalExpansionManager().getExpansionsFolder(), "Expansion-" + expansion.getName().toLowerCase(Locale.ROOT).replace(' ', '_') + ".jar");
    }
    public synchronized CompletableFuture<File> downloadExpansion(CloudExpansion expansion, CloudExpansion.Version version) {
        if (stopped || !plugin.getPlaceholderAPIConfig().isCloudEnabled()) return CompletableFuture.failedFuture(new IllegalStateException("eCloud is disabled or stopped"));
        if (expansion == null || version == null || findCloudExpansionByName(expansion.getName()).orElse(null) != expansion || !expansion.getVersions().contains(version))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Download must refer to the current official catalogue"));
        String name = expansion.getName().toLowerCase(Locale.ROOT).replace(' ', '_');
        String blocked = System.getenv("PAPI_BLOCKED_EXPANSIONS");
        if (blocked != null && Arrays.stream(blocked.split(",")).anyMatch(s -> s.trim().equalsIgnoreCase(name))) return CompletableFuture.failedFuture(new IllegalStateException("Expansion blocked by PAPI_BLOCKED_EXPANSIONS"));
        if (downloads.containsKey(name)) return CompletableFuture.failedFuture(new IllegalStateException("A download for this expansion is already running"));
        CompletableFuture<File> result = new CompletableFuture<>(); downloads.put(name, result);
        long epoch = generation;
        try {
            plugin.getModuleContext().schedule(() -> {
                try {
                    Path file = client.download(expansion, version, plugin.getLocalExpansionManager().getExpansionsFolder().toPath(), () -> stopped || epoch != generation || result.isCancelled());
                    if (stopped || epoch != generation) throw new CancellationException("Module stopped before download completed");
                    result.complete(file.toFile());
                } catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Cannot download expansion " + name, e); result.completeExceptionally(e); }
                finally { downloads.remove(name, result); }
            }, 0, 0, true);
        } catch (RuntimeException e) { downloads.remove(name, result); result.completeExceptionally(e); }
        return result;
    }
}
