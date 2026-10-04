package digital.demilich.henge.examples.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

/** The README's quickstart, run for real: the same application as one process, then as two. */
class ExampleAppTest {

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ConfigurableApplicationContext start(int port, String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "--server.port=" + port;
        System.arraycopy(args, 0, all, 1, args.length);
        return SpringApplication.run(ExampleApp.class, all);
    }

    private static String get(int port, String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(String.class);
    }

    @Test
    void asAMonolithBothServicesAreEmbedded() throws Exception {
        int port = freePort();
        try (var app = start(port)) {
            assertThat(get(port, "/api/greet/Alice")).isEqualTo("Hello, Alice!");
            assertThat(get(port, "/api/audit")).contains("greeted:Alice");
        }
    }

    @Test
    void asTwoProcessesTheGreetingCallsTheAuditServiceOverHttp() throws Exception {
        int auditPort = freePort();
        int greetingPort = freePort();
        try (var audit = start(auditPort, "--modular.serve=audit-service");
                var greeting = start(greetingPort, "--modular.serve=greeting-service",
                        "--modular.services.audit-service.url=http://localhost:" + auditPort)) {
            assertThat(get(greetingPort, "/api/greet/Bob")).isEqualTo("Hello, Bob!");

            // Recorded by the other process, not this one.
            assertThat(get(auditPort, "/api/audit")).contains("greeted:Bob");
        }
    }
}
