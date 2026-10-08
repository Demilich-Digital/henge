package digital.demilich.henge.redis;

import digital.demilich.henge.core.Acquisition;
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
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
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
import java.util.function.Function;
import java.util.UUID;

/**
 * A {@link SystemEphemeralDatastore} on Redis (7.4 or later, for hash-field expiry; Valkey is
 * untested). A key is a hash, {@code henge:<key>}, with one field per member
 * ({@code <nodeId>/<localName>}) that carries its own TTL ({@code HPEXPIRE}), so a member expires on
 * its own. Each operation is one Lua script, which is what makes {@link #claim} atomic: Redis runs one
 * at a time, and it does so on its own clock, so deadlines never depend on a writer's.
 *
 * <p>A key's epoch is the server's {@code run_id} and a token kept in the key's own hash, created from
 * the server's clock by the first write after it is missing. A restart, or a failover to a replica that
 * missed writes, changes the {@code run_id}. A flush, or an eviction under {@code maxmemory}, removes the
 * hash, token and all, so a read sees no token, and the next write makes a new one. Expiry keeps it: the
 * token has no TTL of its own, and the hash outlives the last write to it by {@link #RETENTION}. Only a
 * write touches the token, so a read stays a read. Every read still goes to a key's primary, since a
 * replica has its own {@code run_id}. No persistence or replication is needed or wanted. A key lives
 * wholly on one node, so {@link #connectCluster} shards by Redis Cluster slot with nothing more to do.
 *
 * <p>A Redis that is evicting keys is treated as unreachable until it stops, and a full one under
 * {@code noeviction} fails its writes as unreachable: see {@link RedisMemoryWatch}.
 *
 * <p>A version of this class from before the token can't read or claim a key that has one, so every
 * process sharing a Redis has to be on a version with it: an upgrade across it restarts the cluster.
 */
public final class RedisEphemeralDatastore implements SystemEphemeralDatastore, AutoCloseable {

    private static final String KEY_PREFIX = "henge:";
    private static final String BUCKET_PREFIX = "henge:bucket:";

    /**
     * How long a hash outlives the last write to it, holding its token so that an empty key from the same
     * epoch is still believed. Longer than any member's TTL, and than any reader goes between reads of a
     * key it cares about, so a reader sees the members lapse under the token they were written under. A
     * key nobody writes for this long is forgotten, and its epoch changes from one that was already empty:
     * a change with nothing lost, which the contract allows.
     */
    static final Duration RETENTION = Duration.ofHours(1);

    /**
     * Sets the locals {@code run_id}, the server's, and {@code token}, the key's, or {@code false} if it
     * has none. Together they are the key's epoch. The token is the field {@code ~epoch}, which isn't a
     * member: members are {@code <nodeId>/<localName>}, and a node id is a UUID, so none starts with
     * {@code ~}. Writes nothing, so a read stays a read.
     */
    private static final String EPOCH = """
            local run_id = string.match(redis.call('INFO', 'server'), 'run_id:(%x+)')
            local token = redis.call('HGET', KEYS[1], '~epoch')
            """;

    /**
     * After a member is written: gives {@code field} its TTL (writing a field clears it), creates the
     * key's token from the server's clock if it has none (a key that was flushed or evicted lost its token
     * with its members), and keeps the hash at least as long as the member or the retention, whichever is
     * longer, never shortening it. Expects the script's locals {@code ttl} and {@code field}.
     */
    private static final String WRITTEN = """
            redis.call('HPEXPIRE', KEYS[1], ttl, 'FIELDS', 1, field)
            if redis.call('HEXISTS', KEYS[1], '~epoch') == 0 then
              local now = redis.call('TIME')
              redis.call('HSET', KEYS[1], '~epoch', now[1] .. '.' .. now[2])
            end
            local keep = math.max(%d, tonumber(ttl))
            if redis.call('PTTL', KEYS[1]) < keep then redis.call('PEXPIRE', KEYS[1], keep) end
            """.formatted(RETENTION.toMillis());

