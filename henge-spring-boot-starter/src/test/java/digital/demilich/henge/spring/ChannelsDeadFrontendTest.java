package digital.demilich.henge.spring;

import static digital.demilich.henge.spring.ChannelTestSupport.await;
import static digital.demilich.henge.spring.ChannelTestSupport.connect;
import static digital.demilich.henge.spring.ChannelTestSupport.portOf;
import static digital.demilich.henge.spring.ChannelTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;

import digital.demilich.henge.spring.ChannelTestSupport.Client;
import digital.demilich.henge.spring.fixture.feed.FeedServiceImpl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.http.WebSocket;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A frontend that vanishes without closing its connection (its host lost, a network cut) leaves a trunk
 * that looks open. The backend pings every trunk, and drops one that has said nothing for two intervals.
 */
class ChannelsDeadFrontendTest {

    private static final String QUICK = "henge.channels.trunk.ping-interval=300ms";

    /** A websocket client frame, masked as the protocol requires of a client: binary, final, {@code payload}. */
    private static byte[] clientBinaryFrame(byte[] payload) {
        byte[] mask = {1, 2, 3, 4};
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x82);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
        }
        frame.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        return frame.toByteArray();
    }

    /** What a frontend would send to open the feed on topic {@code orders}, as channel 1. */
    private static byte[] openFrame() {
        byte[] json = """
                {"service":"feed-service","version":1,"method":"watch","args":["orders"]}""".getBytes(StandardCharsets.UTF_8);
        byte[] frame = ByteBuffer.allocate(TrunkFrame.HEADER_BYTES + json.length)
                .put(TrunkFrame.OPEN).putLong(1).put(json).array();
        return clientBinaryFrame(frame);
    }

    /** Dials the trunk by hand, opens a channel, and then never reads or writes again. Left open. */
    private static Socket silentFrontend(int port) throws Exception {
        Socket socket = new Socket("localhost", port);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /_henge/_trunk HTTP/1.1\r\nHost: localhost:" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            head.append((char) in.read());
        }
        assertThat(head.toString()).startsWith("HTTP/1.1 101");
        out.write(openFrame());
        out.flush();
        return socket;
    }

    @Test
    void aFrontendThatGoesSilentIsDroppedAndItsChannelsClose() throws Exception {
        FeedServiceImpl.EVENTS.clear();
        try (ConfigurableApplicationContext backend = start(QUICK);
                Socket frontend = silentFrontend(portOf(backend))) {
            TrunkServer trunks = backend.getBean(TrunkServer.class);
            await(() -> trunks.trunks() == 1);
            assertThat(FeedServiceImpl.EVENTS).doesNotContain("close:1001");

            // It never answers a ping, and never closes: TCP alone would leave this for hours.
            await(() -> trunks.trunks() == 0);
            await(() -> FeedServiceImpl.EVENTS.contains("close:1001"));
        }
    }

    @Test
    void aTrunkThatKeepsAnsweringIsNeverDropped() throws Exception {
        try (ConfigurableApplicationContext backend = start(QUICK);
                ConfigurableApplicationContext frontend = start(QUICK,
                        "henge.services.feed-service.mode=internal-rest",
                        "henge.services.feed-service.url=http://localhost:" + portOf(backend))) {
            var client = new Client();
            WebSocket socket = connect(frontend, "orders", client);
            assertThat(client.nextText()).isEqualTo("watching orders");

            // Quiet for ten ping intervals, with nothing but pings and pongs on the trunk.
            Thread.sleep(3000);

            socket.sendText("still here", true).join();
            assertThat(client.nextText()).isEqualTo("echo:still here");
            assertThat(backend.getBean(TrunkServer.class).trunks()).isEqualTo(1);
            assertThat(client.closed).isEmpty();
        }
    }
}
