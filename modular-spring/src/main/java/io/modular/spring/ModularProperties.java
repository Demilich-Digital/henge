package io.modular.spring;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Reads {@code modular.*} configuration directly from Spring's {@link Environment} on demand —
 * no pre-binding, no annotation-driven binding infrastructure, so this works identically whether
 * the surrounding application is Spring Boot or plain Spring Framework.
 */
public class ModularProperties {

    private final Environment environment;

    public ModularProperties(Environment environment) {
        this.environment = environment;
    }

    /** {@code modular.server.enabled}, default {@code true}. */
    public boolean isServerEnabled() {
        return environment.getProperty("modular.server.enabled", Boolean.class, true);
    }

    /** {@code modular.server.path-prefix}, default {@code /_modular}. */
    public String getServerPathPrefix() {
        return environment.getProperty("modular.server.path-prefix", "/_modular");
    }

    /** {@code modular.remote-url-template}; {@code null} if unset. */
    public String getRemoteUrlTemplate() {
        return environment.getProperty("modular.remote-url-template");
    }

    /**
     * {@code modular.serve}, comma-separated. Split manually rather than relying on the
     * conversion service's String-to-List behavior — simpler and predictable regardless of which
     * {@code Environment} implementation this ends up wrapping.
     */
    public List<String> getServe() {
        String raw = environment.getProperty("modular.serve");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : raw.split(",")) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        return entries;
    }

    public ServiceConfig service(String name) {
        return new ServiceConfig(environment, name);
    }

    public static class ServiceConfig {

        private final Environment environment;
        private final String name;

        ServiceConfig(Environment environment, String name) {
            this.environment = environment;
            this.name = name;
        }

        /** Per-version mode, falling back to the service-level value. {@code null} if neither is set. */
        public String resolveMode(String version) {
            return resolve("mode", version);
        }

        /** Per-version url, falling back to the service-level value. {@code null} if neither is set. */
        public String resolveUrl(String version) {
            return resolve("url", version);
        }

        private String resolve(String key, String version) {
            String versioned = environment.getProperty("modular.services." + name + ".versions." + version + "." + key);
            return versioned != null ? versioned : environment.getProperty("modular.services." + name + "." + key);
        }

        /**
         * Version strings with any explicit {@code modular.services.<name>.versions.<version>.*}
         * config, found by scanning enumerable property sources directly — the only way to
         * discover "which keys exist under this prefix" without Boot's relaxed-binding
         * {@code Binder}. Requires a {@link ConfigurableEnvironment} (always the actual runtime
         * type in a real {@code ApplicationContext}); returns empty otherwise.
         */
        public Set<String> explicitVersions() {
            Set<String> versions = new LinkedHashSet<>();
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                return versions;
            }
            String prefix = "modular.services." + name + ".versions.";
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String propertyName : enumerable.getPropertyNames()) {
                        if (propertyName.startsWith(prefix)) {
                            String rest = propertyName.substring(prefix.length());
                            int dot = rest.indexOf('.');
                            String version = dot < 0 ? rest : rest.substring(0, dot);
                            if (!version.isEmpty()) {
                                versions.add(version);
                            }
                        }
                    }
                }
            }
            return versions;
        }
    }
}
