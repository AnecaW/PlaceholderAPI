package me.clip.placeholderapi.expansion.manager;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.*;
import me.clip.placeholderapi.events.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.jar.*;

/** Expansion lifecycle is distinct from the enclosing Server Manager lifecycle. */
public final class LocalExpansionManager implements Listener {
    private final File folder;
    private final PlaceholderAPIPlugin plugin;
    private final Map<String, Runtime> expansions = new ConcurrentHashMap<>();
    private final Map<String, String> activationFailures = new ConcurrentHashMap<>();
    public String activationFailure(String identifier) { return activationFailures.get(identifier.toLowerCase(Locale.ROOT)); }
    private final Map<String, Runtime> failedCleanup = new ConcurrentHashMap<>();
    private final Map<Class<?>, Snapshot> discovered = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Map<PlaceholderExpansion, Snapshot> attaching = new IdentityHashMap<>();
    private final Set<String> waitingPlugins = new HashSet<>();
    private volatile boolean stopped;
    private boolean busy;
    private volatile long generation;
    private static final ThreadLocal<Runtime> CURRENT = new ThreadLocal<>();

    public static final class Runtime {
        public final PlaceholderExpansion expansion;
        private Snapshot snapshot;
        private final Set<BukkitTask> tasks = ConcurrentHashMap.newKeySet();
        private final List<Runnable> releases = new ArrayList<>();
        private Listener listener;
        private boolean stopCalled, cacheCleared, eventSent;
        private boolean closed;
        private int running;
        private long lastError;
        Runtime(PlaceholderExpansion expansion, Snapshot snapshot) { this.expansion = expansion; this.snapshot = snapshot; }
        public synchronized void own(BukkitTask task) { if (closed) { task.cancel(); throw new IllegalStateException("Expansion is stopping"); } tasks.add(task); }
        public synchronized void own(BukkitTask task, Runnable release) { if (closed) { release.run(); task.cancel(); throw new IllegalStateException("Expansion is stopping"); } tasks.add(task); releases.add(release); }
        public <T> T invoke(Supplier<T> callback) {
            synchronized (this) { if (closed) return null; running++; }
            Runtime previous = CURRENT.get(); Thread thread = Thread.currentThread(); ClassLoader before = thread.getContextClassLoader();
            try { CURRENT.set(this); thread.setContextClassLoader(expansion.getClass().getClassLoader()); return callback.get(); }
            finally { thread.setContextClassLoader(before); if (previous == null) CURRENT.remove(); else CURRENT.set(previous); synchronized (this) { running--; } }
        }
        private synchronized void stopTasks() {
            closed = true;
            for (BukkitTask task : tasks) task.cancel();
            tasks.clear();
            for (Runnable release : releases) release.run(); releases.clear();
            if (running != 0) throw new IllegalStateException("Expansion work still executing; retry unloading after it finishes");
        }
    }
    private static final class Snapshot implements AutoCloseable {
        private Class<? extends PlaceholderExpansion> type;
        private URLClassLoader loader;
        private Path file;
        private final Path source;
        private Runtime owner;
        Snapshot(Class<? extends PlaceholderExpansion> type, URLClassLoader loader, Path file, Path source) { this.type = type; this.loader = loader; this.file = file; this.source = source; }
        public synchronized void close() throws IOException {
            if (owner != null) synchronized (owner) { if (!owner.closed || owner.running != 0 || !owner.stopCalled || !owner.cacheCleared || !owner.eventSent) throw new IOException("Expansion cleanup is incomplete; loader retained"); }
            if (loader != null) loader.close();
            if (file != null) Files.deleteIfExists(file);
            type = null; loader = null; file = null; owner = null;
        }
    }
    public static Runtime currentRuntime() { return CURRENT.get(); }
    public static String request(PlaceholderExpansion expansion, Supplier<String> request) {
        var manager = PlaceholderAPIPlugin.getInstance().getLocalExpansionManager();
        Runtime runtime = manager.expansions.get(expansion.getIdentifier().toLowerCase(Locale.ROOT));
        if (runtime == null || runtime.expansion != expansion) return null;
        try {
            String value = runtime.invoke(request);
            if (value == null) PlaceholderAPI.reportRequestIssue("Expansion '" + expansion.getIdentifier() + "' returned no value: unknown parameters, unsupported player context, or inactive provider");
            return value;
        }
        catch (Exception | LinkageError e) {
            PlaceholderAPI.reportRequestIssue("Parsing exception in expansion '" + expansion.getIdentifier() + "': " + e);
            synchronized (runtime) { if (System.currentTimeMillis() - runtime.lastError > 30000) { runtime.lastError = System.currentTimeMillis(); manager.plugin.getLogger().log(Level.WARNING, "Placeholder request failed for " + expansion.getIdentifier() + " (throttled to once per 30s)", e); } }
            return null;
        }
    }
    public LocalExpansionManager(PlaceholderAPIPlugin plugin) {
        this.plugin = plugin; folder = new File(plugin.getDataFolder(), "expansions");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Cannot create expansion directory");
    }
    private void mainThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Expansion lifecycle requires the server thread"); }
    public Collection<PlaceholderExpansion> getExpansions() { return expansions.values().stream().map(r -> r.expansion).toList(); }
    public PlaceholderExpansion getExpansion(String identifier) { Runtime runtime = identifier == null ? null : expansions.get(identifier.toLowerCase(Locale.ROOT)); return runtime == null ? null : runtime.expansion; }
    public Optional<PlaceholderExpansion> findExpansionByIdentifier(String id) { return Optional.ofNullable(getExpansion(id)); }
    public Optional<PlaceholderExpansion> findExpansionByName(String name) { return getExpansions().stream().filter(e -> e.getName().equalsIgnoreCase(name)).findFirst(); }
    public File getExpansionsFolder() { return folder; }
    public Optional<PlaceholderExpansion> findExpansionByFile(File file) {
        Path source = file.toPath().toAbsolutePath().normalize();
        return expansions.values().stream().filter(r -> { Snapshot snapshot = r.snapshot; return snapshot != null && snapshot.source.toAbsolutePath().normalize().equals(source); }).map(r -> r.expansion).findFirst();
    }

