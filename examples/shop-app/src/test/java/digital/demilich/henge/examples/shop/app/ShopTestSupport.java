package digital.demilich.henge.examples.shop.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Starts the shop as a real process would, and calls its public API over HTTP. */
final class ShopTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ShopTestSupport() {
    }

    record Response(int status, JsonNode body) {
    }

    /** A customer watching an order: what the websocket has said, and the status it was closed with. */
    static final class Feed implements WebSocket.Listener {

        private final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        private final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            statuses.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.add(statusCode);
            return null;
        }

        /** The next status the feed sends, or null if it sends none for 10 seconds. */
        String next() throws InterruptedException {
            return statuses.poll(10, TimeUnit.SECONDS);
        }

        /** The status the websocket was closed with, or null if it isn't closed within 10 seconds. */
        Integer closedWith() throws InterruptedException {
            return closed.poll(10, TimeUnit.SECONDS);
        }
    }

    /** A shop on a free port, configured by {@code args} as on the command line. */
    record Shop(ConfigurableApplicationContext context, int port) implements AutoCloseable {

        static Shop start(String... args) {
            int port = freePort();
            String[] all = new String[args.length + 1];
            all[0] = "--server.port=" + port;
            System.arraycopy(args, 0, all, 1, args.length);
            return new Shop(SpringApplication.run(ShopApp.class, all), port);
        }

        String url() {
            return "http://localhost:" + port;
        }

        Response get(String path) {
            return call(HttpMethod.GET, path, null);
        }

        Response post(String path, String json) {
            return call(HttpMethod.POST, path, json);
        }

        Response placeOrder(String customer, String sku, int quantity) {
            return post("/api/orders", """
                    {"customer": "%s", "items": [{"sku": "%s", "quantity": %d}]}""".formatted(customer, sku, quantity));
        }

        /** Opens {@code /ws/orders/{id}}, as a customer's browser would. */
        Feed watch(String orderId) {
            Feed feed = new Feed();
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/orders/" + orderId), feed)
                    .orTimeout(10, TimeUnit.SECONDS).join();
            return feed;
        }

        int available(String sku) {
            return get("/api/stock?sku=" + sku).body().get(sku).asInt();
        }

        private Response call(HttpMethod method, String path, String json) {
            RestClient.RequestBodySpec request = RestClient.create(url()).method(method).uri(path);
            if (json != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(json);
            }
            return request.exchange((req, res) -> {
                String body = res.bodyTo(String.class);
                return new Response(res.getStatusCode().value(), body == null || body.isEmpty() ? null : JSON.readTree(body));
            });
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
