package me.clip.placeholderapi.expansion.manager;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.wannes.plugModCore.api.ModuleWebApi.ApiResponse;
import com.google.gson.Gson;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** One web action owns downloading, activation and disk rollback until completion. */
public final class ExpansionInstaller implements AutoCloseable {
    private final PlaceholderAPIPlugin plugin;
    private final CloudExpansionManager cloud;
    private final Map<String, String> statuses = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<String>> completions = new ConcurrentHashMap<>();
    public synchronized java.util.concurrent.CompletableFuture<String> installAndAwait(String name, String version) {
        ApiResponse response = install(name, version);
        if (response.getStatusCode() != 200) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException(response.getBody()));
        String key = cloud.findCloudExpansionByName(name).orElseThrow().getName().toLowerCase(Locale.ROOT).replace(' ', '_');
        if ("done".equals(statuses.get(key))) return java.util.concurrent.CompletableFuture.completedFuture("ACTIVE");
        if (statuses.getOrDefault(key, "").startsWith("failed:")) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException(statuses.get(key)));
        return completions.get(key);
    }
    private boolean busy;
    private volatile boolean closed;
    private File pendingArtifact;
    public ExpansionInstaller(PlaceholderAPIPlugin plugin, CloudExpansionManager cloud) { this.plugin = plugin; this.cloud = cloud; }
    public Map<String, String> statuses() { return Map.copyOf(statuses); }
    public synchronized boolean isBusy() { return busy; }
    public synchronized ApiResponse install(String name, String requestedVersion) {
        if (closed) return ApiResponse.error("PlaceholderAPI module is stopped");
        if (name == null || name.isBlank()) return ApiResponse.badRequest("Choose an expansion to install");
        var expansion = cloud.findCloudExpansionByName(name).orElse(null);
        if (expansion == null) return ApiResponse.badRequest("Expansion unavailable or official catalogue not ready; refresh the catalogue and try again");
        var version = expansion.getVersion(requestedVersion == null || requestedVersion.isBlank() ? expansion.getLatestVersion() : requestedVersion);
        if (version == null) return ApiResponse.badRequest("No downloadable artifact is listed for the requested version");
        String key = expansion.getName().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (busy) {
            if (List.of("downloading", "installing").contains(statuses.getOrDefault(key, ""))) return started(key);
            return ApiResponse.badRequest("Another expansion installation is in progress; wait for it to finish");
        }
        if (!plugin.getPlaceholderAPIConfig().isCloudEnabled()) return ApiResponse.badRequest("Automatic installation is disabled: enable cloud_enabled in the PlaceholderAPI configuration");
        if (!version.isVerified()) return ApiResponse.badRequest("This version is not approved by eCloud and cannot be installed automatically");
        var installed = plugin.getLocalExpansionManager().findExpansionByFile(cloud.installedFile(expansion));
        if (installed.isPresent() && installed.get().getVersion().equals(version.getVersion())) { statuses.put(key, "done"); return started(key); }
        if (statuses.size() > 200) statuses.entrySet().removeIf(e -> !List.of("downloading", "installing").contains(e.getValue()));
        busy = true; statuses.put(key, "downloading"); completions.put(key, new java.util.concurrent.CompletableFuture<>());
        cloud.downloadExpansion(expansion, version).whenComplete((file, failure) -> {
            synchronized (this) {
                pendingArtifact = file;
                if (closed) { if (file != null) try { rollbackFile(file); } catch (Exception e) { plugin.getLogger().warning("Cancelled installation artifact cleanup failed: " + e.getMessage()); } return; }
            }
            if (failure != null) { finish(key, "failed: " + reason(failure)); return; }
            plugin.getLogger().fine("Expansion " + key + ": Download SUCCESS; validation SUCCESS; artifact " + file.getName() + "; activating");
            statuses.put(key, "installing");
            try { plugin.getModuleContext().schedule(() -> {
                if (closed) return;
                plugin.getLocalExpansionManager().installAsync(file, version.getVersion()).whenComplete((registered, error) -> {
                    if (closed) return;
                    if (error == null) finish(key, "done"); else rollback(key, file, error);
                });
            }, 0, 0, false); }
            catch (RuntimeException e) { rollback(key, file, e); }
        });
        return started(key);
    }
    private ApiResponse started(String key) { return ApiResponse.okJson(new Gson().toJson(Map.of("status", "started", "key", key))); }
    private synchronized void finish(String key, String state) {
        if (!closed) statuses.put(key, state); pendingArtifact = null; busy = false;
        var completion = completions.remove(key);
        if ("done".equals(state)) {
            plugin.getLogger().info("Expansion " + key + ": Download SUCCESS; validation SUCCESS; loading SUCCESS; registration SUCCESS; status ACTIVE");
            if (completion != null) completion.complete("ACTIVE");
        } else {
            plugin.getLogger().warning("Expansion " + key + ": status NOT ACTIVE; " + state);
            if (completion != null) completion.completeExceptionally(new IllegalStateException(state));
        }
    }
    private void rollback(String key, File file, Throwable failure) {
        try { plugin.getModuleContext().schedule(() -> {
            try {
                rollbackFile(file);
                finish(key, "failed: " + reason(failure));
            } catch (Exception e) { finish(key, "failed: " + reason(failure) + "; artifact cleanup failed: " + e.getMessage()); }
        }, 0, 0, true); }
        catch (RuntimeException e) { finish(key, "failed: " + reason(failure) + "; artifact cleanup could not run: " + e.getMessage()); }
    }
    private void rollbackFile(File file) throws java.io.IOException {
        Path target = file.toPath(); Path previous = target.resolveSibling(file.getName() + ".previous");
        if (Files.exists(previous)) {
            try { Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(previous, target, StandardCopyOption.REPLACE_EXISTING); }
        } else if (plugin.getLocalExpansionManager().findExpansionByFile(file).isEmpty()) Files.deleteIfExists(target);
    }
    private static String reason(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException || error instanceof java.lang.reflect.InvocationTargetException) && error.getCause() != null) error = error.getCause();
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }
    public synchronized void close() {
        closed = true; busy = false; statuses.clear();
        completions.values().forEach(f -> f.completeExceptionally(new java.util.concurrent.CancellationException("PlaceholderAPI stopped"))); completions.clear();
        if (pendingArtifact != null) {
            try { rollbackFile(pendingArtifact); pendingArtifact = null; }
            catch (java.io.IOException e) { throw new IllegalStateException("Cancelled installation artifact cleanup failed", e); }
        }
    }
}
