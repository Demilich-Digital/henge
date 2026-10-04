package digital.demilich.henge.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;

/**
 * Reads {@code modular.*} configuration directly from Spring's {@link Environment} on demand —
 * no pre-binding, no annotation-driven binding infrastructure, so this works identically whether
 * the surrounding application is Spring Boot or plain Spring Framework.
 */
public class ModularProperties {

    /**
     * Spring Boot's {@code ConfigurationPropertySources.ATTACHED_PROPERTY_SOURCE_NAME}: a view over
     * every other source, placed first, so it says nothing about which of them a value came from.
     * Named here rather than referenced, to keep this module Boot-free.
     */
    private static final String BOOT_ATTACHED_SOURCE = "configurationProperties";

    private final Environment environment;

    public ModularProperties(Environment environment) {
        this.environment = environment;
    }

    /**
     * {@code modular.server.path-prefix}, default {@code /_modular}. Must start with {@code /} and not
     * end with one: the client appends it to a base URL and the server maps it, so {@code _modular}
     * would make {@code http://host:8080_modular}, and a trailing {@code /} a {@code //} in the path.
     * Empty is rejected too -- the Boot starter's security chain matches {@code prefix + "/**"}, which
     * would then cover every POST the application serves.
     */
    public String getServerPathPrefix() {
        String prefix = environment.getProperty("modular.server.path-prefix", "/_modular");
        if (!prefix.startsWith("/") || prefix.endsWith("/")) {
            throw new IllegalStateException("modular.server.path-prefix '" + prefix + "' must start with '/' and not end "
                    + "with one, e.g. /_modular");
        }
        return prefix;
    }

    /**
     * {@code modular.advertise.url}: the base URL other processes reach this one's {@code /_modular} at,
     * published in its service advertisements; {@code null} if unset. Must be an absolute http(s) URL.
     */
    public String getAdvertiseUrl() {
        String url = environment.getProperty("modular.advertise.url");
        if (url == null || url.isBlank()) {
            return null;
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalStateException("modular.advertise.url '" + url + "' must be an absolute http:// or https:// URL, "
                    + "e.g. http://10.0.0.7:8080");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** {@code modular.remote-url-template}; {@code null} if unset. */
    public String getRemoteUrlTemplate() {
        return environment.getProperty("modular.remote-url-template");
    }

    /** {@code modular.transport.connect-timeout}, default 2 seconds -- see {@link #timeout}. */
    public Duration getConnectTimeout() {
        return timeout("modular.transport.connect-timeout", Duration.ofSeconds(2), "no timeout");
    }

    /** {@code modular.transport.read-timeout}, default 10 seconds -- see {@link #timeout}. */
    public Duration getReadTimeout() {
        return timeout("modular.transport.read-timeout", Duration.ofSeconds(10), "no timeout");
    }

    /**
     * {@code modular.transport.retry.*}: {@code max-attempts} (default 3, the first call included; 1
     * turns retries off), {@code backoff} (default 50ms, a duration as for the timeouts; 0 retries at
     * once) and {@code on} (default {@code connect,not-served}: which failures are retried, see
     * {@link RetryPolicy}). Invalid values fail at startup, naming the property.
     */
    RetryPolicy getRetryPolicy() {
        String attemptsKey = "modular.transport.retry.max-attempts";
        String rawAttempts = environment.getProperty(attemptsKey);
        int attempts = RetryPolicy.DEFAULT.maxAttempts();
        if (rawAttempts != null && !rawAttempts.isBlank()) {
            try {
                attempts = Integer.parseInt(rawAttempts.trim());
            } catch (NumberFormatException e) {
                attempts = 0;
            }
            if (attempts < 1) {
                throw new IllegalStateException(attemptsKey + "=" + rawAttempts + " is not a positive integer; use 1 for no retries");
            }
        }
        Duration backoff = timeout("modular.transport.retry.backoff", RetryPolicy.DEFAULT.backoff(), "no delay");

        boolean onConnect = RetryPolicy.DEFAULT.onConnectFailure();
        boolean onNotServed = RetryPolicy.DEFAULT.onNotServed();
        String rawOn = environment.getProperty("modular.transport.retry.on");
        if (rawOn != null && !rawOn.isBlank()) {
            onConnect = false;
            onNotServed = false;
            for (String kind : rawOn.split(",")) {
                switch (kind.trim()) {
                    case "connect" -> onConnect = true;
                    case "not-served" -> onNotServed = true;
                    default -> throw new IllegalStateException("modular.transport.retry.on=" + rawOn + " names '" + kind.trim()
                            + "'; the failures that can be retried are connect and not-served");
                }
            }
        }
        return new RetryPolicy(attempts, backoff, onConnect, onNotServed);
    }

    /**
     * A bare number is milliseconds ({@code 2000}); a unit suffix ({@code 2s}, {@code 500ms}) or
     * ISO-8601 ({@code PT2S}) also works, the forms Boot accepts for its own durations. {@code 0}
     * means no timeout. Negative or unparseable values fail at startup, naming the property.
     */
    private Duration timeout(String key, Duration defaultValue, String zeroMeans) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        Duration timeout;
        try {
            timeout = DurationFormatterUtils.detectAndParse(raw.trim(), DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + "=" + raw + " is not a duration; use milliseconds (2000), a unit "
                    + "suffix (2s, 500ms) or ISO-8601 (PT2S)", e);
        }
        if (timeout.isNegative()) {
            throw new IllegalStateException(key + "=" + raw + " is negative; use 0 for " + zeroMeans);
        }
        return timeout;
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
     * {@code modular.serve}: either one comma-separated value ({@code --modular.serve=a,b}, or the
     * {@code MODULAR_SERVE} environment variable) or a list ({@code modular.serve[0]}, ... -- what a
     * YAML list becomes). When both forms are set, the one from the higher-precedence property source
     * wins outright, as a later value replaces an earlier one anywhere else in Spring's configuration,
     * instead of the two being merged. Split manually rather than relying on the conversion service's
     * String-to-List behavior -- simpler and predictable regardless of which {@code Environment}
     * implementation this ends up wrapping.
     */
    public List<String> getServe() {
        List<String> entries = new ArrayList<>();
        if (serveIsAList()) {
            for (int i = 0; environment.getProperty("modular.serve[" + i + "]") != null; i++) {
                addCommaSeparated(entries, environment.getProperty("modular.serve[" + i + "]"));
            }
        } else {
            addCommaSeparated(entries, environment.getProperty("modular.serve"));
        }
        return entries;
    }

    /** Whether the highest-precedence source that sets {@code modular.serve} at all uses the list form. */
    private boolean serveIsAList() {
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (BOOT_ATTACHED_SOURCE.equals(source.getName())) {
                    continue;
                }
                if (source.containsProperty("modular.serve")) {
                    return false;
                }
                if (source.containsProperty("modular.serve[0]")) {
                    return true;
                }
            }
        }
        return environment.getProperty("modular.serve") == null && environment.getProperty("modular.serve[0]") != null;
    }

