package digital.demilich.henge.spring;

import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.ServiceNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;

/**
 * Reads {@code henge.*} configuration directly from Spring's {@link Environment} on demand —
 * no pre-binding, no annotation-driven binding infrastructure, so this works identically whether
 * the surrounding application is Spring Boot or plain Spring Framework.
 */
public class HengeProperties {

    /**
     * Spring Boot's {@code ConfigurationPropertySources.ATTACHED_PROPERTY_SOURCE_NAME}: a view over
     * every other source, placed first, so it says nothing about which of them a value came from.
     * Named here rather than referenced, to keep this module Boot-free.
     */
    private static final String BOOT_ATTACHED_SOURCE = "configurationProperties";

    private final Environment environment;

    public HengeProperties(Environment environment) {
        this.environment = environment;
    }

    /**
     * {@code henge.server.path-prefix}, default {@code /_henge}. Must start with {@code /} and not
     * end with one: the client appends it to a base URL and the server maps it, so {@code _henge}
     * would make {@code http://host:8080_henge}, and a trailing {@code /} a {@code //} in the path.
     * Empty is rejected too -- the Boot starter's security chain matches {@code prefix + "/**"}, which
     * would then cover every POST the application serves.
     */
    public String getServerPathPrefix() {
        String prefix = environment.getProperty("henge.server.path-prefix", "/_henge");
        if (!prefix.startsWith("/") || prefix.endsWith("/")) {
            throw new IllegalStateException("henge.server.path-prefix '" + prefix + "' must start with '/' and not end "
                    + "with one, e.g. /_henge");
        }
        return prefix;
    }

