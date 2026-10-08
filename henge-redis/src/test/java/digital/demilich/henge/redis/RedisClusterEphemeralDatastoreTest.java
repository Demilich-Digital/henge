package digital.demilich.henge.redis;

import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.core.EphemeralDatastoreContract;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Against a real three-shard Redis Cluster in one container; skipped where there's no Docker. The
 * nodes announce 127.0.0.1 and are published on the same ports inside and out, because a cluster
 * client follows the addresses the cluster gives it, not the one it was started with.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisClusterEphemeralDatastoreTest extends EphemeralDatastoreContract {

    private static final int[] PORTS = {17001, 17002, 17003};

    static GenericContainer<?> cluster;
    static RedisEphemeralDatastore store;
    static RedisEphemeralDatastore otherNode;

    @BeforeAll
    static void startCluster() {
        StringBuilder script = new StringBuilder();
        StringBuilder nodes = new StringBuilder();
        for (int port : PORTS) {
            script.append("redis-server --port ").append(port).append(" --cluster-enabled yes --cluster-config-file nodes-")
                    .append(port).append(".conf --cluster-announce-ip 127.0.0.1 --save '' --appendonly no --daemonize yes\n");
            nodes.append(" 127.0.0.1:").append(port);
        }
        script.append("sleep 1\nredis-cli --cluster create").append(nodes).append(" --cluster-yes\n");
        script.append("until redis-cli -p ").append(PORTS[0]).append(" cluster info | grep -q cluster_state:ok; do sleep 0.2; done\n");
        script.append("echo CLUSTER-READY\nsleep infinity\n");

        cluster = new GenericContainer<>("redis:8")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c", script.toString()))
                .waitingFor(Wait.forLogMessage(".*CLUSTER-READY.*", 1).withStartupTimeout(Duration.ofSeconds(60)));
        List<String> bindings = new ArrayList<>();
        for (int port : PORTS) {
            bindings.add(port + ":" + port);
            bindings.add((port + 10000) + ":" + (port + 10000));
        }
        cluster.setPortBindings(bindings);
        cluster.start();

        List<String> seeds = List.of("redis://127.0.0.1:" + PORTS[0]);
        store = RedisEphemeralDatastore.connectCluster(seeds);
        otherNode = RedisEphemeralDatastore.connectCluster(seeds);
    }

    @AfterAll
    static void stopCluster() {
        if (store != null) {
            store.close();
            otherNode.close();
        }
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Override
    protected SystemEphemeralDatastore store() {
        return store;
    }

    @Override
    protected void advance(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    private static String freshKey() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    void membersOnManyShardsAreWrittenAndReadBackWithTheirShardsEpoch() {
        Set<String> servers = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            String key = freshKey();
            store.put(key, "a", new byte[] {1, 2}, Duration.ofSeconds(30));

            var snapshot = otherNode.read(key);

            assertThat(snapshot.members()).containsOnlyKeys(new MemberId(store.nodeId(), "a"));
            // An epoch is the shard's run_id, then the key's token.
            servers.add(snapshot.epoch().id().substring(0, snapshot.epoch().id().indexOf('/')));
        }
        assertThat(servers).as("the keys landed on every shard").hasSize(PORTS.length);
    }

    @Test
    void claimsAreCappedAcrossNodesOnWhicheverShardOwnsTheKey() {
        for (int i = 0; i < 20; i++) {
            String key = freshKey();
            assertThat(store.claim(key, "a", 60, 100, Duration.ofSeconds(30))).isTrue();
            assertThat(otherNode.claim(key, "a", 50, 100, Duration.ofSeconds(30))).isFalse();
            assertThat(otherNode.claim(key, "a", 40, 100, Duration.ofSeconds(30))).isTrue();
        }
    }

    @Test
    void bucketsAreSharedAcrossNodesOnEveryShard() {
        var limit = RateLimit.perSecond(1, 5);
        for (int i = 0; i < 20; i++) {
            String key = freshKey();
            assertThat(store.tryAcquire(key, 3, limit).granted()).isTrue();
            assertThat(otherNode.tryAcquire(key, 2, limit).granted()).isTrue();
            assertThat(store.tryAcquire(key, 1, limit).granted()).isFalse();
        }
    }

    @Test
    void theLimiterKeepsWorkingWhenEveryShardForgetsItsScripts() {
        var limit = RateLimit.perSecond(1, 5);
        for (int i = 0; i < 20; i++) {
            assertThat(store.tryAcquire(freshKey(), 1, limit).granted()).isTrue();
        }

        for (int port : PORTS) {
            var client = RedisClient.create("redis://127.0.0.1:" + port);
            try (var connection = client.connect()) {
                connection.sync().scriptFlush();
            } finally {
                client.shutdown();
            }
        }

        for (int i = 0; i < 20; i++) {
            assertThat(store.tryAcquire(freshKey(), 1, limit).granted()).isTrue();
        }
    }
}
