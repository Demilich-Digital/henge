package digital.demilich.henge.examples.shop.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
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
