package digital.demilich.henge.redis;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastoreProvider;
import java.util.Arrays;
import java.util.function.UnaryOperator;

/**
 * {@code modular.store.type=redis}, connecting to {@code modular.store.redis.uri}: a Lettuce URI such
 * as {@code redis://host:6379/0}, {@code rediss://} for TLS, {@code redis://:password@host} for a
 * password, with options as query parameters ({@code ?timeout=5s}). For a Redis Cluster, give
 * {@code modular.store.redis.cluster-nodes} instead: a comma-separated list of seed URIs.
 */
public final class RedisDatastoreProvider implements SystemEphemeralDatastoreProvider {

    @Override
    public String type() {
        return "redis";
    }

    @Override
    public SystemEphemeralDatastore create(UnaryOperator<String> property) {
        String uri = property.apply("modular.store.redis.uri");
        String clusterNodes = property.apply("modular.store.redis.cluster-nodes");
        boolean hasUri = uri != null && !uri.isBlank();
        boolean hasCluster = clusterNodes != null && !clusterNodes.isBlank();
        if (hasUri == hasCluster) {
            throw new IllegalStateException("modular.store.type=redis needs exactly one of modular.store.redis.uri (e.g. "
                    + "redis://localhost:6379) or modular.store.redis.cluster-nodes (e.g. redis://node1:6379,redis://node2:6379)");
        }
        if (hasCluster) {
            return RedisEphemeralDatastore.connectCluster(Arrays.stream(clusterNodes.split(",")).map(String::trim).toList());
        }
        return RedisEphemeralDatastore.connect(uri.trim());
    }
}
