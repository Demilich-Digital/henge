package digital.demilich.henge.redis;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastoreProvider;
import java.util.function.UnaryOperator;

/**
 * {@code modular.store.type=redis}, connecting to {@code modular.store.redis.uri}: a Lettuce URI such
 * as {@code redis://host:6379/0}, {@code rediss://} for TLS, {@code redis://:password@host} for a
 * password, with options as query parameters ({@code ?timeout=5s}).
 */
public final class RedisDatastoreProvider implements SystemEphemeralDatastoreProvider {

    @Override
    public String type() {
        return "redis";
    }

    @Override
    public SystemEphemeralDatastore create(UnaryOperator<String> property) {
        String uri = property.apply("modular.store.redis.uri");
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("modular.store.type=redis needs modular.store.redis.uri, e.g. redis://localhost:6379");
        }
        return RedisEphemeralDatastore.connect(uri.trim());
    }
}
