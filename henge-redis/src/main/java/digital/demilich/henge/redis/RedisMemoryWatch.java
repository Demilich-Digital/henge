package digital.demilich.henge.redis;

import digital.demilich.henge.core.StoreUnavailableException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Treats a Redis server that is evicting keys as unreachable. Every Henge key is renewed, so a Redis that
 * evicts loses state continuously: claims vanish, are re-created on the next beat, and are taken by
 * someone else in between. A store shedding live state can't answer honestly, so while a server evicts,
 * every operation on a key it holds throws {@link StoreUnavailableException}, and the cluster sits still
 * as it does through any outage. Its own keys then lapse, which frees memory, so an eviction Henge caused
 * ends by itself.
 *
 * <p>Each server (each primary, in a Redis Cluster) is sampled on a timer, not on each operation, since
 * {@code INFO} costs a third of a small read. A server is evicting while {@code current_eviction_exceeded_time}
 * is above zero, or while {@code evicted_keys} rose since the last sample. That counter covers every key on
 * the server, so a shared Redis that evicts another application's keys counts too: it is evicting
 * whatever it likes. A server that can evict at all ({@code maxmemory} set, with any policy but
 * {@code noeviction}) is warned about the first time it is sampled.
 *
 * <p>A sample that fails changes nothing: a server that can't be reached fails its operations anyway.
 */
final class RedisMemoryWatch implements AutoCloseable {

    private static final Logger log = System.getLogger(RedisMemoryWatch.class.getName());

    private static final Pattern FIELD = Pattern.compile("^([a-z_]+):(.*?)\\r?$", Pattern.MULTILINE);

    /** What one server said, and when it was last evicting. */
    private static final class Server {
        long evictedKeys = -1;
        volatile boolean evicting;
        boolean warned;
    }

    private final Supplier<Map<String, Function<String, String>>> servers;
    private final Function<byte[], String> serverOf;
    private final Map<String, Server> state = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sampler;

    /**
     * @param servers each server to sample, by name, as a function from an {@code INFO} section to its text;
     *     asked again on every sample, so a cluster's primaries can change
     * @param serverOf the name of the server that holds a key, or {@code null} if it isn't known
     */
    RedisMemoryWatch(Supplier<Map<String, Function<String, String>>> servers, Function<byte[], String> serverOf,
            Duration interval) {
        this.servers = servers;
        this.serverOf = serverOf;
        sample();
        sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "henge-redis-memory");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleWithFixedDelay(this::sample, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** @throws StoreUnavailableException if the server holding {@code key} is evicting */
    void check(byte[] key) {
        String name = serverOf.apply(key);
        Server server = name == null ? null : state.get(name);
        if (server != null && server.evicting) {
            throw new StoreUnavailableException("Redis at " + name + " is evicting keys under maxmemory, so it can't hold "
                    + "Henge's state; treating it as unreachable until it stops", null);
        }
    }

    /** Samples every server once. Never throws, so the schedule survives anything one sample does. */
    void sample() {
        try {
            // Each on its own thread: a server that is blackholed holds its sample for the connect timeout, and
            // must not hold back the others' (or, at connect, the startup of all of them in turn).
            List<Thread> sampling = servers.get().entrySet().stream()
                    .map(server -> Thread.ofVirtual().start(() -> sample(server.getKey(), server.getValue()))).toList();
            for (Thread thread : sampling) {
                thread.join();
            }
        } catch (RuntimeException e) {
            log.log(Level.DEBUG, "Couldn't list the Redis servers to sample", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sample(String name, Function<String, String> info) {
        Map<String, String> memory;
        Map<String, String> stats;
        try {
            memory = fields(info.apply("memory"));
            stats = fields(info.apply("stats"));
        } catch (RuntimeException e) {
            log.log(Level.DEBUG, "Couldn't sample the memory of Redis at " + name, e);
            return;
        }
        Server server = state.computeIfAbsent(name, n -> new Server());
        long maxmemory = number(memory, "maxmemory").orElse(0L);
        String policy = memory.getOrDefault("maxmemory_policy", "noeviction");
        if (!server.warned) {
            server.warned = true;
            if (maxmemory > 0 && !policy.equals("noeviction")) {
                log.log(Level.WARNING, "Redis at " + name + " can evict keys (maxmemory " + maxmemory + ", maxmemory-policy "
                        + policy + "). Henge treats it as unreachable while it evicts; set maxmemory-policy noeviction, "
                        + "so that a full Redis fails writes instead of shedding claims that are in use");
            }
        }
        long evictedKeys = number(stats, "evicted_keys").orElse(0L);
        boolean evicting = number(stats, "current_eviction_exceeded_time").orElse(0L) > 0
                || (server.evictedKeys >= 0 && evictedKeys > server.evictedKeys);
        server.evictedKeys = evictedKeys;
        if (evicting != server.evicting) {
            server.evicting = evicting;
            if (evicting) {
                log.log(Level.WARNING, "Redis at " + name + " is evicting keys (used_memory " + memory.get("used_memory")
                        + " of maxmemory " + maxmemory + ", maxmemory-policy " + policy + "); treating it as unreachable "
                        + "until it stops");
            } else {
                log.log(Level.INFO, "Redis at " + name + " has stopped evicting keys");
            }
        }
    }

    private static Map<String, String> fields(String info) {
        Map<String, String> fields = new ConcurrentHashMap<>();
        Matcher m = FIELD.matcher(info);
        while (m.find()) {
            fields.put(m.group(1), m.group(2));
        }
        return fields;
    }

    private static Optional<Long> number(Map<String, String> fields, String name) {
        try {
            return Optional.ofNullable(fields.get(name)).map(Long::parseLong);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        sampler.shutdownNow();
    }
}
