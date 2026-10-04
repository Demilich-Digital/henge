package digital.demilich.henge.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a node publishes, under {@code adv:<service>@<version>}, to say it hosts that service version:
 * the base URL its {@code /_henge} endpoint can be reached at, or {@code null} if it has none
 * configured ({@code henge.advertise.url}), meaning it hosts the service but can't be called.
 * JSON, and tolerant of fields it doesn't know, so a later version can add some mid-rollout.
 */
record ServiceAdvertisement(String url) {

    private static final ObjectMapper MAPPER = HengeTransportSupport.objectMapper();

    static String key(String service, int version) {
        return "adv:" + service + "@" + version;
    }

    byte[] encode() {
        try {
            return MAPPER.writeValueAsBytes(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Can't encode " + this, e);
        }
    }

    static ServiceAdvertisement decode(byte[] value) {
        try {
            return MAPPER.readValue(value, ServiceAdvertisement.class);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Not a service advertisement: " + new String(value, java.nio.charset.StandardCharsets.UTF_8), e);
        }
    }
}
