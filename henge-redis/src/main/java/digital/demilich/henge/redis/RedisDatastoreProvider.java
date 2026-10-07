package digital.demilich.henge.redis;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastoreProvider;
import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

/**
 * {@code henge.store.type=redis}, connecting to {@code henge.store.redis.uri}: a Lettuce URI such
 * as {@code redis://host:6379/0}, {@code rediss://} for TLS, {@code redis://:password@host} for a
 * password, with options as query parameters ({@code ?timeout=5s}); {@code henge.store.redis.timeout} sets the timeout instead. For a Redis Cluster, give
 * {@code henge.store.redis.cluster-nodes} instead: a comma-separated list of seed URIs.
 */
public final class RedisDatastoreProvider implements SystemEphemeralDatastoreProvider {

    @Override
    public String type() {
        return "redis";
    }

    @Override
    public SystemEphemeralDatastore create(UnaryOperator<String> property) {
        String uri = property.apply("henge.store.redis.uri");
        String clusterNodes = property.apply("henge.store.redis.cluster-nodes");
        boolean hasUri = uri != null && !uri.isBlank();
        boolean hasCluster = clusterNodes != null && !clusterNodes.isBlank();
        if (hasUri == hasCluster) {
            throw new IllegalStateException("henge.store.type=redis needs exactly one of henge.store.redis.uri (e.g. "
                    + "redis://localhost:6379) or henge.store.redis.cluster-nodes (e.g. redis://node1:6379,redis://node2:6379)");
        }
        Duration timeout = timeout(property.apply("henge.store.redis.timeout"));
        if (hasCluster) {
            return RedisEphemeralDatastore.connectCluster(Arrays.stream(clusterNodes.split(",")).map(String::trim).toList(), timeout);
        }
        return RedisEphemeralDatastore.connect(uri.trim(), timeout);
    }

    /** {@code null} if unset. A bare number is milliseconds; {@code 500ms}, {@code 2s} and ISO-8601 ({@code PT2S}) work too. */
    private static Duration timeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        Duration timeout;
        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                timeout = Duration.parse(value);
            } else {
                Matcher m = DURATION.matcher(value);
                if (!m.matches()) {
                    throw new IllegalArgumentException(value);
                }
                long amount = Long.parseLong(m.group(1));
                timeout = switch (m.group(2) == null ? "ms" : m.group(2)) {
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    default -> throw new IllegalArgumentException(value);
                };
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("henge.store.redis.timeout=" + raw + " is not a duration; use milliseconds "
                    + "(500), a unit suffix (500ms, 2s) or ISO-8601 (PT2S)", e);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("henge.store.redis.timeout=" + raw + " must be positive: a store with no "
                    + "timeout holds every caller until the connection gives up");
        }
        return timeout;
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m)?");
}
