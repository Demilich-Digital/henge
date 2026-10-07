package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The hand-written metadata IDEs read for henge.* completion: present, valid JSON, no duplicates. */
class ConfigurationMetadataTest {

    @Test
    void metadataShipsAndDescribesEachPropertyOnce() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(in).isNotNull();
            JsonNode metadata = new ObjectMapper().readTree(in);
            List<String> names = new ArrayList<>();
            metadata.get("properties").forEach(property -> {
                assertThat(property.get("description").asText()).isNotBlank();
                names.add(property.get("name").asText());
            });
            assertThat(names).doesNotHaveDuplicates().contains(
                    "henge.serve", "henge.recent-versions", "henge.remote-url-template", "henge.advertise.url", "henge.transport.retry.max-attempts", "henge.transport.retry.backoff", "henge.transport.retry.on", "henge.store.type", "henge.store.redis.uri", "henge.store.backoff.initial", "henge.store.backoff.max", "henge.server.enabled", "henge.server.path-prefix", "henge.topology.enabled",
                    "henge.transport.secret", "henge.transport.connect-timeout", "henge.transport.read-timeout", "henge.transport.max-body-bytes",
                    "henge.channels.queue-size", "henge.channels.max-frame-bytes", "henge.channels.trunk.ping-interval", "henge.channels.trunk.idle-timeout");
        }
    }
}
