package digital.demilich.henge.spring;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /** {@code modular.transport.connect-timeout}, milliseconds, default {@code 2000}. */
    public long getConnectTimeoutMillis() {
        return environment.getProperty("modular.transport.connect-timeout", Long.class, 2000L);
    }

    /** {@code modular.transport.read-timeout}, milliseconds, default {@code 10000}. */
    public long getReadTimeoutMillis() {
        return environment.getProperty("modular.transport.read-timeout", Long.class, 10000L);
    }

    /**
     * {@code modular.transport.secret}; {@code null} if unset. When set, {@link InternalRestTransport}
     * sends it on every dispatch call and {@link ModularDispatcherController} requires it (constant-time
     * compare, 403 otherwise) -- see the README's security section.
     */
    public String getTransportSecret() {
        return environment.getProperty("modular.transport.secret");
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
        public String resolveMode(int version) {
            return resolve("mode", version);
        }

        /** Per-version url, falling back to the service-level value. {@code null} if neither is set. */
        public String resolveUrl(int version) {
            return resolve("url", version);
        }

        private String resolve(String key, int version) {
            String versioned = environment.getProperty("modular.services." + name + ".versions." + version + "." + key);
            return versioned != null ? versioned : environment.getProperty("modular.services." + name + "." + key);
        }

        /**
         * Versions with any explicit {@code modular.services.<name>.versions.<version>.*}
         * config, found by scanning enumerable property sources directly — the only way to
         * discover "which keys exist under this prefix" without Boot's relaxed-binding
         * {@code Binder}. Requires a {@link ConfigurableEnvironment} (always the actual runtime
         * type in a real {@code ApplicationContext}); returns empty otherwise.
         *
         * <p>Checks each enumerated property name against both the literal dotted-kebab prefix
         * (what config files/command-line args use) and its env-var-style equivalent
         * (uppercased, {@code .}/{@code -} both mapped to {@code _}) — an OS environment variable
         * property source enumerates its keys in raw {@code MODULAR_SERVICES_...} form, never
         * translated to the dotted form (that translation only happens lazily, per-key, inside
         * {@link Environment#getProperty(String)} — see {@code SystemEnvironmentPropertySource}),
         * so a literal-only scan would silently miss a version declared purely via an env var.
         */
        public Set<Integer> explicitVersions() {
            Set<Integer> versions = new LinkedHashSet<>();
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                return versions;
            }
            String dottedPrefix = "modular.services." + name + ".versions.";
            String envStylePrefix = toEnvVarStyle(dottedPrefix);
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String propertyName : enumerable.getPropertyNames()) {
                        Integer version = extractVersion(propertyName, dottedPrefix, '.');
                        if (version == null) {
                            version = extractVersion(propertyName, envStylePrefix, '_');
                        }
                        if (version != null) {
                            versions.add(version);
                        }
                    }
                }
            }
            return versions;
        }

        private Integer extractVersion(String propertyName, String prefix, char separator) {
            if (!propertyName.startsWith(prefix)) {
                return null;
            }
            String rest = propertyName.substring(prefix.length());
            int sep = rest.indexOf(separator);
            String version = sep < 0 ? rest : rest.substring(0, sep);
            if (version.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(version);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Property '" + propertyName + "' names version '" + version + "' of modular service '"
                        + name + "', but versions must be integers (e.g. modular.services." + name + ".versions.2.mode)");
            }
        }

        private static String toEnvVarStyle(String dotted) {
            return dotted.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        }
    }
}