    private static void addCommaSeparated(List<String> entries, String raw) {
        if (raw == null) {
            return;
        }
        for (String entry : raw.split(",")) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
    }

    private static String toEnvVarStyle(String dotted) {
        return dotted.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static final Pattern DOTTED_SERVICE_KEY = Pattern.compile("mode|url|versions\\.\\d+\\.(mode|url)|leases\\.[^.]+");
    private static final Pattern ENV_SERVICE_KEY = Pattern.compile("MODE|URL|VERSIONS_\\d+_(MODE|URL)|LEASES_.+");

    /**
     * Every {@code modular.services.*} property (dotted, or as a {@code MODULAR_SERVICES_*}
     * environment variable) that names no discovered service, or a key that doesn't exist, as one
     * line each saying what's wrong. Nothing else would ever read such a property, so a typo --
     * {@code .mdoe}, or a misspelled service name -- would silently leave that service on its
     * default mode. Same enumerable-source scan as {@link ServiceConfig#explicitVersions()}.
     */
    public List<String> unknownServiceProperties(Set<String> serviceNames) {
        Set<String> problems = new LinkedHashSet<>();
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return List.of();
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String propertyName : enumerable.getPropertyNames()) {
                String problem = null;
                if (propertyName.startsWith("modular.services.")) {
                    problem = checkDotted(propertyName, serviceNames);
                } else if (propertyName.startsWith("MODULAR_SERVICES_")) {
                    problem = checkEnvVar(propertyName, serviceNames);
                }
                if (problem != null) {
                    problems.add(problem);
                }
            }
        }
        return List.copyOf(problems);
    }

    private static String checkDotted(String propertyName, Set<String> serviceNames) {
        String rest = propertyName.substring("modular.services.".length());
        int dot = rest.indexOf('.');
        String name = dot < 0 ? rest : rest.substring(0, dot);
        if (!serviceNames.contains(name)) {
            return propertyName + ": no @ModularService is named '" + name + "'" + suggestion(name, serviceNames);
        }
        if (dot < 0 || !DOTTED_SERVICE_KEY.matcher(rest.substring(dot + 1)).matches()) {
            return propertyName + ": not a known key (mode, url, versions.<n>.mode, versions.<n>.url, leases.<lease>)";
        }
        return null;
    }

    /**
     * The environment-variable form can't be split into name and key by itself ({@code -} and
     * {@code .} both became {@code _}), so it's fine if any discovered service's name and a known
     * key account for it.
     */
    private static String checkEnvVar(String propertyName, Set<String> serviceNames) {
        String rest = propertyName.substring("MODULAR_SERVICES_".length());
        for (String name : serviceNames) {
            String envName = toEnvVarStyle(name) + "_";
            if (rest.startsWith(envName) && ENV_SERVICE_KEY.matcher(rest.substring(envName.length())).matches()) {
                return null;
            }
        }
        return propertyName + ": doesn't match any discovered service and key (MODULAR_SERVICES_<NAME>_MODE, _URL, "
                + "_VERSIONS_<n>_MODE, _VERSIONS_<n>_URL or _LEASES_<LEASE>; discovered: " + serviceNames + ")";
    }

    private static String suggestion(String name, Set<String> serviceNames) {
        String closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (String candidate : serviceNames) {
            int distance = editDistance(name, candidate);
            if (distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        return closest != null && closestDistance <= 3 ? " -- did you mean '" + closest + "'?" : " (discovered: " + serviceNames + ")";
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * {@code modular.leases.<lease>.capacity}: the cluster-wide capacity of a resource that services
     * claim shares of with {@code @RequiresLease}; {@code null} if unset. A positive integer.
     */
    public Integer leaseCapacity(String lease) {
        return positiveInt(environment, "modular.leases." + lease + ".capacity");
    }

    /**
     * Every {@code modular.leases.*} property that names a lease no service declares, or a key other
     * than {@code capacity}, as one line each. Nothing else would read one, so a typo would leave the
     * real lease without its capacity (which is itself an error) or, worse, a stale entry unnoticed.
     */
    public List<String> unknownLeaseProperties(Set<String> leaseNames) {
        Set<String> problems = new LinkedHashSet<>();
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return List.of();
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String propertyName : enumerable.getPropertyNames()) {
                if (!propertyName.startsWith("modular.leases.")) {
                    continue;
                }
                String rest = propertyName.substring("modular.leases.".length());
                int dot = rest.indexOf('.');
                String lease = dot < 0 ? rest : rest.substring(0, dot);
                if (!leaseNames.contains(lease)) {
                    problems.add(propertyName + ": no @RequiresLease names '" + lease + "'" + suggestion(lease, leaseNames));
                } else if (dot < 0 || !rest.substring(dot + 1).equals("capacity")) {
                    problems.add(propertyName + ": not a known key (capacity)");
                }
            }
        }
        return List.copyOf(problems);
    }

    private static Integer positiveInt(Environment environment, String key) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below, same as a non-positive number
        }
        throw new IllegalStateException(key + "=" + raw + " is not a positive integer");
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

        /**
         * {@code modular.services.<name>.leases.<lease>}: how much of the lease one instance of this
         * service (any version) claims; {@code null} if unset. A positive integer.
         */
        public Integer leaseAmount(String lease) {
            return positiveInt(environment, "modular.services." + name + ".leases." + lease);
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
                        Integer version = extractVersion(propertyName, dottedPrefix, '.', true);
                        if (version == null) {
                            version = extractVersion(propertyName, envStylePrefix, '_', false);
                        }
                        if (version != null) {
                            versions.add(version);
                        }
                    }
                }
            }
            return versions;
        }

        /**
         * @param strict whether a non-integer version segment is an error. True for the dotted form,
         *     where it can only be a typo; false for the environment-variable form, which is
         *     ambiguous by construction -- with services {@code x} and {@code x-versions},
         *     {@code MODULAR_SERVICES_X_VERSIONS_MODE} (the latter's mode) also starts with the
         *     former's {@code MODULAR_SERVICES_X_VERSIONS_} prefix.
         */
        private Integer extractVersion(String propertyName, String prefix, char separator, boolean strict) {
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
                if (!strict) {
                    return null;
                }
                throw new IllegalStateException("Property '" + propertyName + "' names version '" + version + "' of modular service '"
                        + name + "', but versions must be integers (e.g. modular.services." + name + ".versions.2.mode)");
            }
        }


    }
}
