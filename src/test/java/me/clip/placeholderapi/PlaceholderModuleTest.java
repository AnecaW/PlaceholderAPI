package me.clip.placeholderapi;

import me.clip.placeholderapi.expansion.*;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import me.clip.placeholderapi.expansion.manager.*;
import org.wannes.plugModCore.api.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.plugin.*;
import org.bukkit.event.*;
import org.bukkit.scheduler.BukkitTask;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import java.util.logging.Logger;
import javax.tools.ToolProvider;
import static org.junit.Assert.*;

/** Real expansion bytecode runs against a deliberately small Bukkit component test double. */
public class PlaceholderModuleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static Thread primary;
    static final List<String> messages = new ArrayList<>();
    static final Set<String> permissions = new HashSet<>();
    static boolean rejectRegistration;
    static Plugin host;
    static final Map<String,Plugin> nativePlugins = new HashMap<>();
    static CommandSender sender;
    Context context;
    PlaceholderAPIPlugin plugin;
    static Object empty(Class<?> type) {
        if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L;
        if (type.isArray()) return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        return null;
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) { return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler); }
    @BeforeClass public static void server() throws Exception {
        primary = Thread.currentThread();
        sender = proxy(ConsoleCommandSender.class, (p, m, a) -> {
            if (m.getName().equals("sendMessage")) { if (a[0] instanceof String s) messages.add(s); return null; }
            if (m.getName().equals("hasPermission")) return permissions.contains(a[0]);
            return empty(m.getReturnType());
        });
        PluginManager plugins = proxy(PluginManager.class, (p, m, a) -> {
            if (m.getName().equals("getPlugin")) return nativePlugins.get(a[0]);
            if (m.getName().equals("getPlugins")) return nativePlugins.values().toArray(Plugin[]::new);
            if (m.getName().equals("isPluginEnabled")) { Plugin found = a[0] instanceof Plugin n ? n : nativePlugins.get(a[0]); return found != null && found.isEnabled(); }
            if (m.getName().equals("callEvent") && rejectRegistration && a[0] instanceof me.clip.placeholderapi.events.ExpansionRegisterEvent event) event.setCancelled(true);
            if (m.getName().equals("registerEvent")) {
                var handlers = (HandlerList)((Class<?>)a[0]).getMethod("getHandlerList").invoke(null);
                handlers.register(new RegisteredListener((Listener)a[1], (org.bukkit.plugin.EventExecutor)a[3], (EventPriority)a[2], (Plugin)a[4], (boolean)a[5]));
            }
            return empty(m.getReturnType());
        });
        Server server = proxy(Server.class, (p, m, a) -> switch(m.getName()) {
            case "isPrimaryThread" -> Thread.currentThread() == primary;
            case "getPluginManager" -> plugins;
            case "getConsoleSender" -> sender;
            case "getOnlinePlayers" -> List.of();
            case "getBukkitVersion", "getVersion" -> "1.21.11-R0.1-SNAPSHOT";
            case "getLogger" -> Logger.getLogger("papi-test");
            default -> empty(m.getReturnType());
        });
        var field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, server);
        host = proxy(Plugin.class, (p, m, a) -> empty(m.getReturnType()));
    }
    static class Context implements ModuleContext {
        final File data;
        final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        final ExecutorService workers = Executors.newSingleThreadExecutor();
        final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
        final Map<Class<?>, Object> services = new HashMap<>();
        final Map<String,ModuleStatus> modules = new HashMap<>();
        final Map<String, CommandExecutor> commands = new HashMap<>();
        final List<AtomicBoolean> taskFlags = new CopyOnWriteArrayList<>();
        Context(File data) { this.data = data; }
        public String getModuleId() { return "placeholderapi"; }
        public String getModuleName() { return "PlaceholderAPI"; }
        public String getVersion() { return "test"; }
        public Logger getLogger() { return Logger.getLogger("papi-module-test"); }
        public File getDataFolder() { return data; }
        public Plugin getHostPlugin() { return host; }
        public void registerListener(Listener l) {}
        public BukkitTask schedule(Runnable action, long delay, long period, boolean async) {
            AtomicBoolean cancelled = new AtomicBoolean(); taskFlags.add(cancelled);
            Runnable guarded = () -> { if (!cancelled.get()) action.run(); };
            if (async) workers.submit(guarded); else main.add(guarded);
            return proxy(BukkitTask.class, (p, m, a) -> { if (m.getName().equals("cancel")) cancelled.set(true); if (m.getName().equals("isCancelled")) return cancelled.get(); return empty(m.getReturnType()); });
        }
        public <T extends AutoCloseable> T manage(T resource) { resources.add(resource); return resource; }
        public <T> void registerService(Class<T> type, T service) { services.put(type, service); }
        public <T> T getService(Class<T> type) { return type.cast(services.get(type)); }
        public Optional<ModuleStatus> findModule(String name) { return Optional.ofNullable(modules.get(name)); }
        public void registerIncomingChannel(String c, org.bukkit.plugin.messaging.PluginMessageListener l) {}
        public void registerOutgoingChannel(String c) {}
        public void registerCommand(String n, CommandExecutor e, String d, String u, String... a) { commands.put(n, e); }
        void drain() { Runnable r; while ((r = main.poll()) != null) r.run(); }
        <T> T await(CompletableFuture<T> future) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!future.isDone() && System.nanoTime() < deadline) { drain(); Thread.sleep(2); }
            drain(); return future.get(1, TimeUnit.SECONDS);
        }
        void idle() throws Exception { workers.submit(() -> {}).get(15, TimeUnit.SECONDS); drain(); }
        void close() throws Exception { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); for (int i = resources.size()-1; i >= 0; i--) resources.get(i).close(); }
    }
    @Before public void start() throws Exception {
        permissions.clear(); messages.clear(); nativePlugins.clear(); rejectRegistration = false;
        context = new Context(temp.newFolder());
        Files.writeString(context.data.toPath().resolve("config.yml"), "cloud_enabled: false\n");
        PlaceholderAPIPlugin.init(context); plugin = PlaceholderAPIPlugin.getInstance(); context.idle();
    }
    @After public void stop() throws Exception { rejectRegistration = false; PlaceholderAPIPlugin.shutdown(); context.close(); }
    static class Echo extends PlaceholderExpansion {
        public String getIdentifier() { return "echo"; } public String getAuthor() { return "test"; } public String getVersion() { return "1"; }
        public String onRequest(OfflinePlayer p, String params) { return params.equals("unknown") ? null : params.equals("throw") ? failRequest() : params.equals("token") ? "%echo_name%" : params; }
        String failRequest() { throw new IllegalArgumentException("fixture request error"); }
    }
    void echo() { assertTrue(new Echo().register()); }
    @Test public void detailedParsingAndCommandDiagnoseSyntaxUnknownParametersAndExceptions() {
        echo();var service=context.getService(PlaceholderService.class);
        var result=service.parseDetailed(null,"%online% %missing_value% %echo_unknown% %echo_throw% %echo_ok%");
        assertEquals("%online% %missing_value% %echo_unknown% %echo_throw% ok",result.text());
        assertTrue(result.diagnostics().stream().anyMatch(s->s.contains("syntax")));
        assertTrue(result.diagnostics().stream().anyMatch(s->s.contains("not registered")));
        assertTrue(result.diagnostics().stream().anyMatch(s->s.contains("returned no value")));
        assertTrue(result.diagnostics().stream().anyMatch(s->s.contains("fixture request error")));
        assertTrue(service.parseDetailed(null,"%echo_ok%").diagnostics().isEmpty());
        assertEquals("ok",context.renderText(null,"%echo_ok%"));
        permissions.add("placeholderapi.admin");context.commands.get("papi").onCommand(sender,null,"papi",new String[]{"parse","--null","%online%"});
        assertTrue(messages.stream().anyMatch(s->s.contains("server_online")));
    }
    @Test public void parseDiagnosticsReportActualActivationFailureAndMissingService() {
        assertFalse(new Echo(){public String getRequiredPlugin(){return "MissingDependency";}}.register());
        var result=context.getService(PlaceholderService.class).parseDetailed(null,"%echo_value%");
        assertTrue(result.diagnostics().getFirst().contains("MissingDependency"));
        assertTrue(result.diagnostics().getFirst().contains("Not found"));
        context.services.clear();permissions.add("placeholderapi.admin");
        context.commands.get("papi").onCommand(sender,null,"papi",new String[]{"parse","--null","%echo_value%"});
        assertTrue(messages.getLast().contains("requires service"));
    }
    Plugin nativePlugin(String version, boolean enabled) {
        PluginDescriptionFile description = new PluginDescriptionFile("PlayerStats", version, "fixture.PlayerStats");
        return proxy(Plugin.class, (p,m,a) -> switch(m.getName()) {
            case "getName" -> "PlayerStats"; case "getDescription" -> description; case "isEnabled" -> enabled;
            default -> empty(m.getReturnType());
        });
    }
    @Test public void playerStatsDependencyDiagnosticsAndVersionChecks() {
        Echo expansion = new Echo() {
            public String getRequiredPlugin(){ return "PlayerStats"; }
            public boolean canRegister(){ Plugin dependency=Bukkit.getPluginManager().getPlugin("PlayerStats"); return dependency!=null && dependency.getDescription().getVersion().startsWith("2"); }
        };
        var manager=plugin.getLocalExpansionManager();
        String missing=manager.compatibilityFailure(expansion);
        assertTrue(missing,missing.contains("External Paper plugin")); assertTrue(missing,missing.contains("Not found"));
        nativePlugins.put("PlayerStats",nativePlugin("2.4",false));
        assertTrue(manager.compatibilityFailure(expansion).contains("disabled"));
        nativePlugins.put("PlayerStats",nativePlugin("1.9",true));
        String wrong=manager.compatibilityFailure(expansion); assertTrue(wrong,wrong.contains("1.9")); assertTrue(wrong,wrong.contains("canRegister"));
        nativePlugins.put("PlayerStats",nativePlugin("2.4",true));
        assertNull(manager.compatibilityFailure(expansion)); assertTrue(expansion.register());
    }
    @Test public void downloadedExpansionRunsItsRegistrationInitializationHook() throws Exception {
        install(fixture("1","public static boolean initialized; public boolean register(){initialized=true;return super.register();}"));
        context.await(plugin.getLocalExpansionManager().reloadAsync(true));
        var expansion=plugin.getLocalExpansionManager().getExpansion("fixture");
        assertTrue(expansion.getClass().getField("initialized").getBoolean(null));
        OfflinePlayer player=proxy(OfflinePlayer.class,(p,m,a)->m.getName().equals("getName")?"Alice":empty(m.getReturnType()));
        assertEquals("Alice:value",context.getService(PlaceholderService.class).parse(player,"%fixture_value%"));
        context.await(plugin.getLocalExpansionManager().reloadAsync(true));
        assertTrue(plugin.getLocalExpansionManager().getExpansion("fixture").getClass().getField("initialized").getBoolean(null));
    }
    @Test public void internalModuleDoesNotMasqueradeAsRequiredNativePlugin() {
        context.modules.put("PlayerStats",new ModuleStatus("playerstats","PlayerStats","2.0","ENABLED"));
        String failure=plugin.getLocalExpansionManager().compatibilityFailure(new Echo(){public String getRequiredPlugin(){return "PlayerStats";}});
        assertTrue(failure,failure.contains("internal module"));assertTrue(failure,failure.contains("does not satisfy"));
    }
    @Test public void nativeDependencyDisableUnregistersAndEnableReloadsInstalledExpansion() throws Exception {
        Plugin dependency=nativePlugin("2.4",true);nativePlugins.put("PlayerStats",dependency);
        install(fixture("1","public String getRequiredPlugin(){return \"PlayerStats\";}"));
        context.await(plugin.getLocalExpansionManager().reloadAsync(true));assertTrue(PlaceholderAPI.isRegistered("fixture"));
        plugin.getLocalExpansionManager().pluginDisabled(new org.bukkit.event.server.PluginDisableEvent(dependency));
        assertFalse(PlaceholderAPI.isRegistered("fixture"));
        plugin.getLocalExpansionManager().pluginEnabled(new org.bukkit.event.server.PluginEnableEvent(dependency));
        context.idle();assertTrue(PlaceholderAPI.isRegistered("fixture"));
    }
    @Test public void rejectedRegistrationHookDoesNotLeaveAnExpansionInstalled() throws Exception {
        install(fixture("1","public boolean register(){return false;}"));
        assertThrows(ExecutionException.class,()->context.await(plugin.getLocalExpansionManager().reloadAsync(true)));
        assertFalse(PlaceholderAPI.isRegistered("fixture"));
    }
    @Test public void registrationHookExceptionAfterSuperRegistrationCleansRegistryAndSnapshot() throws Exception {
        install(fixture("1","public boolean register(){super.register();throw new IllegalStateException(\"after registration\");}"));
        assertThrows(ExecutionException.class,()->context.await(plugin.getLocalExpansionManager().reloadAsync(true)));
        assertFalse(PlaceholderAPI.isRegistered("fixture"));
        try(var files=Files.list(context.data.toPath().resolve("cache/runtime"))){assertEquals(0,files.count());}
    }
    @Test public void moduleOwnedPlaceholderProviderUsesPlayerContextAndCleansUp() throws Exception {
        PlaceholderService service=context.getService(PlaceholderService.class);
        var registration=service.register(context,new PlaceholderProvider(){
            public String identifier(){return "module";}
            public String resolve(OfflinePlayer player,String parameters){return player==null?"global:"+parameters:player.getName()+":"+parameters;}
        });
        OfflinePlayer player=proxy(OfflinePlayer.class,(p,m,a)->m.getName().equals("getName")?"Bob":empty(m.getReturnType()));
        assertEquals("Bob:kills",service.parse(player,"%module_kills%"));
        assertEquals("global:kills",service.parse(null,"%module_kills%"));
        assertThrows(IllegalStateException.class,()->service.register(context,new PlaceholderProvider(){public String identifier(){return "module";}public String resolve(OfflinePlayer p,String s){return s;}}));
        registration.close(); registration.close(); assertEquals("%module_kills%",service.parse(player,"%module_kills%"));
    }
    @Test public void initializesCommandAndService() { assertNotNull(context.commands.get("papi")); assertNotNull(context.getService(PlaceholderService.class)); }
    @Test public void parsesMultipleAndParametersWithoutRecursion() { echo(); assertEquals("name a_b %echo_name%", PlaceholderAPI.setPlaceholders((OfflinePlayer)null, "%echo_name% %echo_a_b% %echo_token%")); }
    @Test public void preservesMissingUnknownMalformedAndNull() { echo(); assertEquals("%missing_name% %echo_unknown% %echo_name", PlaceholderAPI.setPlaceholders((OfflinePlayer)null, "%missing_name% %echo_unknown% %echo_name")); assertNull(PlaceholderAPI.setPlaceholders((OfflinePlayer)null,(String)null)); assertEquals("plain", PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"plain")); }
    @Test public void bracketsAreSeparateAndExceptionsPreserveToken() { echo(); assertEquals("{echo_name}",PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"{echo_name}")); assertEquals("name",PlaceholderAPI.setBracketPlaceholders(null,"{echo_name}")); assertEquals("%echo_throw%",PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"%echo_throw%")); }
    @Test public void registrationDuplicatesAndStaleInstances() { Echo a=new Echo(), b=new Echo(); assertTrue(a.register()); assertFalse(b.register()); assertFalse(b.isRegistered()); assertTrue(a.unregister()); assertTrue(b.register()); assertFalse(a.unregister()); assertFalse(a.isRegistered()); }
    @Test public void missingDependencyAndApiVersionRejected() { assertFalse(new Echo(){ public String getRequiredPlugin(){return "missing";} }.register()); class Wrong extends Echo implements VersionSpecific { public boolean isCompatibleWith(Version v){return false;} } assertFalse(new Wrong().register()); }
    @Test public void registrationEventCanReject() { rejectRegistration=true; assertFalse(new Echo().register()); assertTrue(PlaceholderAPI.getRegisteredIdentifiers().isEmpty()); }
    @Test public void configurationDefaultsRetainUserSettings() { plugin.getConfig().set("expansions.echo.setting", "custom"); class Config extends Echo implements Configurable { public Map<String,Object> getDefaults(){return Map.of("setting","default","new",123);} } assertTrue(new Config().register()); assertEquals("custom",plugin.getConfig().getString("expansions.echo.setting")); assertEquals(123,plugin.getConfig().getInt("expansions.echo.new")); }
    @Test public void relationalContract() { class Relation extends Echo implements Relational { public String onPlaceholderRequest(org.bukkit.entity.Player a,org.bukkit.entity.Player b,String params){return "relation:"+params;} } assertTrue(new Relation().register()); assertEquals("relation:value %rel_missing_x%",PlaceholderAPI.setRelationalPlaceholders(null,null,"%rel_echo_value% %rel_missing_x%")); }
    @Test public void permissionAndTabCompletion() { var router=(TabCompleter)context.commands.get("papi"); assertTrue(router.onTabComplete(sender,null,"papi",new String[]{""}).isEmpty()); context.commands.get("papi").onCommand(sender,null,"papi",new String[]{"reload"}); assertTrue(messages.getLast().contains("lack permission")); permissions.add("placeholderapi.admin"); assertTrue(router.onTabComplete(sender,null,"papi",new String[]{"re"}).contains("reload")); }
    @Test public void stopsOwnedTasksAndCallbacks() throws Exception { AtomicInteger stopped=new AtomicInteger(); class Tasks extends Echo implements Taskable, Cacheable { public void start(){plugin.getScheduler().runTaskTimer(()->{},1,1);} public void stop(){stopped.incrementAndGet();} public void clear(){stopped.incrementAndGet();} } Tasks t=new Tasks(); assertTrue(t.register()); assertTrue(t.unregister()); assertEquals(2,stopped.get()); assertTrue(context.taskFlags.getLast().get()); }
    @Test public void activeInvocationBlocksUnsafeUnloadUntilRetry() throws Exception { CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1); Echo a=new Echo(){public String onRequest(OfflinePlayer p,String s){entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return s;}};assertTrue(a.register()); var f=CompletableFuture.supplyAsync(()->PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"%echo_x%"));assertTrue(entered.await(3,TimeUnit.SECONDS));assertThrows(IllegalStateException.class,a::unregister);release.countDown();f.get();assertTrue(plugin.getLocalExpansionManager().unload("echo")); }
    Path fixture(String version, String extra) throws Exception {
        return fixture(version, "", extra);
    }
    Path fixture(String version, String interfaces, String extra) throws Exception {
        Path dir=temp.newFolder().toPath(); Path source=dir.resolve("Fixture.java");
        Files.writeString(source,"package fixture; public class Fixture extends me.clip.placeholderapi.expansion.PlaceholderExpansion "+interfaces+" { public String getIdentifier(){return \"fixture\";} public String getAuthor(){return \"tests\";} public String getVersion(){return \""+version+"\";} public String onRequest(org.bukkit.OfflinePlayer player,String params){return player==null?params:player.getName()+\":\"+params;} "+extra+" }");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),source.toString()));
        Path output=dir.resolve("fixture.jar"); try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(output))){jar.putNextEntry(new JarEntry("fixture/Fixture.class"));jar.write(Files.readAllBytes(dir.resolve("fixture/Fixture.class")));jar.closeEntry();} return output;
    }
    Path install(Path jar) throws Exception { Path target=plugin.getLocalExpansionManager().getExpansionsFolder().toPath().resolve("Expansion-fixture.jar");Files.copy(jar,target,StandardCopyOption.REPLACE_EXISTING);return target; }
    @Test public void realJarDiscoveryLoadReloadUnregisterAndStorage() throws Exception { Path target=install(fixture("1","")); assertEquals(1,(int)context.await(plugin.getLocalExpansionManager().reloadAsync(true))); assertEquals("a_b",PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"%fixture_a_b%"));var old=plugin.getLocalExpansionManager().getExpansion("fixture");assertEquals(1,(int)context.await(plugin.getLocalExpansionManager().reloadAsync(true)));assertFalse(old.isRegistered());assertEquals(1,PlaceholderAPI.getRegisteredIdentifiers().size());assertTrue(plugin.getLocalExpansionManager().unload("fixture"));assertEquals("%fixture_x%",PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"%fixture_x%"));assertTrue(Files.exists(target));assertEquals(0,Files.list(context.data.toPath().resolve("cache/runtime")).count()); }
    @Test public void failedUpdateRestoresWorkingRuntimeAndInstalledJar() throws Exception { Path original=fixture("1","");Path installed=install(original);context.await(plugin.getLocalExpansionManager().reloadAsync(true)); install(fixture("2","public boolean canRegister(){return false;}"));assertThrows(ExecutionException.class,()->context.await(plugin.getLocalExpansionManager().reloadAsync(true)));assertEquals("1",plugin.getLocalExpansionManager().getExpansion("fixture").getVersion());assertArrayEquals(Files.readAllBytes(original),Files.readAllBytes(installed));assertEquals("x",PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"%fixture_x%")); }
    @Test public void incompatibleJarPreservesRegistry() throws Exception {install(fixture("1",""));context.await(plugin.getLocalExpansionManager().reloadAsync(true));Files.writeString(plugin.getLocalExpansionManager().getExpansionsFolder().toPath().resolve("broken.jar"),"html");assertThrows(ExecutionException.class,()->context.await(plugin.getLocalExpansionManager().reloadAsync(true)));assertNotNull(plugin.getLocalExpansionManager().getExpansion("fixture")); }
    @Test public void persistentExpansionSurvivesReload() throws Exception { Echo a=new Echo(){public boolean persist(){return true;}};assertTrue(a.register());context.await(plugin.getLocalExpansionManager().reloadAsync(true));assertTrue(a.isRegistered()); }
    CloudExpansion entry() throws Exception { return ECloudClient.parseCatalogue("{\"fixture\":{\"author\":\"test\",\"latest_version\":\"1\",\"versions\":[{\"version\":\"1\",\"verified\":true,\"url\":\"https://dl.placeholderapi.com/fixture.jar\"}]}}").get("fixture"); }
    @Test public void downloadExecutesExpansionAndPersistsAcrossModuleRestart() throws Exception {byte[] bytes=Files.readAllBytes(fixture("1",""));var e=entry();var client=new ECloudClient(uri->new ByteArrayInputStream(bytes),1000000);Path target=client.download(e,e.getVersion(),plugin.getLocalExpansionManager().getExpansionsFolder().toPath());context.await(plugin.getLocalExpansionManager().reloadAsync(true));assertEquals("x",PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"%fixture_x%"));PlaceholderAPIPlugin.shutdown();context.close();context=new Context(target.getParent().getParent().toFile());PlaceholderAPIPlugin.init(context);plugin=PlaceholderAPIPlugin.getInstance();context.idle();assertEquals("x",PlaceholderAPI.setPlaceholders((OfflinePlayer)null,"%fixture_x%")); }
    @Test public void invalidInterruptedOversizedDownloadsRetainInstalledFile() throws Exception { Path original=install(fixture("1",""));byte[] before=Files.readAllBytes(original);var e=entry();assertThrows(IOException.class,()->new ECloudClient(u->new ByteArrayInputStream("<html>".getBytes()),1000).download(e,e.getVersion(),original.getParent()));assertArrayEquals(before,Files.readAllBytes(original));assertThrows(IOException.class,()->new ECloudClient(u->new ByteArrayInputStream(before),1).download(e,e.getVersion(),original.getParent()));assertThrows(InterruptedIOException.class,()->new ECloudClient(u->new ByteArrayInputStream(before),1000000).download(e,e.getVersion(),original.getParent(),()->true));assertArrayEquals(before,Files.readAllBytes(original));assertEquals(1,Files.list(original.getParent()).count()); }
    @Test public void validatesMetadataAndTrustedDestinations() throws Exception {for(String url:List.of("http://dl.placeholderapi.com/x","https://evil.example/x","https://dl.placeholderapi.com@evil.example/x","https://dl.placeholderapi.com:444/x"))assertThrows(IOException.class,()->ECloudClient.trusted(url));assertThrows(IOException.class,()->ECloudClient.parseCatalogue("bad"));assertThrows(IOException.class,()->ECloudClient.parseCatalogue("{}"));var e=entry();e.setName("../escape");assertThrows(IOException.class,()->new ECloudClient().download(e,e.getVersion(),temp.newFolder().toPath())); }
    @Test public void networkErrorsAndUnapprovedVersionsAreReported() throws Exception {var e=entry();assertThrows(IOException.class,()->new ECloudClient(u->{throw new IOException("network failure");},1000).download(e,e.getVersion(),temp.newFolder().toPath()));e.getVersion().setVerified(false);assertThrows(IOException.class,()->new ECloudClient().download(e,e.getVersion(),temp.newFolder().toPath())); }
    @Test public void asynchronousCloudRefreshAndDownload() throws Exception {byte[] bytes=Files.readAllBytes(fixture("1",""));var e=entry();String json=new com.google.gson.Gson().toJson(Map.of("fixture",e));AtomicBoolean networkOffMain=new AtomicBoolean();plugin.getConfig().set("cloud_enabled",true);CloudExpansionManager cloud=new CloudExpansionManager(plugin,new ECloudClient(uri->{networkOffMain.set(!Bukkit.isPrimaryThread());return new ByteArrayInputStream(uri.equals(ECloudClient.CATALOGUE)?json.getBytes():bytes);},1000000));var entries=context.await(cloud.refresh());File artifact=context.await(cloud.downloadExpansion(entries.get("fixture"),entries.get("fixture").getVersion()));assertTrue(networkOffMain.get());assertTrue(artifact.isFile());cloud.kill(); }
    @Test public void startFailureRollsBackFreshInstanceAndDisk() throws Exception {
        Path original = fixture("1", ""); Path installed = install(original); context.await(plugin.getLocalExpansionManager().reloadAsync(true));
        var old = plugin.getLocalExpansionManager().getExpansion("fixture");
        install(fixture("2", "implements me.clip.placeholderapi.expansion.Taskable", "public void start(){throw new IllegalStateException(\"start failure\");} public void stop(){}"));
        assertThrows(ExecutionException.class, () -> context.await(plugin.getLocalExpansionManager().reloadAsync(true)));
        assertFalse(old.isRegistered()); assertEquals("1", plugin.getLocalExpansionManager().getExpansion("fixture").getVersion());
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(installed)); assertEquals("x", PlaceholderAPI.setPlaceholders((OfflinePlayer)null, "%fixture_x%"));
    }
    @Test public void moduleShutdownRemovesParsersAndClosesSnapshots() throws Exception {
        install(fixture("1", "")); context.await(plugin.getLocalExpansionManager().reloadAsync(true));
        PlaceholderAPIPlugin.shutdown(); assertEquals("%fixture_x%", PlaceholderAPI.setPlaceholders((OfflinePlayer)null, "%fixture_x%"));
        try (var files = Files.list(context.data.toPath().resolve("cache/runtime"))) { assertEquals(0, files.count()); }
    }
    @Test public void partialReadAndThreadInterruptionPreserveExistingArtifact() throws Exception {
        Path original = install(fixture("1", "")); byte[] before = Files.readAllBytes(original); var e = entry();
        ECloudClient broken = new ECloudClient(u -> new InputStream() { int count; public int read() throws IOException { if (++count > 20) throw new IOException("connection reset"); return 1; } }, 1000000);
        assertThrows(IOException.class, () -> broken.download(e, e.getVersion(), original.getParent()));
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, () -> new ECloudClient(u -> new ByteArrayInputStream(before),1000000).download(e,e.getVersion(),original.getParent())); }
        finally { Thread.interrupted(); }
        assertArrayEquals(before, Files.readAllBytes(original));
    }
    @Test public void sharedServiceParsesOfflinePlayerContext() {
        echo(); OfflinePlayer offline = proxy(OfflinePlayer.class, (p,m,a)->m.getName().equals("getName")?"Offline":empty(m.getReturnType()));
        assertEquals("value", context.getService(PlaceholderService.class).parse(offline, "%echo_value%"));
        assertEquals(Set.of("echo"),context.getService(PlaceholderService.class).identifiers());
    }
    @Test public void listenersRunWithExpansionOwnershipAndAreRemoved() throws Exception {
        AtomicInteger count = new AtomicInteger();
        class Events extends Echo implements Listener {
            @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) { assertNotNull(LocalExpansionManager.currentRuntime()); count.incrementAndGet(); }
        }
        Events expansion = new Events(); assertTrue(expansion.register());
        var player = proxy(org.bukkit.entity.Player.class, (p,m,a)->empty(m.getReturnType()));
        var event = new org.bukkit.event.player.PlayerQuitEvent(player, "bye");
        for (var listener : event.getHandlers().getRegisteredListeners()) listener.callEvent(event);
        assertEquals(1,count.get()); assertTrue(expansion.unregister());
        assertEquals(0,event.getHandlers().getRegisteredListeners().length);
    }
    @Test public void failedCleanupRetainsLoaderAndSuccessfulCallbacksAreNotRepeated() throws Exception {
        install(fixture("1", "implements me.clip.placeholderapi.expansion.Taskable, me.clip.placeholderapi.expansion.Cacheable", "public static int stops; private boolean fail=true; public void start(){} public void stop(){stops++;} public void clear(){if(fail){fail=false;throw new IllegalStateException(\"cleanup failure\");}}"));
        context.await(plugin.getLocalExpansionManager().reloadAsync(true)); var expansion=plugin.getLocalExpansionManager().getExpansion("fixture");
        assertThrows(IllegalStateException.class, expansion::unregister);
        assertFalse(expansion.isRegistered()); assertThrows(IOException.class,()->context.resources.getLast().close());
        assertTrue(plugin.getLocalExpansionManager().unload("fixture")); assertEquals(1,expansion.getClass().getField("stops").get(null));
    }
    CloudExpansionManager installCloud(byte[] artifact, String name, String version, AtomicInteger downloads) throws Exception {
        var entry = entry(); entry.setName(name); entry.setLatestVersion(version); entry.getVersions().getFirst().setVersion(version);
        String json = new com.google.gson.Gson().toJson(Map.of(name, entry));
        plugin.getConfig().set("cloud_enabled", true);
        var cloud = new CloudExpansionManager(plugin, new ECloudClient(uri -> {
            if (uri.equals(ECloudClient.CATALOGUE)) return new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            downloads.incrementAndGet(); return new ByteArrayInputStream(artifact);
        }, 1000000));
        context.await(cloud.refresh()); context.manage(cloud::kill); return cloud;
    }
    String awaitInstall(ExpansionInstaller installer, String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (installer.isBusy() && System.nanoTime() < deadline) { context.drain(); Thread.sleep(2); }
        context.drain(); assertFalse("Installation must reach a terminal state", installer.isBusy()); return installer.statuses().get(key);
    }
    @Test public void unifiedInstallationDeduplicatesAndRecognizesActualArtifactIdentifier() throws Exception {
        AtomicInteger downloads = new AtomicInteger(); var cloud = installCloud(Files.readAllBytes(fixture("1", "")), "Pretty Name", "1", downloads);
        var installer = context.manage(new ExpansionInstaller(plugin, cloud));
        assertEquals(200, installer.install("Pretty_Name", null).getStatusCode());
        assertEquals(200, installer.install("Pretty_Name", null).getStatusCode());
        assertEquals("done", awaitInstall(installer, "pretty_name")); assertEquals(1, downloads.get());
        assertEquals("x", PlaceholderAPI.setPlaceholders((OfflinePlayer)null, "%fixture_x%"));
        assertTrue(cloud.getCloudExpansions().get("pretty_name").hasExpansion());
        assertEquals(200, installer.install("Pretty_Name", null).getStatusCode()); assertEquals(1, downloads.get());
    }
    @Test public void unsupportedAutomaticDownloadReportsReasonAndLeavesNoArtifact() throws Exception {
        var cloud = installCloud("public class Source {}".getBytes(), "fixture", "1", new AtomicInteger());
        var installer = context.manage(new ExpansionInstaller(plugin, cloud)); installer.install("fixture", null);
        String status = awaitInstall(installer, "fixture"); assertTrue(status, status.contains("source compilation"));
        assertFalse(cloud.installedFile(cloud.findCloudExpansionByName("fixture").orElseThrow()).exists());
    }
    @Test public void rejectedInstallationRemovesArtifactAndDoesNotReloadUnrelatedExpansions() throws Exception {
        echo(); Path rejected = fixture("1", "public String getRequiredPlugin(){return \"MissingDependency\";}");
        var cloud = installCloud(Files.readAllBytes(rejected), "fixture", "1", new AtomicInteger());
        var installer = context.manage(new ExpansionInstaller(plugin, cloud)); installer.install("fixture", null);
        String status = awaitInstall(installer, "fixture"); assertTrue(status, status.contains("MissingDependency"));
        assertFalse(cloud.installedFile(cloud.findCloudExpansionByName("fixture").orElseThrow()).exists());
        assertTrue(PlaceholderAPI.isRegistered("echo")); assertFalse(PlaceholderAPI.isRegistered("fixture"));
    }
    @Test public void failedAutomaticUpdateRestoresOriginalJarAndRuntime() throws Exception {
        Path original = fixture("1", ""); install(original); context.await(plugin.getLocalExpansionManager().reloadAsync(true));
        var cloud = installCloud(Files.readAllBytes(fixture("2", "implements me.clip.placeholderapi.expansion.Taskable", "public void start(){throw new IllegalStateException(\"update start rejected\");} public void stop(){}")), "fixture", "2", new AtomicInteger());
        var installer = context.manage(new ExpansionInstaller(plugin, cloud)); installer.install("fixture", null);
        assertTrue(awaitInstall(installer, "fixture").startsWith("failed:"));
        assertEquals("1", plugin.getLocalExpansionManager().getExpansion("fixture").getVersion());
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(cloud.installedFile(cloud.findCloudExpansionByName("fixture").orElseThrow()).toPath()));
    }
    @Test public void installerRejectsVersionMismatchAndUnapprovedMetadata() throws Exception {
        var cloud = installCloud(Files.readAllBytes(fixture("1", "")), "fixture", "2", new AtomicInteger());
        var installer = context.manage(new ExpansionInstaller(plugin, cloud)); installer.install("fixture", null);
        assertTrue(awaitInstall(installer, "fixture").contains("does not match requested version"));
        cloud.findCloudExpansionByName("fixture").orElseThrow().getVersion().setVerified(false);
        assertEquals(400,installer.install("fixture",null).getStatusCode());
    }
    @Test public void targetedInstallationIgnoresUnrelatedBrokenFiles() throws Exception {
        Files.writeString(plugin.getLocalExpansionManager().getExpansionsFolder().toPath().resolve("unrelated.jar"), "invalid");
        var cloud = installCloud(Files.readAllBytes(fixture("1", "")), "fixture", "1", new AtomicInteger());
        var installer = context.manage(new ExpansionInstaller(plugin, cloud)); installer.install("fixture", null);
        assertEquals("done", awaitInstall(installer, "fixture"));
    }
    @Test public void officialPlayerExpansionLiveDownloadLoadAndParse() throws Exception {
        Assume.assumeTrue("Optional real official eCloud integration: -Dpapi.liveTest=true",Boolean.getBoolean("papi.liveTest"));
        plugin.getConfig().set("cloud_enabled",true);var cloud=plugin.getCloudExpansionManager();context.await(cloud.refresh());assertNotNull(cloud.findCloudExpansionByName("Player").orElse(null));var installer=context.manage(new ExpansionInstaller(plugin,cloud));assertEquals(200,installer.install("Player",null).getStatusCode());assertEquals("done",awaitInstall(installer,"player"));UUID uuid=UUID.randomUUID();OfflinePlayer offline=proxy(OfflinePlayer.class,(p,m,a)->switch(m.getName()){case "getName"->"LivePlayer";case "getUniqueId"->uuid;default->empty(m.getReturnType());});assertEquals("LivePlayer "+uuid,PlaceholderAPI.setPlaceholders(offline,"%player_name% %player_uuid%"));
        World world = proxy(World.class, (p,m,a)->m.getName().equals("getName")?"testworld":empty(m.getReturnType()));
        var online = proxy(org.bukkit.entity.Player.class, (p,m,a)->switch(m.getName()) {
            case "getName" -> "LivePlayer"; case "getUniqueId" -> uuid; case "isOnline" -> true; case "getPlayer" -> p;
            case "getWorld" -> world; case "getLocation" -> new Location(world, 12.8, 64.1, -3.2);
            default -> empty(m.getReturnType());
        });
        assertEquals("testworld 12 64 -4", PlaceholderAPI.setPlaceholders(online, "%player_world% %player_x% %player_y% %player_z%"));
        assertEquals("0",PlaceholderAPI.setPlaceholders(online,"%player_ping%"));
        permissions.add("placeholderapi.admin");
        context.commands.get("papi").onCommand(sender,null,"papi",new String[]{"ecloud","download","Server"});
        assertEquals("done",awaitInstall(plugin.getExpansionInstaller(),"server"));
        assertEquals("0",context.renderText(null,"%server_online%"));
        assertTrue(messages.stream().anyMatch(s->s.contains("Server") && s.contains("status ACTIVE")));
    }
    @Test public void officialPlayerStatsArtifactReportsItsMissingNativeDependency() throws Exception {
        Assume.assumeTrue("Optional real eCloud artifact: -Dpapi.liveTest=true",Boolean.getBoolean("papi.liveTest"));
        plugin.getConfig().set("cloud_enabled",true);var cloud=plugin.getCloudExpansionManager();context.await(cloud.refresh());
        assertNotNull(cloud.findCloudExpansionByName("PlayerStats").orElse(null));
        var installer=context.manage(new ExpansionInstaller(plugin,cloud));assertEquals(200,installer.install("PlayerStats",null).getStatusCode());
        String failure=awaitInstall(installer,"playerstats");
        assertTrue(failure,failure.contains("PlayerStats"));assertTrue(failure,failure.contains("External Paper plugin"));assertTrue(failure,failure.contains("Not found"));
        assertFalse(PlaceholderAPI.isRegistered("playerstats"));
        assertFalse(cloud.installedFile(cloud.findCloudExpansionByName("PlayerStats").orElseThrow()).exists());
    }
}


