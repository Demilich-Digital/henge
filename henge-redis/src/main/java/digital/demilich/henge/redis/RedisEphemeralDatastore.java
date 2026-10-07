package digital.demilich.henge.redis;

import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@link SystemEphemeralDatastore} on Redis (7.4 or later, for hash-field expiry; Valkey is
 * untested). A key is a hash, {@code henge:<key>}, with one field per member
 * ({@code <nodeId>/<localName>}) that carries its own TTL ({@code HPEXPIRE}), so a member expires on
 * its own and the hash vanishes with its last one. Each operation is one Lua script, which is what
 * makes {@link #claim} atomic: Redis runs one at a time, and it does so on its own clock, so deadlines
 * never depend on a writer's.
 *
 * <p>The epoch is the server's {@code run_id}: a Redis restart changes it, and with it everything it
 * held is gone. No persistence or replication is needed or wanted. A key lives wholly on one node,
 * so {@link #connectCluster} shards by Redis Cluster slot with nothing more to do, and the epoch a
 * read reports is that of the shard holding the key.
 */
public final class RedisEphemeralDatastore implements SystemEphemeralDatastore, AutoCloseable {

    private static final String KEY_PREFIX = "henge:";
    private static final String BUCKET_PREFIX = "henge:bucket:";

    /**
     * Gives {@code field} its TTL (writing a field clears it) and the hash its own: the hash lives as
     * long as its longest-lived member. Expects the script's locals {@code ttl} and {@code field}.
     */
    private static final String EXPIRE = """
            redis.call('HPEXPIRE', KEYS[1], ttl, 'FIELDS', 1, field)
            local pttl = redis.call('PTTL', KEYS[1])
            if pttl < tonumber(ttl) then redis.call('PEXPIRE', KEYS[1], ttl) end
            """;

    /** KEYS: hash. ARGV: field, value, ttl-millis. */
    private static final String PUT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            local ttl, field = ARGV[3], ARGV[1]
            """ + EXPIRE + """
            return 1
            """;

    /** KEYS: hash. ARGV: field. */
    private static final String REMOVE = """
            return redis.call('HDEL', KEYS[1], ARGV[1])
            """;

    /** KEYS: hash. Returns the server's run_id, then field, value for every live member. */
    private static final String READ = """
            local result = {string.match(redis.call('INFO', 'server'), 'run_id:(%x+)')}
            for _, item in ipairs(redis.call('HGETALL', KEYS[1])) do result[#result + 1] = item end
            return result
            """;

    /** KEYS: hash. ARGV: field, amount, capacity, ttl-millis, amount as 4 big-endian bytes. */
    private static final String CLAIM = """
            local others = 0
            local all = redis.call('HGETALL', KEYS[1])
            for i = 1, #all, 2 do
              if all[i] ~= ARGV[1] then
                if #all[i + 1] ~= 4 then return redis.error_reply('not a claimed amount: ' .. all[i]) end
                local b1, b2, b3, b4 = string.byte(all[i + 1], 1, 4)
                others = others + (((b1 * 256 + b2) * 256 + b3) * 256 + b4)
              end
            end
            if others + tonumber(ARGV[2]) > tonumber(ARGV[3]) then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[5])
            local ttl, field = ARGV[4], ARGV[1]
            """ + EXPIRE + """
            return 1
            """;

    /**
     * KEYS: the bucket, a string {@code level:at}. ARGV: amount, capacity, permits, period-millis.
     * Levels are in units of 1/period of a permit, so the leak is exact integer arithmetic: each
     * elapsed millisecond drains {@code permits} units. Time is the server's. A drained bucket is
     * the same as no bucket, so the key's TTL is how long it takes to drain.
     */
    private static final String TRY_ACQUIRE = """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local period, permits = tonumber(ARGV[4]), tonumber(ARGV[3])
            local capacity, cost = tonumber(ARGV[2]) * period, tonumber(ARGV[1]) * period
            local level = 0
            local raw = redis.call('GET', KEYS[1])
            if raw then
              local l, a = string.match(raw, '^(%d+):(%d+)$')
              local at = tonumber(a)
              local elapsed = math.min(math.max(0, now - at), capacity)
              level = math.max(0, tonumber(l) - elapsed * permits)
              now = math.max(now, at)
            end
            if level + cost > capacity then return 0 end
            level = level + cost
            if level == 0 then
              redis.call('DEL', KEYS[1])
            else
              redis.call('SET', KEYS[1], string.format('%d:%d', level, now), 'PX', string.format('%d', math.ceil(level / permits)))
            end
            return 1
            """;

    private final String nodeId = UUID.randomUUID().toString();
    private static final String TRY_ACQUIRE_DIGEST = sha1(TRY_ACQUIRE);

    private final StatefulConnection<byte[], byte[]> connection;
    private final RedisScriptingCommands<byte[], byte[]> scripts;
    private final Runnable shutdown;

    private RedisEphemeralDatastore(StatefulConnection<byte[], byte[]> connection,
            RedisScriptingCommands<byte[], byte[]> scripts, Runnable shutdown) {
        this.connection = connection;
        this.scripts = scripts;
        this.shutdown = shutdown;
    }

    /**
     * Connects to the Redis at {@code uri}, e.g. {@code redis://host:6379/0}; {@link #close()} disconnects.
     *
     * @throws StoreUnavailableException if it can't be reached; a malformed {@code uri} is an {@link IllegalStateException}
     */
    public static RedisEphemeralDatastore connect(String uri) {
        return connect(uri, null);
    }

    /** As {@link #connect(String)}, with {@code timeout} (if not {@code null}) in place of the URI's or the default. */
    public static RedisEphemeralDatastore connect(String uri, Duration timeout) {
        RedisURI redisUri = parse(uri, timeout);
        RedisClient client = RedisClient.create(redisUri);
        client.setOptions(ClientOptions.builder().socketOptions(socketOptions(redisUri)).build());
        try {
            StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
            return new RedisEphemeralDatastore(connection, connection.sync(), client::shutdown);
        } catch (RuntimeException e) {
            client.shutdown();
            // RedisURI's toString() masks the password.
            throw new StoreUnavailableException("Can't connect to Redis at " + redisUri + ": " + e.getMessage(), e);
        }
    }

    /**
     * Connects to a Redis Cluster through any of its nodes, e.g. {@code redis://host1:6379}; the rest
     * are discovered, and the topology is refreshed on a schedule and whenever a redirect, a dropped
     * connection or a failed reconnect suggests it moved. {@link #close()} disconnects.
     *
     * <p>Every operation touches exactly one key, so each is routed to the node that owns that key's
     * slot, and nothing needs to be atomic across nodes. The price is that a key's data lives and dies
     * with its shard: a failover to a replica that hadn't seen the last writes, or a resharding, loses
     * them. That is the same wipe the {@link Epoch} already reports, and bounded overshoot is what
     * {@link #claim} and {@link #tryAcquire} already accept.
     */
    public static RedisEphemeralDatastore connectCluster(List<String> seedUris) {
        return connectCluster(seedUris, null);
    }

    /** As {@link #connectCluster(List)}, with {@code timeout} (if not {@code null}) in place of the URIs' or the default. */
    public static RedisEphemeralDatastore connectCluster(List<String> seedUris, Duration timeout) {
        List<RedisURI> seeds = Objects.requireNonNull(seedUris, "seedUris").stream().map(uri -> parse(uri, timeout)).toList();
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("A Redis Cluster needs at least one seed node");
        }
        RedisClusterClient client = RedisClusterClient.create(seeds);
        client.setOptions(ClusterClientOptions.builder()
                .socketOptions(socketOptions(seeds.get(0)))
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(Duration.ofSeconds(30))
                        .enableAllAdaptiveRefreshTriggers()
                        .build())
                .build());
        try {
            StatefulRedisClusterConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
            return new RedisEphemeralDatastore(connection, connection.sync(), client::shutdown);
        } catch (RuntimeException e) {
            client.shutdown();
            throw new StoreUnavailableException("Can't connect to the Redis Cluster at " + seeds + ": " + e.getMessage(), e);
        }
    }

    /**
     * How long a command, or a connection attempt, may take unless the URI says otherwise with
     * {@code ?timeout=} or {@code henge.store.redis.timeout}. Lettuce's own default is a minute, and a store that drops packets (rather than
     * refusing the connection) holds every caller for all of it before the first failure starts the
     * backoff that makes the rest fail fast.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

    /** The TCP connect timeout (initial, and every reconnect) is the command timeout; Lettuce defaults it to 10s. */
    private static SocketOptions socketOptions(RedisURI uri) {
        return SocketOptions.builder().connectTimeout(uri.getTimeout()).build();
    }

    private static RedisURI parse(String uri, Duration timeout) {
        try {
            RedisURI parsed = RedisURI.create(Objects.requireNonNull(uri, "uri"));
            if (timeout != null) {
                parsed.setTimeout(timeout);
            } else if (!uri.contains("timeout=")) {
                parsed.setTimeout(DEFAULT_TIMEOUT);
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("'" + uri + "' is not a Redis URI, e.g. redis://localhost:6379/0", e);
        }
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public void put(String key, String localName, byte[] value, Duration ttl) {
        Objects.requireNonNull(value, "value");
        eval(PUT, ScriptOutputType.INTEGER, key, bytes(field(localName)), value, millis(ttl));
    }

    @Override
    public void remove(String key, String localName) {
        eval(REMOVE, ScriptOutputType.INTEGER, key, bytes(field(localName)));
    }

    @Override
    public Snapshot read(String key) {
        List<byte[]> reply = eval(READ, ScriptOutputType.MULTI, key);
        Map<MemberId, byte[]> members = new HashMap<>();
        for (int i = 1; i < reply.size(); i += 2) {
            String field = new String(reply.get(i), StandardCharsets.UTF_8);
            int slash = field.indexOf('/');
            members.put(new MemberId(field.substring(0, slash), field.substring(slash + 1)), reply.get(i + 1));
        }
        return new Snapshot(Map.copyOf(members), new Epoch(new String(reply.get(0), StandardCharsets.UTF_8)));
    }

    @Override
    public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
        if (amount < 0 || capacity < 0) {
            throw new IllegalArgumentException("amount and capacity must not be negative, got " + amount + " and " + capacity);
        }
        Long granted;
        try {
            granted = eval(CLAIM, ScriptOutputType.INTEGER, key, bytes(field(localName)), bytes(amount), bytes(capacity),
                    millis(ttl), ByteBuffer.allocate(Integer.BYTES).putInt(amount).array());
        } catch (RedisCommandExecutionException e) {
            throw new IllegalStateException("Claiming '" + key + "' failed: " + e.getMessage()
                    + " (a key is for claims or for put, never both)", e);
        }
        return granted == 1;
    }

    /**
     * Runs on every request a limiter guards, so it goes by {@code EVALSHA}. The digest is computed
     * here rather than learned from {@code SCRIPT LOAD}, which in a cluster would load the script on
     * one node and not the one that owns the key. A node that doesn't have the script (a restart,
     * {@code SCRIPT FLUSH}, a replica just promoted) answers NOSCRIPT, and a plain {@code EVAL} then
     * teaches it.
     */
    @Override
    public boolean tryAcquire(String key, int amount, RateLimit limit) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative, got " + amount);
        }
        byte[][] keys = {bytes(BUCKET_PREFIX + key)};
        byte[][] arguments = {bytes(amount), bytes(limit.capacity()), bytes(limit.permits()), bytes(limit.periodMillis())};
        Long granted;
        try {
            granted = scripts.evalsha(TRY_ACQUIRE_DIGEST, ScriptOutputType.INTEGER, keys, arguments);
        } catch (RedisNoScriptException e) {
            granted = scripts.eval(bytes(TRY_ACQUIRE), ScriptOutputType.INTEGER, keys, arguments);
        }
        return granted == 1;
    }

    @Override
    public void close() {
        connection.close();
        shutdown.run();
    }

    private <T> T eval(String script, ScriptOutputType type, String key, byte[]... arguments) {
        return scripts.eval(bytes(script), type, new byte[][] {bytes(KEY_PREFIX + key)}, arguments);
    }

    private static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes(text)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-1", e);
        }
    }

    private String field(String localName) {
        return nodeId + "/" + localName;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(long number) {
        return bytes(Long.toString(number));
    }

    /** At least one millisecond: a TTL of zero would delete the member the moment it is written. */
    private static byte[] millis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        return bytes(Math.max(1, ttl.toMillis()));
    }
}
