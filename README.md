# PlaceholderAPI module

The existing port now provides expansion-driven percent, bracket and relational parsing; an independent expansion registry; asynchronous official eCloud downloads; runtime JAR discovery and activation; configuration defaults; and lifecycle cleanup. The old command stub, hardcoded player-independent placeholders, unsafe direct web downloads and duplicate configuration keys were removed.

## Install and operate

Build the whole reactor with `mvn verify` using JDK 21 or newer. Deploy the rebuilt PlugModCore and `Modules/PlaceholderAPI/target/PlaceholderAPI.jar` together: the shared API now contains `PlaceholderService`. Put the module JAR in PlugModCore's `modules` directory and enable it through `/module` or the dashboard.

Existing `config.yml` files are preserved. Older installations may have `cloud_enabled: false`; change this to `true` and run `/papi reload`. New installs enable eCloud by default. Permission `placeholderapi.admin` defaults to operators. Delegated users need the relevant `placeholderapi.<action>` permission; eCloud actions also require `placeholderapi.ecloud` and `placeholderapi.ecloud.<action>`.

```text
/papi ecloud refresh
/papi ecloud search Player
/papi ecloud info Player
/papi ecloud placeholders Player
/papi ecloud download Player
/papi parse me %player_name% %player_uuid% %player_world% %player_x% %player_y% %player_z%
```

Wait for completion messages between dependent operations. Both the web Install button and `/papi ecloud download`/`update` now use the same installer and report ACTIVE only after validation, loading and registration succeed. `/papi reload` remains available for manually copied artifacts and reload operations. `/papi parse <online-or-cached-player|me|--null> <text>` uses the shared service and reports invalid syntax, unregistered identifiers, recorded dependency/activation failures, unknown parameters or unsupported contexts, and request exceptions. Unresolved tokens remain unchanged.

Other commands: `/papi info`, `/papi list`, `/papi unload <identifier>`, `/papi ecloud list [page]`, `/papi ecloud download <name> [version]`, `/papi ecloud update <name>`. Update installs and activates the latest version. Tab completion and permission checks apply. Unload retains the installed JAR, so another reload or restart loads it again. Remove its JAR while unloaded to uninstall permanently. Persistent expansions stay registered during `/papi reload`, but are stopped when the entire module disables.

The web UI offers one Download & Install button, using the official metadata URL internally. It validates the artifact and activates that expansion without reloading unrelated expansions. Progress shows Downloading…, Installing…, Installed successfully or a precise failure reason. Completed installs show Installed; Update appears only when the catalogue reports an available update. Duplicate requests and overlapping web installations are prevented, including across UI searches/refreshes. Failed activation restores the previous artifact or removes a failed new installation. Unsupported source/build/archive formats report an error; arbitrary source builds and bundle extraction are not supported. The legacy /ecloud/download web endpoint still performs full installation; /ecloud/install is its clearer alias. Web API authentication/authorization is supplied by PlugModCore.

## Storage and updates

Storage is the module context's existing data directory, normally `plugins/PlugModCore/module-data/<internal module ID>/`:

- `config.yml`: module settings and `expansions.<identifier>` configuration defaults.
- `expansions/Expansion-<name>.jar`: persistent installed implementations.
- `expansions/*.previous`: previous artifact retained while an update awaits activation.
- `cache/ecloud.json`: last valid catalogue; used when available while a refresh runs.
- `cache/runtime/`: private active JAR snapshots, closed and removed on successful unload.

The downloader writes a bounded temporary file, validates its archive content, then publishes it with an atomic move where supported. Incomplete, interrupted, oversized and invalid downloads leave the installed artifact intact. Previous disk backups survive until activation. Reload rejects a bad discovery pass without unloading existing expansions. Dependency/API rejection retains the previous runtime and restores its disk artifact when available. A failed new start attempts to recreate a fresh instance from the previous snapshot. Shutdown failures retain resources for retry and do not report success. Arbitrary in-memory/database changes and external side effects cannot be rolled back.

## Supported expansions and limits

