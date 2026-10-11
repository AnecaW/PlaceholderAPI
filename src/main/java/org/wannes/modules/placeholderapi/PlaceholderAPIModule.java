package org.wannes.modules.placeholderapi;

import org.wannes.plugModCore.api.*;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.manager.ExpansionInstaller;
import com.google.gson.Gson;
import java.util.*;

public final class PlaceholderAPIModule implements org.wannes.plugModCore.api.Module, ModuleWebApi {
    private volatile ExpansionInstaller installer;
    public void onEnable(ModuleContext context) {
        PlaceholderAPIPlugin.init(context);
        var plugin = PlaceholderAPIPlugin.getInstance();
        installer = plugin.getExpansionInstaller();
    }
    public void onDisable() {
        installer = null;
        PlaceholderAPIPlugin.shutdown();
    }
    public ApiResponse handleApiRequest(String method, String path, Map<String, String> params) {
        var current = installer;
        if (current == null) return ApiResponse.error("PlaceholderAPI module is stopped");
        try {
            var plugin = PlaceholderAPIPlugin.getInstance(); var cloud = plugin.getCloudExpansionManager();
            if ("GET".equalsIgnoreCase(method) && "/ecloud/list".equals(path)) return ApiResponse.okJson(new Gson().toJson(cloud.getCloudExpansions()));
            if ("GET".equalsIgnoreCase(method) && "/ecloud/status".equals(path)) return ApiResponse.okJson(new Gson().toJson(current.statuses()));
            if ("GET".equalsIgnoreCase(method) && "/ecloud/meta".equals(path)) return ApiResponse.okJson(new Gson().toJson(Map.of("refreshing", cloud.isRefreshing(), "enabled", plugin.getPlaceholderAPIConfig().isCloudEnabled(), "error", Objects.toString(cloud.getLastError(), ""))));
            if ("POST".equalsIgnoreCase(method) && "/ecloud/refresh".equals(path)) {
                if (!plugin.getPlaceholderAPIConfig().isCloudEnabled()) return ApiResponse.badRequest("eCloud is disabled in the module configuration");
                cloud.refresh(); return ApiResponse.okJson("{\"status\":\"started\"}");
            }
            // Keep the existing endpoint compatible; both routes perform full installation.
            if ("POST".equalsIgnoreCase(method) && ("/ecloud/install".equals(path) || "/ecloud/download".equals(path))) return current.install(params.getOrDefault("name", params.get("id")), params.get("version"));
            return ApiResponse.notFound("Unknown endpoint");
        } catch (Exception e) { return ApiResponse.badRequest(e.getMessage()); }
    }
}
