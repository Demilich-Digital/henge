package digital.demilich.henge.examples.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
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
        try (var audit = start(auditPort, "--henge.serve=audit-service");
                var greeting = start(greetingPort, "--henge.serve=greeting-service",
                        "--henge.services.audit-service.url=http://localhost:" + auditPort)) {
            assertThat(get(greetingPort, "/api/greet/Bob")).isEqualTo("Hello, Bob!");

            // Recorded by the other process, not this one.
            assertThat(get(auditPort, "/api/audit")).contains("greeted:Bob");
        }
    }

    private static JsonNode topology(int port) throws Exception {
        return new ObjectMapper().readTree(get(port, "/_henge/topology"));
    }

    private static List<String> edges(JsonNode topology) {
        List<String> edges = new ArrayList<>();
        topology.get("dependencies").forEach(edge -> edges.add(edge.get("from").asText() + " -> " + edge.get("to").asText()
                + (edge.get("remote").asBoolean() ? " (remote)" : "")));
        return edges;
    }

    private static JsonNode service(JsonNode topology, String id) {
        for (JsonNode service : topology.get("services")) {
            if (service.get("id").asText().equals(id)) {
                return service;
            }
        }
        throw new AssertionError("no " + id + " in " + topology);
    }

    @Test
    void theTopologyOfTheMonolithHasEverythingHostedAndGreetingInjectingAudit() throws Exception {
        int port = freePort();
        try (var app = start(port, "--henge.topology.enabled=true")) {
            JsonNode topology = topology(port);

            assertThat(service(topology, "greeting-service@1").get("state").asText()).isEqualTo("hosted");
            assertThat(service(topology, "audit-service@1").get("state").asText()).isEqualTo("hosted");
            assertThat(edges(topology)).contains("greeting-service@1 -> audit-service@1", "bean:demoController -> greeting-service@1");
            assertThat(get(port, "/_henge/topology/ui")).contains("Henge topology");
        }
    }

    @Test
    void theTopologyOfASplitShowsTheCallToTheOtherProcessAsRemoteAndWhereItGoes() throws Exception {
        int auditPort = freePort();
        int greetingPort = freePort();
        String auditUrl = "http://localhost:" + auditPort;
        try (var audit = start(auditPort, "--henge.serve=audit-service", "--henge.topology.enabled=true");
                var greeting = start(greetingPort, "--henge.serve=greeting-service", "--henge.topology.enabled=true",
                        "--henge.services.audit-service.url=" + auditUrl)) {
            JsonNode topology = topology(greetingPort);

            JsonNode remoteAudit = service(topology, "audit-service@1");
            assertThat(remoteAudit.get("state").asText()).isEqualTo("remote");
            assertThat(remoteAudit.get("modeSource").asText()).isEqualTo("serve");
            assertThat(remoteAudit.at("/route/source").asText()).isEqualTo("configured-url");
            assertThat(remoteAudit.at("/route/urls/0").asText()).isEqualTo(auditUrl);
            assertThat(service(topology, "greeting-service@1").get("state").asText()).isEqualTo("hosted");
            assertThat(edges(topology)).contains("greeting-service@1 -> audit-service@1 (remote)");

            // The audit process sees the same call from its side: it hosts audit, and greeting is the remote one.
            JsonNode auditSide = topology(auditPort);
            assertThat(service(auditSide, "audit-service@1").get("state").asText()).isEqualTo("hosted");
            assertThat(service(auditSide, "greeting-service@1").get("state").asText()).isEqualTo("remote");
        }
    }
}