    /** KEYS: hash. ARGV: field, value, ttl-millis. */
    private static final String PUT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            local ttl, field = ARGV[3], ARGV[1]
            """ + WRITTEN + """
            return 1
            """;

    /** KEYS: hash. ARGV: field. The token stays, so an empty key keeps its epoch until the hash lapses. */
    private static final String REMOVE = """
            return redis.call('HDEL', KEYS[1], ARGV[1])
            """;

    /** KEYS: hash. Returns the run_id and the token (empty if none), then field, value for every live member. */
    private static final String READ = EPOCH + """
            local result = {run_id, token or ''}
            local all = redis.call('HGETALL', KEYS[1])
            for i = 1, #all, 2 do
              if all[i] ~= '~epoch' then
                result[#result + 1] = all[i]
                result[#result + 1] = all[i + 1]
              end
            end
            return result
            """;

    /** KEYS: hash. Returns the run_id, the token (empty if none), and HLEN less the token, as a string. */
    private static final String COUNT = EPOCH + """
            local live = redis.call('HLEN', KEYS[1]) - (token and 1 or 0)
            return {run_id, token or '', tostring(live)}
            """;

    /**
     * KEYS: hash. ARGV: the most members to return. Returns the run_id, the token (empty if none), HLEN
     * less the token as a string, then field, value for up to that many distinct live members at
     * random. One more is asked for than wanted, since the token may be among them.
     */
    private static final String SAMPLE = EPOCH + """
            local limit = tonumber(ARGV[1])
            local result = {run_id, token or '', tostring(redis.call('HLEN', KEYS[1]) - (token and 1 or 0))}
            local drawn = redis.call('HRANDFIELD', KEYS[1], limit + 1, 'WITHVALUES')
            local taken = 0
            for i = 1, #drawn, 2 do
              if drawn[i] ~= '~epoch' and taken < limit then
                result[#result + 1] = drawn[i]
                result[#result + 1] = drawn[i + 1]
                taken = taken + 1
              end
            end
            return result
            """;

    /**
     * KEYS: hash. ARGV: field, amount, capacity, ttl-millis, amount as 4 big-endian bytes. A member's value is a signed amount: negative is a claim being given up, which a claim being
     * renewed isn't asked to make room for, and a new one is. A new claim that is refused writes nothing.
     */
    private static final String CLAIM = """
            local ttl, field = ARGV[4], ARGV[1]
            local amount = tonumber(ARGV[2])
            local function write(value)
              redis.call('HSET', KEYS[1], field, value)
            """ + WRITTEN + """
            end
            if amount < 0 then write(ARGV[5]); return 1 end
            local own = nil
            local others, leaving = 0, 0
            local all = redis.call('HGETALL', KEYS[1])
            for i = 1, #all, 2 do
              if all[i] ~= '~epoch' then
                if #all[i + 1] ~= 4 then return redis.error_reply('not a claimed amount: ' .. all[i]) end
                local b1, b2, b3, b4 = string.byte(all[i + 1], 1, 4)
                local value = ((b1 * 256 + b2) * 256 + b3) * 256 + b4
                if value >= 2147483648 then value = value - 4294967296 end
                if all[i] == field then
                  own = value
                elseif value < 0 then
                  leaving = leaving - value
                else
                  others = others + value
                end
              end
            end
            local renewing = own ~= nil and own >= 0
            if not renewing then others = others + leaving end
            if others + amount > tonumber(ARGV[3]) then
              if renewing then
                -- Kept at what it holds, now as a claim being given up: 4 big-endian bytes of -own.
                local n = 4294967296 - own
                write(string.char(math.floor(n / 16777216) % 256, math.floor(n / 65536) % 256, math.floor(n / 256) % 256, n % 256))
              end
              return 0
            end
            write(ARGV[5])
            return 1
            """;

    /**
     * KEYS: the bucket, a string {@code level:at}. ARGV: amount, capacity, permits, period-millis.
     * Levels are in units of 1/period of a permit, so the leak is exact integer arithmetic: each
     * elapsed millisecond drains {@code permits} units. Time is the server's. A drained bucket is
     * the same as no bucket, so the key's TTL is how long it takes to drain. Returns granted (1 or 0),
     * then the milliseconds until one permit fits (0 when granted).
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
            if level + cost > capacity then
              return {0, math.max(0, math.ceil((level + period - capacity) / permits))}
            end
            level = level + cost
            if level == 0 then
              redis.call('DEL', KEYS[1])
            else
              redis.call('SET', KEYS[1], string.format('%d:%d', level, now), 'PX', string.format('%d', math.ceil(level / permits)))
            end
            return {1, 0}
            """;

    private final String nodeId = UUID.randomUUID().toString();
    private static final String TRY_ACQUIRE_DIGEST = sha1(TRY_ACQUIRE);

    /** How often each server's memory is sampled, to tell whether it is evicting. */
    static final Duration MEMORY_SAMPLE_INTERVAL = Duration.ofSeconds(5);

    private final StatefulConnection<byte[], byte[]> connection;
    private final RedisScriptingCommands<byte[], byte[]> scripts;
    private final RedisMemoryWatch memory;
    private final Runnable shutdown;

    private RedisEphemeralDatastore(StatefulConnection<byte[], byte[]> connection,
            RedisScriptingCommands<byte[], byte[]> scripts, RedisMemoryWatch memory, Runnable shutdown) {
        this.connection = connection;
        this.scripts = scripts;
        this.memory = memory;
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
        return connect(uri, timeout, true);
    }

    /**
     * As {@link #connect(String, Duration)}; with {@code evictionIsOutage} false, a server that evicts keys is
     * logged but still used (see {@link RedisMemoryWatch}), for a Redis shared with a cache.
     */
    public static RedisEphemeralDatastore connect(String uri, Duration timeout, boolean evictionIsOutage) {
        return connect(uri, timeout, MEMORY_SAMPLE_INTERVAL, evictionIsOutage);
    }

    /** As {@link #connect(String, Duration)}, sampling the server's memory every {@code memorySampleInterval}. */
    static RedisEphemeralDatastore connect(String uri, Duration timeout, Duration memorySampleInterval) {
        return connect(uri, timeout, memorySampleInterval, true);
    }

    static RedisEphemeralDatastore connect(String uri, Duration timeout, Duration memorySampleInterval,
            boolean evictionIsOutage) {
        RedisURI redisUri = parse(uri, timeout);
        RedisClient client = RedisClient.create(redisUri);
        client.setOptions(ClientOptions.builder().socketOptions(socketOptions(redisUri)).build());
        try {
            StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
            String server = redisUri.getHost() + ":" + redisUri.getPort();
            RedisMemoryWatch memory = new RedisMemoryWatch(
                    () -> Map.of(server, section -> connection.sync().info(section)), key -> server, memorySampleInterval,
                    evictionIsOutage);
            return new RedisEphemeralDatastore(connection, connection.sync(), memory, client::shutdown);
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
        return connectCluster(seedUris, timeout, true);
    }

    /** As {@link #connectCluster(List, Duration)}, with {@code evictionIsOutage} as for {@link #connect(String, Duration, boolean)}. */
    public static RedisEphemeralDatastore connectCluster(List<String> seedUris, Duration timeout, boolean evictionIsOutage) {
        return connectCluster(seedUris, timeout, MEMORY_SAMPLE_INTERVAL, evictionIsOutage);
    }

    /** As {@link #connectCluster(List, Duration)}, sampling each primary's memory every {@code memorySampleInterval}. */
    static RedisEphemeralDatastore connectCluster(List<String> seedUris, Duration timeout, Duration memorySampleInterval) {
        return connectCluster(seedUris, timeout, memorySampleInterval, true);
    }

    static RedisEphemeralDatastore connectCluster(List<String> seedUris, Duration timeout,
            Duration memorySampleInterval, boolean evictionIsOutage) {
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
            RedisMemoryWatch memory = new RedisMemoryWatch(() -> primaries(connection), key -> {
                RedisClusterNode node = connection.getPartitions().getMasterBySlot(SlotHash.getSlot(key));
                return node == null ? null : name(node);
            }, memorySampleInterval, evictionIsOutage);
            return new RedisEphemeralDatastore(connection, connection.sync(), memory, client::shutdown);
        } catch (RuntimeException e) {
            client.shutdown();
            throw new StoreUnavailableException("Can't connect to the Redis Cluster at " + seeds + ": " + e.getMessage(), e);
        }
    }

    /** Each primary of the cluster as it is known now, by name, as a function from an {@code INFO} section to its text. */
    private static Map<String, Function<String, String>> primaries(StatefulRedisClusterConnection<byte[], byte[]> connection) {
        Map<String, Function<String, String>> primaries = new HashMap<>();
        for (RedisClusterNode node : connection.getPartitions()) {
            if (node.is(RedisClusterNode.NodeFlag.UPSTREAM)) {
                String nodeId = node.getNodeId();
                primaries.put(name(node), section -> connection.getConnection(nodeId).sync().info(section));
            }
        }
        return primaries;
    }

    private static String name(RedisClusterNode node) {
        return node.getUri().getHost() + ":" + node.getUri().getPort();
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
        return new Snapshot(members(reply, 2), epoch(reply));
    }

    /**
     * {@code HLEN}, less the token. It is O(1), and counts fields that have lapsed and that Redis hasn't
     * reclaimed yet (measured on Redis 8.10), which the contract allows: never fewer than the live members.
     */
    @Override
    public Count count(String key) {
        List<byte[]> reply = eval(COUNT, ScriptOutputType.MULTI, key);
        return new Count(Integer.parseInt(text(reply.get(2))), epoch(reply));
    }

    /**
     * {@code HRANDFIELD} with a count, less the token: it returns distinct live fields only (measured on
     * Redis 8.10). The total is {@code HLEN}'s, as {@link #count} gives it.
     */
    @Override
    public Sample sample(String key, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        List<byte[]> reply = eval(SAMPLE, ScriptOutputType.MULTI, key, bytes(limit));
        return new Sample(members(reply, 3), Integer.parseInt(text(reply.get(2))), epoch(reply));
    }

    /** A reply's epoch: the server's {@code run_id} and the key's token, its first two items; {@code <run_id>/} for none. */
    private static Epoch epoch(List<byte[]> reply) {
        return new Epoch(text(reply.get(0)) + "/" + text(reply.get(1)));
    }

    /** The members in {@code reply} from {@code from} on, as field, value pairs. */
    private static Map<MemberId, byte[]> members(List<byte[]> reply, int from) {
        Map<MemberId, byte[]> members = new HashMap<>();
        for (int i = from; i < reply.size(); i += 2) {
            String field = text(reply.get(i));
            int slash = field.indexOf('/');
            members.put(new MemberId(field.substring(0, slash), field.substring(slash + 1)), reply.get(i + 1));
        }
        return Map.copyOf(members);
    }

    @Override
    public boolean claim(String key, String localName, int amount, int capacity, Duration ttl) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative, got " + capacity);
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
    public Acquisition tryAcquire(String key, int amount, RateLimit limit) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative, got " + amount);
        }
        byte[][] keys = {bytes(BUCKET_PREFIX + key)};
        byte[][] arguments = {bytes(amount), bytes(limit.capacity()), bytes(limit.permits()), bytes(limit.periodMillis())};
        memory.check(keys[0]);
        List<Long> reply;
        try {
            try {
                reply = scripts.evalsha(TRY_ACQUIRE_DIGEST, ScriptOutputType.MULTI, keys, arguments);
            } catch (RedisNoScriptException e) {
                reply = scripts.eval(bytes(TRY_ACQUIRE), ScriptOutputType.MULTI, keys, arguments);
            }
        } catch (RedisCommandExecutionException e) {
            throw fullOr(e);
        }
        return reply.get(0) == 1 ? Acquisition.GRANTED : Acquisition.refused(Duration.ofMillis(reply.get(1)));
    }

    @Override
    public void close() {
        memory.close();
        connection.close();
        shutdown.run();
    }

    private <T> T eval(String script, ScriptOutputType type, String key, byte[]... arguments) {
        byte[] hash = bytes(KEY_PREFIX + key);
        memory.check(hash);
        try {
            return scripts.eval(bytes(script), type, new byte[][] {hash}, arguments);
        } catch (RedisCommandExecutionException e) {
            throw fullOr(e);
        }
    }

    /**
     * A Redis at {@code maxmemory} under {@code noeviction} refuses a script's first write, and runs the
     * rest, so what is there can still be read. A refused write is the store failing honestly as an outage,
     * so it is {@link StoreUnavailableException}, said as the store being full rather than unreachable. Any
     * other error is {@code e} itself.
     */
    private static RuntimeException fullOr(RedisCommandExecutionException e) {
        String message = e.getMessage();
        if (message != null && message.contains("OOM command not allowed")) {
            return new StoreUnavailableException("Redis is full (used memory is over maxmemory, under maxmemory-policy "
                    + "noeviction), so it refuses Henge's writes; treating it as unreachable until it has room", e);
        }
        return e;
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

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
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
