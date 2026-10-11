package me.clip.placeholderapi.expansion.cloud;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import me.clip.placeholderapi.util.TimeUtil;


public class CloudExpansion {

    private String name,
            author,
            latest_version,
            description,
            source_url,
            dependency_url;

    private boolean hasExpansion,
            shouldUpdate;

    private long last_update,
            ratings_count;

    private double average_rating;

    private List<String> placeholders;

    private List<Version> versions;

    public CloudExpansion() {
    }

    public String getTimeSinceLastUpdate() {
        int time = (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - getLastUpdate());
        return TimeUtil.getTime(time);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Version getVersion() {
        return getLatestVersion() == null ? null : getVersion(getLatestVersion());
    }

    public Version getVersion(String version) {
        return versions == null ? null : versions.stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst()
                .orElse(null);
    }

    public java.util.List<String> getAvailableVersions() {
        return versions.stream().map(Version::getVersion).collect(Collectors.toList());
    }

    public String getLatestVersion() {
        return latest_version;
    }

    public void setLatestVersion(String latest_version) {
        this.latest_version = latest_version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceUrl() {
        return source_url;
    }

    public void setSourceUrl(String source_url) {
        this.source_url = source_url;
    }

    public String getDependencyUrl() {
        return dependency_url;
    }

    public void setDependencyUrl(String dependency_url) {
        this.dependency_url = dependency_url;
    }

    public boolean hasExpansion() {
        return hasExpansion;
    }

    public void setHasExpansion(boolean hasExpansion) {
        this.hasExpansion = hasExpansion;
    }

    public boolean shouldUpdate() {
        return shouldUpdate;
    }

    public void setShouldUpdate(boolean shouldUpdate) {
        this.shouldUpdate = shouldUpdate;
    }

    public long getLastUpdate() {
        return last_update;
    }

    public void setLastUpdate(long last_update) {
        this.last_update = last_update;
    }

    public long getRatingsCount() {
        return ratings_count;
    }

    public double getAverage_rating() {
        return average_rating;
    }

    public java.util.List<String> getPlaceholders() {
        return placeholders;
    }

    public void setPlaceholders(java.util.List<String> placeholders) {
        this.placeholders = placeholders;
    }

    public java.util.List<Version> getVersions() {
        return versions;
    }

    public void setVersions(java.util.List<Version> versions) {
        this.versions = versions;
    }

    public static class Version {

        private String url, version, release_notes;
        private boolean verified;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getReleaseNotes() {
            return release_notes;
        }

        public void setReleaseNotes(String release_notes) {
            this.release_notes = release_notes;
        }

        public boolean isVerified() {
            return verified;
        }

        public void setVerified(boolean verified) {
            this.verified = verified;
        }
    }
}