    /**
     * {@code henge.advertise.url}: the base URL other processes reach this one's {@code /_henge} at,
     * published in its service advertisements; {@code null} if unset. Must be an absolute http(s) URL.
     */
    public String getAdvertiseUrl() {
        String url = environment.getProperty("henge.advertise.url");
        if (url == null || url.isBlank()) {
            return null;
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalStateException("henge.advertise.url '" + url + "' must be an absolute http:// or https:// URL, "
                    + "e.g. http://10.0.0.7:8080");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * {@code henge.topology.enabled}: whether this process serves its topology (as JSON and as a page)
     * under the {@code /_henge} prefix. Off unless set, since it lists every service's host.
     */
    public boolean isTopologyEnabled() {
        return environment.getProperty("henge.topology.enabled", Boolean.class, false);
    }

    /** {@link #getRecentVersions()} when {@code henge.recent-versions} is unset. */
    static final int DEFAULT_RECENT_VERSIONS = 2;

    /**
     * {@code henge.recent-versions}, default 2: how many of the most recent versions of each service
     * this process runs. Two is a deploy from the previous version to the latest, or a rollback of one;
     * raise it to run more at once. A positive integer.
     */
    public int getRecentVersions() {
        Integer configured = positiveInt(environment, "henge.recent-versions");
        return configured != null ? configured : DEFAULT_RECENT_VERSIONS;
    }

    /** {@code henge.remote-url-template}; {@code null} if unset. */
    public String getRemoteUrlTemplate() {
        return environment.getProperty("henge.remote-url-template");
    }

    /** {@code henge.transport.connect-timeout}, default 2 seconds -- see {@link #timeout}. */
    public Duration getConnectTimeout() {
        return timeout("henge.transport.connect-timeout", Duration.ofSeconds(2), "no timeout");
    }

    /** {@code henge.transport.read-timeout}, default 10 seconds -- see {@link #timeout}. */
    public Duration getReadTimeout() {
        return timeout("henge.transport.read-timeout", Duration.ofSeconds(10), "no timeout");
    }

    /**
     * {@code henge.transport.retry.*}: {@code max-attempts} (default 3, the first call included; 1
     * turns retries off), {@code backoff} (default 50ms, a duration as for the timeouts; 0 retries at
     * once) and {@code on} (default {@code connect,not-served}: which failures are retried, see
     * {@link RetryPolicy}). Invalid values fail at startup, naming the property.
     */
    RetryPolicy getRetryPolicy() {
        String attemptsKey = "henge.transport.retry.max-attempts";
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
        Duration backoff = timeout("henge.transport.retry.backoff", RetryPolicy.DEFAULT.backoff(), "no delay");

        boolean onConnect = RetryPolicy.DEFAULT.onConnectFailure();
        boolean onNotServed = RetryPolicy.DEFAULT.onNotServed();
        String rawOn = environment.getProperty("henge.transport.retry.on");
        if (rawOn != null && !rawOn.isBlank()) {
            onConnect = false;
            onNotServed = false;
            for (String kind : rawOn.split(",")) {
                switch (kind.trim()) {
                    case "connect" -> onConnect = true;
                    case "not-served" -> onNotServed = true;
                    default -> throw new IllegalStateException("henge.transport.retry.on=" + rawOn + " names '" + kind.trim()
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
     * {@code henge.transport.secret}; {@code null} if unset. When set, {@link InternalRestTransport}
     * sends it on every dispatch call and {@link HengeDispatcherController} requires it (constant-time
     * compare, 403 otherwise) -- see docs/guide/07-operating.md.
     */
    public String getTransportSecret() {
        return environment.getProperty("henge.transport.secret");
    }

    /**
     * {@code henge.serve}: either one comma-separated value ({@code --henge.serve=a,b}, or the
     * {@code HENGE_SERVE} environment variable) or a list ({@code henge.serve[0]}, ... -- what a
     * YAML list becomes). When both forms are set, the one from the higher-precedence property source
     * wins outright, as a later value replaces an earlier one anywhere else in Spring's configuration,
     * instead of the two being merged. Split manually rather than relying on the conversion service's
     * String-to-List behavior -- simpler and predictable regardless of which {@code Environment}
     * implementation this ends up wrapping.
     */
    public List<String> getServe() {
        List<String> entries = new ArrayList<>();
        if (serveIsAList()) {
            for (int i = 0; environment.getProperty("henge.serve[" + i + "]") != null; i++) {
                addCommaSeparated(entries, environment.getProperty("henge.serve[" + i + "]"));
            }
        } else {
            addCommaSeparated(entries, environment.getProperty("henge.serve"));
        }
        return entries;
    }

    /** Whether the highest-precedence source that sets {@code henge.serve} at all uses the list form. */
    private boolean serveIsAList() {
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (BOOT_ATTACHED_SOURCE.equals(source.getName())) {
                    continue;
                }
                if (source.containsProperty("henge.serve")) {
                    return false;
                }
                if (source.containsProperty("henge.serve[0]")) {
                    return true;
                }
            }
        }
        return environment.getProperty("henge.serve") == null && environment.getProperty("henge.serve[0]") != null;
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

    private static final Pattern DOTTED_SERVICE_KEY = Pattern.compile("mode|url|versions\\.\\d+\\.(mode|url)");
    private static final Pattern ENV_SERVICE_KEY = Pattern.compile("MODE|URL|VERSIONS_\\d+_(MODE|URL)");

    /**
     * Every {@code henge.services.*} property (dotted, or as a {@code HENGE_SERVICES_*}
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
                if (propertyName.startsWith("henge.services.")) {
                    problem = checkDotted(propertyName, serviceNames);
                } else if (propertyName.startsWith("HENGE_SERVICES_")) {
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
        String rest = propertyName.substring("henge.services.".length());
        int dot = rest.indexOf('.');
        String name = dot < 0 ? rest : rest.substring(0, dot);
        if (!serviceNames.contains(name)) {
            return propertyName + ": no @HengeService is named '" + name + "'" + suggestion(name, serviceNames);
        }
        if (dot < 0 || !DOTTED_SERVICE_KEY.matcher(rest.substring(dot + 1)).matches()) {
            return propertyName + ": not a known key (mode, url, versions.<n>.mode, versions.<n>.url)";
        }
        return null;
    }

    /**
     * The environment-variable form can't be split into name and key by itself ({@code -} and
     * {@code .} both became {@code _}), so it's fine if any discovered service's name and a known
     * key account for it.
     */
    private static String checkEnvVar(String propertyName, Set<String> serviceNames) {
        String rest = propertyName.substring("HENGE_SERVICES_".length());
        for (String name : serviceNames) {
            String envName = toEnvVarStyle(name) + "_";
            if (rest.startsWith(envName) && ENV_SERVICE_KEY.matcher(rest.substring(envName.length())).matches()) {
                return null;
            }
        }
        return propertyName + ": doesn't match any discovered service and key (HENGE_SERVICES_<NAME>_MODE, _URL, "
                + "_VERSIONS_<n>_MODE or _VERSIONS_<n>_URL; discovered: " + serviceNames + ")";
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
     * {@code henge.leases.<lease>.capacity}: the cluster-wide capacity of a resource that services
     * claim shares of with {@code @RequiresLease}; {@code null} if unset. A positive integer.
     */
    public Integer leaseCapacity(String lease) {
        return positiveInt(environment, "henge.leases." + lease + ".capacity");
    }

    /**
     * {@code henge.leases.<lease>.amount}: what one node claims of the lease, shared by every service
     * on that node that declares it; {@code null} if unset. A positive integer.
     */
    public Integer leaseAmount(String lease) {
        return positiveInt(environment, "henge.leases." + lease + ".amount");
    }

    /**
     * Every {@code henge.leases.*} property that names a lease no service declares, or a key other
     * than {@code capacity} or {@code amount}, as one line each. Nothing else would read one, so a typo would leave the
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
                if (!propertyName.startsWith("henge.leases.")) {
                    continue;
                }
                String rest = propertyName.substring("henge.leases.".length());
                int dot = rest.indexOf('.');
                String lease = dot < 0 ? rest : rest.substring(0, dot);
                if (!leaseNames.contains(lease)) {
                    problems.add(propertyName + ": no @RequiresLease names '" + lease + "'" + suggestion(lease, leaseNames));
                } else if (dot < 0 || !Set.of("capacity", "amount").contains(rest.substring(dot + 1))) {
                    problems.add(propertyName + ": not a known key (capacity, amount)");
                }
            }
        }
        return List.copyOf(problems);
    }

    private static final Set<String> RATE_LIMIT_KEYS = Set.of("permits", "period", "capacity");
    private static final String RATE_LIMITS = "henge.rate-limits.";
    private static final String ENV_RATE_LIMITS = toEnvVarStyle(RATE_LIMITS);

    /**
     * Every limiter configured under {@code henge.rate-limits.<name>}, by name: {@code permits} drain
     * every {@code period} (both required), from a bucket holding at most {@code capacity} (the burst;
     * default {@code permits}). The configuration is the declaration, so the names are found by scanning
     * the enumerable property sources, in the dotted form and as {@code HENGE_RATE_LIMITS_<NAME>_<KEY>}
     * environment variables (a name is lowercase kebab case, so its {@code _} can only have been a
     * {@code -}). A malformed name, an unknown key, or a missing or invalid value fails, naming the property.
     */
    public Map<String, RateLimit> rateLimits() {
        Set<String> names = new TreeSet<>();
        Set<String> problems = new LinkedHashSet<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String propertyName : enumerable.getPropertyNames()) {
                    String name;
                    String key;
                    if (propertyName.startsWith(RATE_LIMITS)) {
                        String rest = propertyName.substring(RATE_LIMITS.length());
                        int dot = rest.indexOf('.');
                        name = dot < 0 ? rest : rest.substring(0, dot);
                        key = dot < 0 ? "" : rest.substring(dot + 1);
                    } else if (propertyName.startsWith(ENV_RATE_LIMITS)) {
                        String rest = propertyName.substring(ENV_RATE_LIMITS.length());
                        int underscore = rest.lastIndexOf('_');
                        name = underscore < 0 ? "" : rest.substring(0, underscore).toLowerCase(Locale.ROOT).replace('_', '-');
                        key = rest.substring(underscore + 1).toLowerCase(Locale.ROOT);
                    } else {
                        continue;
                    }
                    if (!ServiceNames.isValidServiceName(name)) {
                        problems.add(propertyName + ": a rate limit's name must be lowercase kebab case, e.g. "
                                + "henge.rate-limits.notifications.permits");
                    } else if (!RATE_LIMIT_KEYS.contains(key)) {
                        problems.add(propertyName + ": not a known key (permits, period, capacity)");
                    } else {
                        names.add(name);
                    }
                }
            }
        }
        Map<String, RateLimit> limits = new LinkedHashMap<>();
        for (String name : names) {
            try {
                limits.put(name, rateLimit(name));
            } catch (IllegalStateException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid henge.rate-limits configuration:\n  " + String.join("\n  ", problems));
        }
        return limits;
    }

    private RateLimit rateLimit(String name) {
        String prefix = RATE_LIMITS + name + ".";
        Integer permits = positiveInt(environment, prefix + "permits");
        String rawPeriod = environment.getProperty(prefix + "period");
        if (permits == null || rawPeriod == null || rawPeriod.isBlank()) {
            throw new IllegalStateException(prefix + "permits and " + prefix + "period are both required: permits drain "
                    + "every period, e.g. permits=10 and period=1s");
        }
        Duration period;
        try {
            period = DurationFormatterUtils.detectAndParse(rawPeriod.trim(), DurationFormat.Unit.MILLIS);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(prefix + "period=" + rawPeriod + " is not a duration; use milliseconds (1000), "
                    + "a unit suffix (1s, 500ms) or ISO-8601 (PT1S)");
        }
        Integer capacity = positiveInt(environment, prefix + "capacity");
        try {
            return new RateLimit(capacity != null ? capacity : permits, permits, period);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(prefix + "period=" + rawPeriod + ": " + e.getMessage());
        }
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

        private String resolve(String key, int version) {
            String versioned = environment.getProperty("henge.services." + name + ".versions." + version + "." + key);
            return versioned != null ? versioned : environment.getProperty("henge.services." + name + "." + key);
        }

        /**
         * Versions with any explicit {@code henge.services.<name>.versions.<version>.*}
         * config, found by scanning enumerable property sources directly — the only way to
         * discover "which keys exist under this prefix" without Boot's relaxed-binding
         * {@code Binder}. Requires a {@link ConfigurableEnvironment} (always the actual runtime
         * type in a real {@code ApplicationContext}); returns empty otherwise.
         *
         * <p>Checks each enumerated property name against both the literal dotted-kebab prefix
         * (what config files/command-line args use) and its env-var-style equivalent
         * (uppercased, {@code .}/{@code -} both mapped to {@code _}) — an OS environment variable
         * property source enumerates its keys in raw {@code HENGE_SERVICES_...} form, never
         * translated to the dotted form (that translation only happens lazily, per-key, inside
         * {@link Environment#getProperty(String)} — see {@code SystemEnvironmentPropertySource}),
         * so a literal-only scan would silently miss a version declared purely via an env var.
         */
        public Set<Integer> explicitVersions() {
            Set<Integer> versions = new LinkedHashSet<>();
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                return versions;
            }
            String dottedPrefix = "henge.services." + name + ".versions.";
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
         *     {@code HENGE_SERVICES_X_VERSIONS_MODE} (the latter's mode) also starts with the
         *     former's {@code HENGE_SERVICES_X_VERSIONS_} prefix.
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
                throw new IllegalStateException("Property '" + propertyName + "' names version '" + version + "' of Henge service '"
                        + name + "', but versions must be integers (e.g. henge.services." + name + ".versions.2.mode)");
            }
        }


    }
}