Reference behavior: [upstream PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI), [installation documentation](https://wiki.placeholderapi.com/users/using-placeholders/). The supported external installation format is the executable expansion JAR used by official eCloud. The catalogue endpoint is `https://ecloud.placeholderapi.com/api/v3/?platform=bukkit`. Each JAR must expose exactly one concrete `me.clip.placeholderapi.expansion.PlaceholderExpansion` with a public no-argument constructor. Shaded expansion libraries work when compatible with the server and this API. Required Bukkit plugins and the legacy `VersionSpecific` contract are checked; unresolved third-party classes and incompatible APIs produce errors. Plugin dependency classloaders are consulted for class resolution. Source archives, arbitrary Maven builds, automatic dependency installation and ordinary plugin JAR installation are not supported or silently compiled.

Standard `onRequest(OfflinePlayer, parameters)` / `onPlaceholderRequest(Player, parameters)`, `Relational`, `Configurable`, `Taskable.start/stop`, `Cacheable.clear`, `Cleanable.cleanup` and expansion registration events are supported. Identifier lookup is case-insensitive; IDs must contain letters, digits or hyphens. Returned values are inserted once, so recursive/nested expansion-specific behavior remains the expansion's responsibility. Percent and bracket APIs are distinct. No values are hardcoded in the parser.

Tasks started through the module scheduler from managed expansion callbacks and annotated listener invocations are owned by that expansion. Registered listener handlers are removed independently of the host plugin. A running managed callback prevents loader release until it finishes; retry unloading afterwards. Resources created directly through Bukkit, arbitrary threads, third-party registries or expansion constructors require the expansion's own cleanup contract. Closing a loader does not guarantee immediate class garbage collection.

HTTPS destinations and redirects are restricted to explicit official hosts: ecloud.placeholderapi.com, dl.placeholderapi.com, supa.placeholderapi.com (observed official artifact-storage redirect), and api.extendedclip.com. Metadata is limited to 8 MiB and artifact downloads to 32 MiB; archive entries and decompressed content are bounded. Connect/read timeouts are 10/15 seconds. Official `verified` metadata is required for automated downloads; it means eCloud approval, not cryptographic verification. JAR signature verification is enabled when a signed JAR supplies signatures, but no publisher certificate pinning or invented checksums are claimed. Expansion code executes with normal server-process privileges; it is not sandboxed.

This module is not a JavaPlugin named PlaceholderAPI. Plugins that rely on Bukkit discovering that plugin, JavaPlugin methods or unsupported upstream API classes may require an adapter or the original plugin. Full upstream binary compatibility, every expansion, NMS compatibility and Folia are not claimed.

## Shared consumer API

Consumers compile against PlugModCore-API as `provided`; module consumers declare the `placeholderapi` provider dependency. Resolve the service on each use/provider activation:

```java
PlaceholderService placeholders = context.getService(PlaceholderService.class);
String rendered = placeholders == null ? text : placeholders.parse(player, text);
```

`PlaceholderService.parseBrackets` and `identifiers` are also available. Ordinary plugins can use `Bukkit.getServicesManager().load(PlaceholderService.class)` when compiled against the shared API and declaring their PlugModCore plugin dependency. Consumers need no internal implementation access. Expansion code retains its conventional `PlaceholderAPI` and `PlaceholderExpansion` API within the provider's loader.

`context.renderText(player, configuredText)` is the common convenience entry point for existing and future modules; declare the `placeholderapi` dependency to use its service. `parseDetailed` returns rendered text and request-specific diagnostics using the same runtime. The only existing configurable player-facing text module is ScoreBoard; TemplateModule contains no configurable messages, and Announcements/Tablist/chat modules are not present in this repository.

ScoreBoard's shipped YAML and editor defaults now use `%server_online%`, supplied by the [official Server expansion](https://github.com/PlaceholderAPI/Server-Expansion). Install Server, wait for ACTIVE, and test `/papi parse --null %server_online%`. Existing user configurations are preserved: replace old `%online%` entries in the editor with `%server_online%`. The editor preview shows templates; actual player rendering uses the shared service. Scoreboard diagnostics log each distinct issue once per configuration reload.

Modules can also expose conventional placeholders without using another module's implementation classes:

```java
context.requireService(PlaceholderService.class, "placeholderapi")
    .register(context, new PlaceholderProvider() {
        public String identifier() { return "mystats"; }
        public String resolve(OfflinePlayer player, String parameters) {
            return parameters.equals("name") && player != null ? player.getName() : null;
        }
    });
```

This registers `%mystats_name%`; the consumer context owns automatic unregistration. The returned closeable can unregister it earlier. ScoreBoard declares the PlaceholderAPI dependency and required service, renders with each player's context, refreshes every second, and reacquires the service rather than caching its implementation. Core provider reload recreates dependent activations in dependency order.

## PlayerStats dependency diagnostics

The [PlayerStatsExpansion source](https://github.com/Artemis-the-gr8/PlayerStatsExpansion) requires the actual native plugin named `PlayerStats`. Its compatibility check expects the plugin's 2.x API; its overridden `register()` initializes that API and the placeholder provider. The runtime now invokes overridden registration hooks, rather than inserting expansions directly into its registry. A missing or disabled native plugin, a rejected version check, a missing API class, and a failed registration hook each produce a separate diagnostic. An internal module named PlayerStats cannot satisfy a native plugin requirement; it can expose placeholders through the shared service instead. Native dependency disable unregisters dependent expansions, and enable retries loading installed expansions.

Use placeholder identifiers/parameters documented by the expansion you installed; the example `%playerstats_kills%` is not assumed to be supported by every PlayerStats expansion. This repository contains no running Paper server or installed PlayerStats plugin, so actual native API/gameplay compatibility still requires a live server with matching versions.

## Verification

The module component suite uses real compiled expansion JARs and a small Bukkit test double. It covers initialization, registry identity/duplicates, parsing, null/unknown/malformed input, bracket/relational behavior, permissions/completion, dependencies/version rejection, config merging, disk persistence/restart, repeated reloads, rollback, asynchronous downloads, invalid metadata/artifacts, limits, interruption/partial reads, task cancellation, listener ownership/removal, cleanup failure retries and module shutdown.

Run the six UI state regression tests with `node --test Modules/PlaceholderAPI/src/test/js/ecloud.test.cjs`. They cover the single action, progress, duplicate clicks, rerenders, installed/update states, errors and connection failures using a simulated DOM/fetch transport. Optional network tests exercise the unified installer with the real official catalogue: Player loads and parses name, UUID, world and X/Y/Z using simulated player/world objects; PlayerStats reports the actual missing native plugin and removes the failed new artifact. Run them with `mvn verify -Dpapi.liveTest=true`; ordinary `mvn verify` skips those two network-dependent tests. ScoreBoard tests also render through the actual PlaceholderAPI service and replace/unregister expansions between refreshes. Tests do not validate full gameplay, scheduler timing, actual Paper event dispatch, NMS, Vault or a running PlayerStats native API, or browser interaction. Run the command sequence above on Paper 1.21.11 before deployment, then test expansion/module reload and shutdown, web installation, permission delegation and any required third-party plugins.

