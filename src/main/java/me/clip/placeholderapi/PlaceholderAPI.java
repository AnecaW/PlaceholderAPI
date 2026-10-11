package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import me.clip.placeholderapi.replacer.*;
import me.clip.placeholderapi.expansion.*;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;

/** Single-pass replacement. Values are supplied exclusively by registered expansions. */
public final class PlaceholderAPI {
    private PlaceholderAPI() {}
    private static final Replacer PERCENT = new CharsReplacer(Replacer.Closure.PERCENT);
    private static final Replacer BRACKET = new CharsReplacer(Replacer.Closure.BRACKET);
    private static final Pattern RELATIONAL = Pattern.compile("%rel_([^_%]+)_([^%]*)%");
    private static final Pattern TOKENS = Pattern.compile("%[^%\\s]+_[^%]*%");
    private static final Pattern BRACKETS = Pattern.compile("\\{[^{}\\s]+_[^{}]*}");
    private static final ThreadLocal<List<String>> REQUEST_DIAGNOSTICS = new ThreadLocal<>();
    public static void reportRequestIssue(String message) { var issues = REQUEST_DIAGNOSTICS.get(); if (issues != null) issues.add(message); }
    public static org.wannes.plugModCore.api.PlaceholderResult parseDetailed(OfflinePlayer player, String text) {
        List<String> issues = new ArrayList<>(); List<String> previous = REQUEST_DIAGNOSTICS.get();
        REQUEST_DIAGNOSTICS.set(issues);
        try {
            LocalExpansionManager local = manager();
            if (local == null) return new org.wannes.plugModCore.api.PlaceholderResult(text, List.of("Placeholder service runtime is unavailable"));
            String rendered = setPlaceholders(player, text);
            if (text != null) {
                Matcher tokens = Pattern.compile("%([A-Za-z0-9-]+)(?:_([^%]*))?%").matcher(text);
                while (tokens.find()) {
                    String id = tokens.group(1).toLowerCase(Locale.ROOT);
                    if (tokens.group(2) == null) issues.add(tokens.group() + ": invalid PlaceholderAPI syntax; use %identifier_parameters% (online count: %server_online% with Server registered)");
                    else if (local.getExpansion(id) == null) {
                        String failure = local.activationFailure(id);
                        issues.add(tokens.group() + ": " + (failure == null ? "identifier '" + id + "' is not registered; install its expansion or reload a downloaded artifact and inspect activation errors" : failure));
                    }
                }
            }
            return new org.wannes.plugModCore.api.PlaceholderResult(rendered, issues.stream().distinct().toList());
        } finally { if (previous == null) REQUEST_DIAGNOSTICS.remove(); else REQUEST_DIAGNOSTICS.set(previous); }
    }
    public static String setPlaceholders(OfflinePlayer player, String text) { return apply(PERCENT, player, text); }
    public static String setPlaceholders(Player player, String text) { return setPlaceholders((OfflinePlayer) player, text); }
    public static List<String> setPlaceholders(OfflinePlayer player, List<String> text) { return text.stream().map(s -> setPlaceholders(player, s)).toList(); }
    public static String setBracketPlaceholders(OfflinePlayer player, String text) { return apply(BRACKET, player, text); }
    public static List<String> setBracketPlaceholders(OfflinePlayer player, List<String> text) { return text.stream().map(s -> setBracketPlaceholders(player, s)).toList(); }
    private static String apply(Replacer replacer, OfflinePlayer player, String text) {
        if (text == null) return null;
        LocalExpansionManager manager = manager();
        return manager == null ? text : replacer.apply(text, player, manager::getExpansion);
    }
    private static LocalExpansionManager manager() {
        try { return PlaceholderAPIPlugin.getInstance().getLocalExpansionManager(); }
        catch (IllegalStateException e) { return null; }
    }
    public static String setRelationalPlaceholders(Player one, Player two, String text) {
        LocalExpansionManager manager = manager();
        if (text == null || manager == null || !text.contains("%rel_")) return text;
        Matcher matcher = RELATIONAL.matcher(text); StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            PlaceholderExpansion expansion = manager.getExpansion(matcher.group(1)); String params = matcher.group(2);
            String value = expansion instanceof Relational relational ? LocalExpansionManager.request(expansion, () -> relational.onPlaceholderRequest(one, two, params)) : null;
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(output); return output.toString();
    }
    public static List<String> setRelationalPlaceholders(Player one, Player two, List<String> text) { return text.stream().map(s -> setRelationalPlaceholders(one, two, s)).toList(); }
    public static Set<String> getRegisteredIdentifiers() {
        var manager = manager(); return manager == null ? Set.of() : manager.getExpansions().stream().map(PlaceholderExpansion::getIdentifier).collect(Collectors.toUnmodifiableSet());
    }
    public static boolean isRegistered(String identifier) { var manager = manager(); return manager != null && manager.getExpansion(identifier) != null; }
    public static boolean containsPlaceholders(String text) { return text != null && TOKENS.matcher(text).find(); }
    public static boolean containsBracketPlaceholders(String text) { return text != null && BRACKETS.matcher(text).find(); }
}
