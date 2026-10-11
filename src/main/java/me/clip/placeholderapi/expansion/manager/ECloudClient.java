package me.clip.placeholderapi.expansion.manager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Official eCloud protocol and bounded, transactional artifact installation. */
public final class ECloudClient {
    public static final URI CATALOGUE = URI.create("https://ecloud.placeholderapi.com/api/v3/?platform=bukkit");
    // dl.placeholderapi.com currently redirects artifacts to this official storage host.
    private static final Set<String> HOSTS = Set.of("ecloud.placeholderapi.com", "dl.placeholderapi.com", "supa.placeholderapi.com", "api.extendedclip.com");
    public interface Transport { InputStream open(URI uri) throws IOException; }
    private final Transport transport;
    private final int artifactLimit;
    public ECloudClient() { this(ECloudClient::openOfficial, 32 * 1024 * 1024); }
    public ECloudClient(Transport transport, int artifactLimit) { this.transport = transport; this.artifactLimit = artifactLimit; }
    public static URI trusted(String url) throws IOException {
        final URI uri;
        try { uri = URI.create(url); } catch (RuntimeException e) { throw new IOException("Invalid official download URL", e); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443) || !HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)))
            throw new IOException("Download URL is outside the official HTTPS hosts");
        return uri;
    }
    private static InputStream openOfficial(URI initial) throws IOException {
        URI uri = trusted(initial.toString());
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "PlugModCore-PlaceholderAPI/2.11-compatible");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException e) { connection.disconnect(); throw e; }
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null) throw new IOException("Redirect without a location");
                uri = trusted(uri.resolve(location).toString()); continue;
            }
            if (status != 200) { connection.disconnect(); throw new IOException("Official eCloud returned HTTP " + status); }
            InputStream body;
            try { body = connection.getInputStream(); }
            catch (IOException e) { connection.disconnect(); throw e; }
            return new FilterInputStream(body) {
                public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
            };
        }
        throw new IOException("Too many download redirects");
    }
    private static void copyBounded(InputStream input, OutputStream output, int limit) throws IOException {
        byte[] buffer = new byte[8192]; long total = 0;
        for (int read; (read = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download interrupted");
            total += read; if (total > limit) throw new IOException("Download exceeds size limit " + limit);
            output.write(buffer, 0, read);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download interrupted");
    }
    public Map<String, CloudExpansion> fetch() throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (InputStream input = transport.open(CATALOGUE)) { copyBounded(input, data, 8 * 1024 * 1024); }
        return parseCatalogue(data.toString(StandardCharsets.UTF_8));
    }
    public static Map<String, CloudExpansion> parseCatalogue(String json) throws IOException {
        try {
            Map<String, CloudExpansion> parsed = new Gson().fromJson(json, new TypeToken<Map<String, CloudExpansion>>() {}.getType());
            if (parsed == null || parsed.isEmpty()) throw new IOException("Official catalogue is empty");
            Map<String, CloudExpansion> result = new TreeMap<>();
            for (var entry : parsed.entrySet()) {
                String name = entry.getKey(); CloudExpansion expansion = entry.getValue();
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9_ -]{0,127}") || expansion == null || expansion.getLatestVersion() == null || expansion.getVersions() == null)
                    throw new IOException("Invalid catalogue metadata for " + name);
                expansion.setName(name);
                for (CloudExpansion.Version version : expansion.getVersions()) {
                    if (version == null || version.getVersion() == null || version.getVersion().isBlank()) throw new IOException("Invalid version metadata for " + name);
                    trusted(version.getUrl());
                }
                String key = name.toLowerCase(Locale.ROOT).replace(' ', '_');
                if (result.put(key, expansion) != null) throw new IOException("Duplicate catalogue name " + name);
            }
            return Collections.unmodifiableMap(result);
        } catch (com.google.gson.JsonParseException | IllegalArgumentException | NullPointerException e) { throw new IOException("Invalid official catalogue response", e); }
    }
    public Path download(CloudExpansion expansion, CloudExpansion.Version version, Path folder) throws IOException {
        return download(expansion, version, folder, () -> false);
    }
    public Path download(CloudExpansion expansion, CloudExpansion.Version version, Path folder, java.util.function.BooleanSupplier cancelled) throws IOException {
        String name = expansion.getName();
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_ -]{0,127}")) throw new IOException("Invalid expansion name");
        if (!version.isVerified()) throw new IOException("eCloud has not approved this version; automatic download is refused");
        URI uri = trusted(version.getUrl());
        Files.createDirectories(folder);
        Path destination = folder.resolve("Expansion-" + name.toLowerCase(Locale.ROOT).replace(' ', '_') + ".jar");
        Path temporary = Files.createTempFile(folder, ".download-", ".tmp");
        try {
            try (InputStream input = new FilterInputStream(transport.open(uri)) {
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
                    return super.read(buffer, offset, length);
                }
            }; OutputStream output = Files.newOutputStream(temporary)) { copyBounded(input, output, artifactLimit); }
            validateArtifact(temporary);
            // Interruption/disable before publication must preserve the old installed JAR.
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new InterruptedIOException("Download interrupted or cancelled");
            Path previous = destination.resolveSibling(destination.getFileName() + ".previous");
            if (Files.exists(destination) && !Files.exists(previous)) Files.copy(destination, previous);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }
    public static void validateArtifact(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] signature = input.readNBytes(4);
            if (signature.length < 4 || signature[0] != 'P' || signature[1] != 'K') throw new IOException("The download is not an executable expansion JAR. Automatic source compilation and bundle extraction are not supported.");
        }
        boolean expansion = false; long expanded = 0; int entries = 0;
        try (JarFile jar = new JarFile(file.toFile(), true)) {
            Enumeration<JarEntry> contents = jar.entries();
            while (contents.hasMoreElements()) {
                JarEntry entry = contents.nextElement(); if (++entries > 10000) throw new IOException("Too many JAR entries");
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = jar.getInputStream(entry)) { copyBounded(input, bytes, 8 * 1024 * 1024); }
                expanded += bytes.size(); if (expanded > 64 * 1024 * 1024) throw new IOException("Expanded JAR exceeds size limit");
                if (entry.getName().endsWith(".class") && new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1).contains("me/clip/placeholderapi/expansion/PlaceholderExpansion")) expansion = true;
            }
        } catch (SecurityException e) { throw new IOException("JAR signature validation failed", e); }
        if (!expansion) throw new IOException("Not a compatible PlaceholderExpansion JAR; source files and ordinary plugin JARs are unsupported");
    }
}
