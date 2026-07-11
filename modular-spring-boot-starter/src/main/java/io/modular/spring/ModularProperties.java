package io.modular.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modular")
public class ModularProperties {

    /**
     * Per-service overrides, keyed by the service's {@code name} (see {@code io.modular.core.ModularService}).
     * Prefer kebab-case service names ({@code audit-service}) — Spring Boot's relaxed property
     * binding is only unambiguous for map keys in that form across YAML/properties/env-vars/CLI.
     */
    private final Map<String, ServiceConfig> services = new LinkedHashMap<>();

    private final Server server = new Server();

    public Map<String, ServiceConfig> getServices() {
        return services;
    }

    public Server getServer() {
        return server;
    }

    public static class Server {

        /** Whether this process exposes {@code /_modular/**} dispatch endpoints for its embedded services at all. */
        private boolean enabled = true;

        /** Base path under which every service's dispatch endpoints are mounted. */
        private String pathPrefix = "/_modular";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }
    }

    public static class ServiceConfig {

        /** {@code embedded} (default) or {@code internal-rest}. Applies to any version without its own override. */
        private String mode = "embedded";

        /** Base URL of the process hosting this service; required when {@code mode=internal-rest}. */
        private String url;

        /**
         * Per-version overrides, keyed by version string. Only needed when a service runs more
         * than one version at once, or when a specific version's mode/url differs from the
         * service-level default above — a single-version service never needs this.
         */
        private final Map<String, VersionConfig> versions = new LinkedHashMap<>();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Map<String, VersionConfig> getVersions() {
            return versions;
        }

        /** Per-version mode, falling back to the service-level {@link #getMode()} when unset. */
        public String resolveMode(String version) {
            VersionConfig versionConfig = versions.get(version);
            return (versionConfig != null && versionConfig.getMode() != null) ? versionConfig.getMode() : mode;
        }

        /** Per-version url, falling back to the service-level {@link #getUrl()} when unset. */
        public String resolveUrl(String version) {
            VersionConfig versionConfig = versions.get(version);
            return (versionConfig != null && versionConfig.getUrl() != null) ? versionConfig.getUrl() : url;
        }
    }

    public static class VersionConfig {

        /** Overrides the service-level mode for this version only; unset inherits it. */
        private String mode;

        /** Overrides the service-level url for this version only; unset inherits it. */
        private String url;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
