package digital.demilich.henge.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.mock.http.client.MockClientHttpRequest;

class HengeClientRequestObservationConventionTest {

    private static ClientRequestObservationContext requestTo(String url) {
        return new ClientRequestObservationContext(new MockClientHttpRequest(HttpMethod.POST, URI.create(url)));
    }

    private static List<String> values(Iterable<KeyValue> keyValues) {
        var values = new java.util.ArrayList<String>();
        keyValues.forEach(keyValue -> values.add(keyValue.getKey() + "=" + keyValue.getValue()));
        return values;
    }

    @Test
    void theHostOfAnAdvertisedNodeNeverBecomesATagValue() {
        var convention = new HengeClientRequestObservationConvention();

        var first = values(convention.getLowCardinalityKeyValues(requestTo("http://10.1.2.3:8080/_henge/echo-service/1/echo")));
        var second = values(convention.getLowCardinalityKeyValues(requestTo("http://10.9.8.7:8080/_henge/echo-service/1/echo")));

        assertThat(first).contains("client.name=henge").noneMatch(value -> value.contains("10.1.2.3"));
        // Two different nodes, one series.
        assertThat(second).isEqualTo(first);
    }

    @Test
    void everythingElseIsSpringsOwn() {
        var context = requestTo("http://10.1.2.3:8080/_henge/echo-service/1/echo");
        var spring = values(new DefaultClientRequestObservationConvention().getLowCardinalityKeyValues(context));
        var ours = values(new HengeClientRequestObservationConvention().getLowCardinalityKeyValues(context));

        assertThat(ours).hasSameSizeAs(spring).contains("method=POST");
        assertThat(ours.stream().filter(value -> !value.startsWith("client.name="))).containsExactlyInAnyOrderElementsOf(
                spring.stream().filter(value -> !value.startsWith("client.name=")).toList());
        assertThat(spring).anyMatch(value -> value.equals("client.name=10.1.2.3")); // what it replaces
    }

    @Test
    void theNameStaysTheStandardOneSoTracesAndDashboardsRecognizeIt() {
        assertThat(new HengeClientRequestObservationConvention().getName()).isEqualTo("http.client.requests");
    }
}
