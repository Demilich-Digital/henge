package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The hand-written metadata IDEs read for modular.* completion: present, valid JSON, no duplicates. */
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
                    "modular.serve", "modular.recent-versions", "modular.remote-url-template", "modular.advertise.url", "modular.transport.retry.max-attempts", "modular.transport.retry.backoff", "modular.transport.retry.on", "modular.store.type", "modular.store.redis.uri", "modular.server.enabled", "modular.server.path-prefix", "modular.topology.enabled",
                    "modular.transport.secret", "modular.transport.connect-timeout", "modular.transport.read-timeout");
        }
    }
}
