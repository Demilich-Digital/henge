package digital.demilich.henge.redis;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
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
 * held is gone. No persistence or replication is needed or wanted. Redis Cluster isn't supported: a
 * key lives wholly on one node, which is the point, but sharding across several independent
 * instances is left to the caller.
 */
public final class RedisEphemeralDatastore implements SystemEphemeralDatastore, AutoCloseable {

    private static final String KEY_PREFIX = "henge:";

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

    private final String nodeId = UUID.randomUUID().toString();
    private final RedisClient client;
    private final StatefulRedisConnection<byte[], byte[]> connection;

    private RedisEphemeralDatastore(RedisClient client) {
        this.client = client;
        this.connection = client.connect(ByteArrayCodec.INSTANCE);
    }

    /** Connects to the Redis at {@code uri}, e.g. {@code redis://host:6379/0}; {@link #close()} disconnects. */
    public static RedisEphemeralDatastore connect(String uri) {
        RedisURI redisUri;
        try {
            redisUri = RedisURI.create(Objects.requireNonNull(uri, "uri"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("'" + uri + "' is not a Redis URI, e.g. redis://localhost:6379/0", e);
        }
        RedisClient client = RedisClient.create(redisUri);
        try {
            return new RedisEphemeralDatastore(client);
        } catch (RuntimeException e) {
            client.shutdown();
            // RedisURI's toString() masks the password.
            throw new IllegalStateException("Can't connect to Redis at " + redisUri + ": " + e.getMessage(), e);
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

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    private <T> T eval(String script, ScriptOutputType type, String key, byte[]... arguments) {
        return connection.sync().eval(bytes(script), type, new byte[][] {bytes(KEY_PREFIX + key)}, arguments);
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
