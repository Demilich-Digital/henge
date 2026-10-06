package digital.demilich.henge.spring;

import static digital.demilich.henge.spring.ChannelTestSupport.await;
import static digital.demilich.henge.spring.ChannelTestSupport.connect;
import static digital.demilich.henge.spring.ChannelTestSupport.freePort;
import static digital.demilich.henge.spring.ChannelTestSupport.handshakeStatus;
import static digital.demilich.henge.spring.ChannelTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.ChannelTestSupport.Client;
import digital.demilich.henge.spring.ChannelTestSupport.SwitchableStore;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What happens to open channels, and to ones being opened, as backends retire or die and as the ephemeral
 * store goes away: the frontend finds its backends through the store's advertisements, as it does in a
 * cluster.
 */
class ChannelsLifecycleTest {

    private final SwitchableStore store = new SwitchableStore();
    private final List<ConfigurableApplicationContext> running = new ArrayList<>();

    @AfterEach
    void stopAll() {
        for (ConfigurableApplicationContext context : running) {
            context.close();
        }
    }

    private ConfigurableApplicationContext backend() throws Exception {
        int port = freePort();
        return track(start(store.node("backend" + running.size()), "server.port=" + port, "henge.advertise.url=http://localhost:" + port));
    }

    private ConfigurableApplicationContext frontend() {
        return track(start(store, "henge.services.feed-service.mode=internal-rest"));
    }

    private ConfigurableApplicationContext track(ConfigurableApplicationContext context) {
        running.add(context);
        return context;
    }

    private static void retire(ConfigurableApplicationContext backend) throws InterruptedException {
        backend.getBean(HengeServiceRegistry.class).retire("feed-service", 1, Duration.ZERO, Duration.ofSeconds(5));
    }

    @Test
    void aBackendThatDiesClosesItsChannelsAsLost() throws Exception {
        var backend = backend();
        var frontend = frontend();
        var client = new Client();
        connect(frontend, "orders", client);
        assertThat(client.nextText()).isEqualTo("watching orders");

        backend.close();

        assertThat(client.nextClose()).isEqualTo(1011);
    }

    @Test
    void aRetiringServiceClosesItsChannelsAsARestartAndTheDrainCompletes() throws Exception {
        var backend = backend();
        var frontend = frontend();
        var first = new Client();
        var second = new Client();
        connect(frontend, "a", first);
        connect(frontend, "b", second);
        assertThat(first.nextText()).isEqualTo("watching a");
        assertThat(second.nextText()).isEqualTo("watching b");

        long start = System.nanoTime();
        retire(backend);

        // Open channels don't hold the drain, which would otherwise wait the whole 5 seconds.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(first.nextClose()).isEqualTo(1012);
        assertThat(second.nextClose()).isEqualTo(1012);
    }

    @Test
    void aBackendThatNoLongerHostsTheServiceRefusesTheOpenAs404() throws Exception {
        var backend = backend();
        var frontend = frontend();
        var warm = new Client();
        connect(frontend, "a", warm);
        assertThat(warm.nextText()).isEqualTo("watching a");
        retire(backend);

        // The frontend still has the backend in its routing table, and nobody else to try.
        var client = new Client();
        connect(frontend, "b", client);

        assertThat(client.nextClose()).isEqualTo(4404);
    }

    @Test
    void anOpenThatIsRefusedIsTriedOnceOnAnotherBackendAndLosesNothingSentMeanwhile() throws Exception {
        var retiring = backend();
        var staying = backend();
        var frontend = frontend();
        var warm = new Client();
        connect(frontend, "warm", warm);
        assertThat(warm.nextText()).isEqualTo("watching warm");
        retire(retiring);

        // Whichever backend the rotation tries first, every channel ends up on the one that hosts the service.
        for (int i = 0; i < 4; i++) {
            var client = new Client();
            WebSocket socket = connect(frontend, "topic" + i, client);
            socket.sendText("early" + i, true).join();

            assertThat(client.nextText()).isEqualTo("watching topic" + i);
            assertThat(client.nextText()).isEqualTo("echo:early" + i);
        }
        assertThat(staying.getBean(TrunkServer.class).trunks()).isEqualTo(1);
    }

    @Test
    void theStoreGoingAwayOrBeingWipedChangesNothingForOpenChannelsOrNewOnesOnAKnownBackend() throws Exception {
        backend();
        var frontend = frontend();
        var client = new Client();
        WebSocket socket = connect(frontend, "orders", client);
        assertThat(client.nextText()).isEqualTo("watching orders");

        store.down = true;
        store.wipe();

        socket.sendText("during the outage", true).join();
        assertThat(client.nextText()).isEqualTo("echo:during the outage");
        var another = new Client();
        connect(frontend, "more", another);
        assertThat(another.nextText()).isEqualTo("watching more");
        assertThat(client.closed).isEmpty();
    }

    @Test
    void withNoBackendKnownAndTheStoreAwayAnOpenFailsAsTryAgainLater() throws Exception {
        backend();
        var frontend = frontend();
        store.down = true;

        var client = new Client();
        connect(frontend, "orders", client);

        assertThat(client.nextClose()).isEqualTo(1013);
    }

    @Test
    void aBackendThatHasNotReachedTheStoreRefusesTheTrunkHandshake() throws Exception {
        store.down = true;
        int port = freePort();
        // Startup waits for the store, so it is started aside and left waiting.
        var starting = new Thread(() -> track(start(store, "server.port=" + port, "henge.advertise.url=http://localhost:" + port)));
        starting.setDaemon(true);
        starting.start();
        await(() -> serving(port));

        assertThat(handshakeStatus("ws://localhost:" + port + "/_henge/_trunk")).isEqualTo(503);

        store.down = false; // it is ready now, and startup finishes
        starting.join(30_000);
        assertThat(starting.isAlive()).isFalse();
        assertThat(handshakeStatus("ws://localhost:" + port + "/_henge/_trunk")).isEqualTo(101);
    }

    private static boolean serving(int port) {
        try (var socket = new java.net.Socket("localhost", port)) {
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