    /** Activate one downloaded artifact without reloading unrelated installed expansions. */
    public CompletableFuture<PlaceholderExpansion> installAsync(File file, String expectedVersion) {
        mainThread();
        if (busy || stopped) return CompletableFuture.failedFuture(new IllegalStateException("Expansion manager is busy; try again after the current operation finishes"));
        busy = true; long epoch = generation;
        CompletableFuture<PlaceholderExpansion> result = new CompletableFuture<>(); pending.add(result);
        try {
            plugin.getModuleContext().schedule(() -> {
                Snapshot prepared;
                try { prepared = discover(file); }
                catch (Exception | LinkageError e) {
                    try { plugin.getModuleContext().schedule(() -> { busy = false; pending.remove(result); result.completeExceptionally(e); }, 0, 0, false); }
                    catch (RuntimeException closed) { result.completeExceptionally(e); }
                    return;
                }
                try { plugin.getModuleContext().schedule(() -> {
                    Runtime old = null; boolean removed = false; PlaceholderExpansion installed = null; Throwable failure = null;
                    try {
                        if (stopped || epoch != generation) throw new CancellationException("Installation cancelled: module stopped");
                        PlaceholderExpansion candidate = prepared.type.getDeclaredConstructor().newInstance();
                        candidate.setExpansionType(PlaceholderExpansion.Type.EXTERNAL);
                        if (!expectedVersion.equals(candidate.getVersion())) throw new IllegalStateException("Artifact version " + candidate.getVersion() + " does not match requested version " + expectedVersion);
                        String incompatibility = compatibilityFailure(candidate);
                        if (incompatibility != null) throw new IllegalStateException(incompatibility);
                        old = expansions.get(candidate.getIdentifier().toLowerCase(Locale.ROOT));
                        if (old != null) {
                            if (old.expansion.persist()) throw new IllegalStateException("This expansion is persistent; disable its provider before updating");
                            if (old.snapshot == null || !old.snapshot.source.toAbsolutePath().normalize().equals(file.toPath().toAbsolutePath().normalize())) throw new IllegalStateException("This identifier is already provided by another expansion; unload that provider before installing");
                            unregister(old.expansion, false); removed = true;
                        }
                        if (!activate(candidate, prepared)) throw new IllegalStateException(registrationRejection(candidate));
                        installed = candidate;
                        if (old != null && old.snapshot != null) close(old.snapshot);
                        try { Files.deleteIfExists(file.toPath().resolveSibling(file.getName() + ".previous")); }
                        catch (IOException e) { plugin.getLogger().warning("Expansion installed; previous artifact backup retained: " + e.getMessage()); }
                    } catch (Exception | LinkageError e) {
                        failure = e;
                        if (removed && old != null && !failedCleanup.containsKey(old.expansion.getIdentifier().toLowerCase(Locale.ROOT))) {
                            try {
                                PlaceholderExpansion restored = old.snapshot.type.getDeclaredConstructor().newInstance(); restored.setExpansionType(PlaceholderExpansion.Type.EXTERNAL);
                                if (!activate(restored, old.snapshot)) throw new IllegalStateException("Previous expansion could not be restored");
                                restoreFile(old.snapshot.file, old.snapshot.source);
                            } catch (Exception | LinkageError rollback) { e.addSuppressed(rollback); }
                        }
                    } finally {
                        if (installed == null && failedCleanup.values().stream().noneMatch(r -> r.snapshot == prepared)) close(prepared);
                        busy = false; pending.remove(result);
                    }
                    if (failure == null) result.complete(installed); else result.completeExceptionally(failure);
                }, 0, 0, false); }
                catch (RuntimeException e) { close(prepared); result.completeExceptionally(e); }
            }, 0, 0, true);
        } catch (RuntimeException e) { busy = false; pending.remove(result); result.completeExceptionally(e); }
        return result;
    }
    public void load(CommandSender sender) { reloadAsync(false).whenComplete((count, failure) -> plugin.report(sender, failure == null ? "Loaded " + count + " expansions." : "Expansion loading failed: " + failure.getMessage())); }
    public CompletableFuture<Integer> reloadAsync(boolean reload) {
        mainThread();
        if (busy || stopped) return CompletableFuture.failedFuture(new IllegalStateException("Expansion manager is busy or stopped"));
        busy = true; long epoch = generation;
        CompletableFuture<Integer> result = new CompletableFuture<>(); pending.add(result);
        try {
            plugin.getModuleContext().schedule(() -> {
                List<Snapshot> prepared = new ArrayList<>();
                try {
                    File[] files = folder.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                    if (files == null) throw new IOException("Cannot scan expansions directory");
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    List<String> errors = new ArrayList<>();
                    for (File file : files) {
                        try { prepared.add(discover(file)); }
                        catch (Exception | LinkageError e) {
                            Path previous = file.toPath().resolveSibling(file.getName() + ".previous");
                            if (Files.exists(previous)) Files.move(previous, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            errors.add(file.getName() + ": " + e.getMessage()); plugin.getLogger().log(Level.WARNING, "Cannot discover expansion " + file.getName(), e);
                        }
                    }
                    plugin.getModuleContext().schedule(() -> {
                        try {
                            if (stopped || epoch != generation) throw new CancellationException("Expansion reload cancelled");
                            if (reload && !errors.isEmpty()) throw new IllegalStateException("Reload cancelled; existing expansions retained: " + String.join("; ", errors));
                            int registered = 0;
                            Set<String> found = new HashSet<>();
                            for (Snapshot snapshot : prepared) {
                                try {
                                    PlaceholderExpansion expansion = snapshot.type.getDeclaredConstructor().newInstance();
                                    expansion.setExpansionType(PlaceholderExpansion.Type.EXTERNAL);
                                    String id = expansion.getIdentifier().toLowerCase(Locale.ROOT);
                                    if (!found.add(id)) throw new IllegalStateException("Duplicate installed expansion identifier " + id);
                                    Runtime old = expansions.get(id);
                                    if (old != null && (!reload || old.expansion.persist())) { snapshot.close(); continue; }
                                    String incompatibility = compatibilityFailure(expansion);
                                    if (incompatibility != null) throw new IllegalStateException(incompatibility);
                                    Snapshot backup = old == null ? null : old.snapshot;
                                    if (old != null) unregister(old.expansion, false);
                                    try {
                                        if (!activate(expansion, snapshot)) throw new IllegalStateException(registrationRejection(expansion));
                                        registered++;
                                        if (backup != null) close(backup);
                                        try { Files.deleteIfExists(snapshot.source.resolveSibling(snapshot.source.getFileName() + ".previous")); }
                                        catch (IOException retainedBackup) { plugin.getLogger().warning("Expansion activated; previous disk backup retained: " + retainedBackup.getMessage()); }
                                    } catch (Exception | LinkageError failure) {
                                        if (old != null && !failedCleanup.containsKey(id)) {
                                            PlaceholderExpansion restored = backup == null ? old.expansion.getClass().getDeclaredConstructor().newInstance() : backup.type.getDeclaredConstructor().newInstance();
                                            restored.setExpansionType(old.expansion.getExpansionType());
                                            if (!activate(restored, backup)) failure.addSuppressed(new IllegalStateException("Rollback registration rejected"));
                                            if (backup != null) restoreFile(backup.file, backup.source);
                                        }
                                        throw failure;
                                    }
                                } catch (Exception | LinkageError e) {
                                    boolean restored = false;
                                    for (Runtime existing : expansions.values()) if (existing.snapshot != null && existing.snapshot != snapshot && existing.snapshot.source.equals(snapshot.source)) { restoreFile(existing.snapshot.file, snapshot.source); restored = true; }
                                    Path previous = snapshot.source.resolveSibling(snapshot.source.getFileName() + ".previous");
                                    if (!restored && Files.exists(previous)) Files.move(previous, snapshot.source, StandardCopyOption.REPLACE_EXISTING);
                                    errors.add(e.toString()); plugin.getLogger().log(Level.WARNING, "Expansion initialization failed", e); if (failedCleanup.values().stream().noneMatch(r -> r.snapshot == snapshot)) snapshot.close();
                                }
                            }
                            if (reload && errors.isEmpty()) for (Runtime runtime : List.copyOf(expansions.values())) if (!runtime.expansion.persist() && !found.contains(runtime.expansion.getIdentifier().toLowerCase(Locale.ROOT))) unregister(runtime.expansion);
                            Bukkit.getPluginManager().callEvent(new ExpansionsLoadedEvent(List.copyOf(getExpansions())));
                            if (!errors.isEmpty()) throw new IllegalStateException("Loaded " + registered + " expansions; failures: " + String.join("; ", errors));
                            busy = false; pending.remove(result); result.complete(registered);
                        } catch (Exception | LinkageError e) { busy = false; pending.remove(result); result.completeExceptionally(e); }
                        finally { busy = false; pending.remove(result); for (Snapshot snapshot : prepared) if (snapshot.type != null && expansions.values().stream().noneMatch(r -> r.snapshot == snapshot) && failedCleanup.values().stream().noneMatch(r -> r.snapshot == snapshot)) close(snapshot); }
                    }, 0, 0, false);
                } catch (Exception | LinkageError e) {
                    for (Snapshot snapshot : prepared) close(snapshot);
                    try { plugin.getModuleContext().schedule(() -> { busy = false; pending.remove(result); result.completeExceptionally(e); }, 0, 0, false); }
                    catch (RuntimeException stoppedContext) { result.completeExceptionally(e); }
                }
            }, 0, 0, true);
        } catch (RuntimeException e) { busy = false; pending.remove(result); result.completeExceptionally(e); }
        return result;
    }
    @SuppressWarnings("unchecked") private Snapshot discover(File source) throws Exception {
        ECloudClient.validateArtifact(source.toPath());
        Path directory = plugin.getDataFolder().toPath().resolve("cache/runtime"); Files.createDirectories(directory);
        Path copy = Files.createTempFile(directory, "expansion-", ".jar");
        URLClassLoader loader = null;
        try {
            Files.copy(source.toPath(), copy, StandardCopyOption.REPLACE_EXISTING);
            loader = new URLClassLoader(new URL[]{copy.toUri().toURL()}, PlaceholderExpansion.class.getClassLoader()) {
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    try { return super.findClass(name); }
                    catch (ClassNotFoundException original) {
                        for (org.bukkit.plugin.Plugin other : Bukkit.getPluginManager().getPlugins()) {
                            try { return Class.forName(name, false, other.getClass().getClassLoader()); } catch (ClassNotFoundException ignored) { }
                        }
                        throw original;
                    }
                }
            };
            List<Class<? extends PlaceholderExpansion>> matches = new ArrayList<>(); Throwable failure = null;
            try (JarFile jar = new JarFile(copy.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.startsWith("me/clip/placeholderapi/")) continue;
                    try {
                        Class<?> type = loader.loadClass(name.substring(0, name.length() - 6).replace('/', '.'));
                        if (PlaceholderExpansion.class.isAssignableFrom(type) && !type.isInterface() && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())) matches.add((Class<? extends PlaceholderExpansion>) type);
                    } catch (ClassNotFoundException | LinkageError e) { failure = e; }
                }
            }
            if (matches.size() != 1) throw new IOException("Expected exactly one concrete PlaceholderExpansion; found " + matches.size() + (failure == null ? "" : "; missing/incompatible API: " + failure), failure);
            Snapshot snapshot = new Snapshot(matches.get(0), loader, copy, source.toPath());
            plugin.getModuleContext().manage(snapshot);
            return snapshot;
        } catch (Exception | LinkageError e) { if (loader != null) loader.close(); Files.deleteIfExists(copy); throw e; }
    }
    public CompletableFuture<Class<? extends PlaceholderExpansion>> findExpansionInFile(File file) {
        CompletableFuture<Class<? extends PlaceholderExpansion>> result = new CompletableFuture<>(); pending.add(result);
        plugin.getModuleContext().schedule(() -> {
            try {
                Snapshot snapshot = discover(file);
                if (stopped) { snapshot.close(); throw new CancellationException("Module stopped"); }
                discovered.put(snapshot.type, snapshot); result.complete(snapshot.type);
            } catch (Exception | LinkageError e) { result.completeExceptionally(e); }
            finally { pending.remove(result); }
        }, 0, 0, true);
        return result;
    }
    public Optional<PlaceholderExpansion> register(Class<? extends PlaceholderExpansion> type) {
        mainThread(); Snapshot snapshot = discovered.remove(type);
        try {
            PlaceholderExpansion expansion = type.getDeclaredConstructor().newInstance(); expansion.setExpansionType(PlaceholderExpansion.Type.EXTERNAL);
            if (activate(expansion, snapshot)) return Optional.of(expansion);
        } catch (Exception | LinkageError e) { plugin.getLogger().log(Level.WARNING, "Cannot register expansion " + type.getName(), e); }
        if (snapshot != null && failedCleanup.values().stream().noneMatch(r -> r.snapshot == snapshot)) close(snapshot);
        return Optional.empty();
    }
    public boolean register(PlaceholderExpansion expansion) { return register(expansion, attaching.get(expansion)); }
    private boolean activate(PlaceholderExpansion expansion, Snapshot snapshot) {
        mainThread(); attaching.put(expansion, snapshot);
        try {
            boolean accepted = expansion.register();
            if (accepted && getExpansion(expansion.getIdentifier()) != expansion) throw new IllegalStateException("Expansion register() returned success without calling the PlaceholderAPI registry: " + expansion.getIdentifier());
            if (!accepted && getExpansion(expansion.getIdentifier()) == expansion) unregister(expansion);
            if (accepted) activationFailures.remove(expansion.getIdentifier().toLowerCase(Locale.ROOT));
            else activationFailures.put(expansion.getIdentifier().toLowerCase(Locale.ROOT), registrationRejection(expansion));
            return accepted;
        } catch (RuntimeException | LinkageError failure) {
            activationFailures.put(expansion.getIdentifier().toLowerCase(Locale.ROOT), failure.toString());
            if (expansions.values().stream().anyMatch(runtime -> runtime.expansion == expansion)) {
                try { unregister(expansion); }
                catch (RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        } finally { attaching.remove(expansion); }
    }
    private boolean register(PlaceholderExpansion expansion, Snapshot snapshot) {
        mainThread(); String id = expansion.getIdentifier();
        if (stopped || id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9-]{0,127}")) throw new IllegalArgumentException("Invalid expansion identifier");
        id = id.toLowerCase(Locale.ROOT);
        if (failedCleanup.containsKey(id)) throw new IllegalStateException("Prior expansion cleanup failed; unload it before registering " + id);
        if (expansions.containsKey(id)) return false;
        String rejection = compatibilityFailure(expansion);
        if (rejection != null) { plugin.getLogger().warning(rejection); return false; }
        if (expansion instanceof Configurable configurable && configurable.getDefaults() != null) {
            boolean changed = false;
            for (var option : configurable.getDefaults().entrySet()) {
                if (option.getKey() == null || option.getKey().isBlank()) continue;
                String path = "expansions." + id + "." + option.getKey();
                if (!plugin.getConfig().contains(path)) { plugin.getConfig().set(path, option.getValue()); changed = true; }
            }
            if (changed) plugin.saveConfig();
        }
        ExpansionRegisterEvent event = new ExpansionRegisterEvent(expansion); Bukkit.getPluginManager().callEvent(event); if (event.isCancelled()) return false;
        Runtime runtime = new Runtime(expansion, snapshot); expansions.put(id, runtime);
        try {
            if (snapshot != null) snapshot.owner = runtime;
            if (expansion instanceof Listener) registerListeners(runtime);
            if (expansion instanceof Taskable taskable) runtime.invoke(() -> { taskable.start(); return null; });
            activationFailures.remove(id);
            return true;
        } catch (Exception | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Expansion start failed for " + id, e);
            try { unregister(expansion); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw new IllegalStateException("Expansion start failed: " + id, e);
        }
    }
    public boolean unregister(PlaceholderExpansion expansion) {
        return unregister(expansion, true);
    }
    private boolean unregister(PlaceholderExpansion expansion, boolean release) {
        mainThread(); String id = expansion.getIdentifier().toLowerCase(Locale.ROOT);
        Runtime runtime = expansions.get(id); if (runtime != null && runtime.expansion != expansion) return false;
        if (runtime == null) runtime = failedCleanup.get(id);
        if (runtime == null || runtime.expansion != expansion) return false;
        expansions.remove(id, runtime); failedCleanup.put(id, runtime);
        List<Throwable> failures = new ArrayList<>();
        if (runtime.listener != null) { HandlerList.unregisterAll(runtime.listener); runtime.listener = null; }
        // Stop accepting invocations before touching expansion state; retain it if a callback is running.
        runtime.stopTasks();
        try { if (!runtime.stopCalled && expansion instanceof Taskable taskable) taskable.stop(); runtime.stopCalled = true; } catch (Exception | LinkageError e) { failures.add(e); }
        try { if (!runtime.cacheCleared && expansion instanceof Cacheable cacheable) cacheable.clear(); runtime.cacheCleared = true; } catch (Exception | LinkageError e) { failures.add(e); }
        try { if (!runtime.eventSent) Bukkit.getPluginManager().callEvent(new ExpansionUnregisterEvent(expansion)); runtime.eventSent = true; } catch (RuntimeException e) { failures.add(e); }
        if (failures.isEmpty() && release && runtime.snapshot != null) { try { runtime.snapshot.close(); runtime.snapshot = null; } catch (IOException e) { failures.add(e); } }
        if (!failures.isEmpty()) {
            IllegalStateException error = new IllegalStateException("Cleanup incomplete for expansion " + id + "; no longer registered; retry unload");
            failures.forEach(error::addSuppressed); throw error;
        }
        failedCleanup.remove(id, runtime); return true;
    }
    public boolean unload(String identifier) {
        Runtime runtime = expansions.get(identifier.toLowerCase(Locale.ROOT)); if (runtime == null) runtime = failedCleanup.get(identifier.toLowerCase(Locale.ROOT));
        return runtime != null && unregister(runtime.expansion);
    }
    public void kill() {
        mainThread(); stopped = true; generation++;
        for (CompletableFuture<?> future : pending) future.completeExceptionally(new CancellationException("PlaceholderAPI module stopped")); pending.clear();
        List<Throwable> failures = new ArrayList<>();
        Set<Runtime> all = Collections.newSetFromMap(new IdentityHashMap<>()); all.addAll(expansions.values()); all.addAll(failedCleanup.values());
        for (Runtime runtime : all) try { unregister(runtime.expansion); } catch (RuntimeException e) { failures.add(e); }
        for (Snapshot snapshot : discovered.values()) try { snapshot.close(); } catch (IOException e) { failures.add(e); } discovered.clear();
        if (!failures.isEmpty()) { IllegalStateException error = new IllegalStateException("Expansion shutdown incomplete"); failures.forEach(error::addSuppressed); throw error; }
    }
    private void close(Snapshot snapshot) { try { snapshot.close(); } catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Expansion loader cleanup failed", e); } }
    private static void restoreFile(Path original, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".restore-", ".tmp");
        try {
            Files.copy(original, temporary, StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public String compatibilityFailure(PlaceholderExpansion expansion) {
        String failure = checkCompatibility(expansion);
        if (failure != null && expansion.getIdentifier() != null) {
            if (activationFailures.size() > 200) activationFailures.clear();
            activationFailures.put(expansion.getIdentifier().toLowerCase(Locale.ROOT), failure);
        }
        return failure;
    }
    private String checkCompatibility(PlaceholderExpansion expansion) {
        String prefix = "Failed to register expansion '" + expansion.getIdentifier() + "' (" + expansion.getVersion() + "). ";
        if (expansion.getAuthor() == null || expansion.getVersion() == null) return prefix + "Expansion author/version metadata is missing.";
        String required = expansion.getRequiredPlugin();
        org.bukkit.plugin.Plugin external = required == null || required.isBlank() ? null : Bukkit.getPluginManager().getPlugin(required);
        if (required != null && !required.isBlank()) {
            String dependency = "Required dependency: " + required + ". Dependency type: External Paper plugin. ";
            if (external == null) {
                waitingPlugins.add(required);
                var internal = plugin.getModuleContext().findModule(required);
                return prefix + dependency + "Status: Not found. " + internal.map(m -> "An internal module named " + m.id() + " exists (" + m.state() + "), but does not satisfy a native plugin dependency. Modules must provide their placeholders through PlaceholderService. ").orElse("") + "The expansion was downloaded, but activation requires the matching external plugin installed and enabled.";
            }
            if (!external.isEnabled()) { waitingPlugins.add(required); return prefix + dependency + "Status: Found but disabled (" + external.getName() + " " + external.getDescription().getVersion() + "). Enable it and retry installing/reloading the expansion."; }
        }
        try {
            if (!expansion.canRegister()) return prefix + "Status: expansion canRegister() rejected activation. " + (external == null ? "Its custom compatibility check failed." : "External plugin " + external.getName() + " " + external.getDescription().getVersion() + " is enabled, but its version/API did not satisfy the expansion's check. Check the expansion's supported dependency versions and its server log diagnostic.");
            if (expansion instanceof VersionSpecific specific && !specific.isCompatibleWith(PlaceholderAPIPlugin.getServerVersion())) return prefix + "Status: server version " + Bukkit.getBukkitVersion() + " is unsupported by this expansion.";
        } catch (LinkageError e) { return prefix + "Status: incompatible or missing dependency API class: " + e + ". Check the external plugin and expansion versions."; }
        return null;
    }
    private String registrationRejection(PlaceholderExpansion expansion) {
        String failure = compatibilityFailure(expansion);
        if (failure != null) return failure;
        return "Expansion '" + expansion.getIdentifier() + "' register() hook rejected activation although declared dependencies are available. Its initialization/API lookup or a registration event failed; check the expansion log and matching plugin API versions.";
    }
    @SuppressWarnings("unchecked") private void registerListeners(Runtime runtime) {
        Listener wrapper = new Listener() {}; runtime.listener = wrapper;
        Set<String> methods = new HashSet<>();
        for (Class<?> type = runtime.expansion.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (var method : type.getDeclaredMethods()) {
                EventHandler handler = method.getAnnotation(EventHandler.class);
                if (handler == null || method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0]) || !methods.add(method.getName() + "(" + method.getParameterTypes()[0].getName() + ")")) continue;
                method.setAccessible(true);
                Class<? extends Event> eventType = (Class<? extends Event>) method.getParameterTypes()[0];
                Bukkit.getPluginManager().registerEvent(eventType, wrapper, handler.priority(), (listener, event) -> {
                    if (!eventType.isInstance(event)) return;
                    runtime.invoke(() -> {
                        try { method.invoke(runtime.expansion, event); return null; }
                        catch (ReflectiveOperationException e) { throw new IllegalStateException("Expansion event handler failed", e); }
                    });
                }, plugin.getModuleContext().getHostPlugin(), handler.ignoreCancelled());
            }
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        for (Runtime runtime : expansions.values()) if (runtime.expansion instanceof Cleanable cleanable) {
            try { runtime.invoke(() -> { cleanable.cleanup(event.getPlayer()); return null; }); }
            catch (Exception | LinkageError e) { plugin.getLogger().log(Level.WARNING, "Expansion player cleanup failed: " + runtime.expansion.getIdentifier(), e); }
        }
    }
    @EventHandler public void pluginDisabled(org.bukkit.event.server.PluginDisableEvent event) {
        if (stopped) return;
        for (Runtime runtime : List.copyOf(expansions.values())) {
            String required = runtime.expansion.getRequiredPlugin();
            if (required != null && (required.equalsIgnoreCase(event.getPlugin().getName()) || Bukkit.getPluginManager().getPlugin(required) == event.getPlugin())) {
                waitingPlugins.add(required);
                try { unregister(runtime.expansion); }
                catch (RuntimeException e) { plugin.getLogger().log(Level.WARNING, "Dependent expansion cleanup failed", e); }
            }
        }
    }
    @EventHandler public void pluginEnabled(org.bukkit.event.server.PluginEnableEvent event) {
        if (!stopped && waitingPlugins.stream().anyMatch(name -> name.equalsIgnoreCase(event.getPlugin().getName()) || Bukkit.getPluginManager().getPlugin(name) == event.getPlugin())) {
            if (!busy) load(Bukkit.getConsoleSender());
            else plugin.getLogger().info("External dependency became enabled during expansion loading; run /papi reload after the current operation finishes if it is still missing");
        }
    }
}
