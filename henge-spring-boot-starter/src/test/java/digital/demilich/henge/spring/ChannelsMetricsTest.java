package digital.demilich.henge.spring;

import static digital.demilich.henge.spring.ChannelTestSupport.await;
import static digital.demilich.henge.spring.ChannelTestSupport.connect;
import static digital.demilich.henge.spring.ChannelTestSupport.start;
import static digital.demilich.henge.spring.ChannelTestSupport.portOf;
import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.ChannelTestSupport.Client;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.WebSocket;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** What a frontend and its backend report about the channels and the one trunk between them. */
class ChannelsMetricsTest {

    private static double gauge(MeterRegistry meters, String name, String side) {
        var found = meters.find(name).tag("side", side).gauge();
        return found == null ? 0 : found.value();
    }

    private static double closed(MeterRegistry meters, String side, String status) {
        var found = meters.find("henge.channels.closed").tag("side", side).tag("status", status).counter();
        return found == null ? 0 : found.count();
    }

    @Test
    void bothEndsCountTheirChannelsAndTheTrunk() throws Exception {
        MeterRegistry backendMeters = new SimpleMeterRegistry();
        MeterRegistry frontendMeters = new SimpleMeterRegistry();
        try (ConfigurableApplicationContext backend = start(null, backendMeters);
                ConfigurableApplicationContext frontend = start(null, frontendMeters,
                        "henge.services.feed-service.mode=internal-rest",
                        "henge.services.feed-service.url=http://localhost:" + portOf(backend))) {
            var clients = new Client[3];
            var sockets = new WebSocket[3];
            for (int i = 0; i < 3; i++) {
                clients[i] = new Client();
                sockets[i] = connect(frontend, "topic" + i, clients[i]);
                assertThat(clients[i].nextText()).isEqualTo("watching topic" + i);
            }

            assertThat(gauge(frontendMeters, "henge.channels.open", "frontend")).isEqualTo(3);
            assertThat(gauge(backendMeters, "henge.channels.open", "backend")).isEqualTo(3);
            assertThat(gauge(frontendMeters, "henge.trunks.open", "frontend")).isEqualTo(1);
            assertThat(gauge(backendMeters, "henge.trunks.open", "backend")).isEqualTo(1);

            sockets[0].sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
            await(() -> gauge(backendMeters, "henge.channels.open", "backend") == 2);

            assertThat(gauge(frontendMeters, "henge.channels.open", "frontend")).isEqualTo(2);
            assertThat(closed(backendMeters, "backend", "1000")).isEqualTo(1);
            assertThat(closed(frontendMeters, "frontend", "1000")).isEqualTo(1);

            var refused = new Client();
            connect(frontend, "missing", refused);
            assertThat(refused.nextClose()).isEqualTo(4404);
            await(() -> closed(backendMeters, "backend", "4404") == 1);
            assertThat(closed(frontendMeters, "frontend", "4404")).isEqualTo(1);
            // A refused open is counted opened and closed, so it leaves nothing behind.
            assertThat(gauge(backendMeters, "henge.channels.open", "backend")).isEqualTo(2);
        }
    }
}
