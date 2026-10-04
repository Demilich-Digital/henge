package digital.demilich.henge.spring;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Observes the transport's HTTP requests as Spring's standard {@code http.client.requests}, which is
 * what a trace and a dashboard already recognize, with one difference: {@code client.name} is the
 * constant {@value #CLIENT_NAME}, not the host.
 *
 * <p>Spring's convention tags the host of the request's URL, and for a service found by its
 * advertisement that is a node's own address: as nodes come and go the tag would mint a new series for
 * each. Which host a call went to is on the span (as {@code http.url}), where it can't multiply meters. The
 * tag keeps its key, since every {@code http.client.requests} meter must have the same ones.
 */
final class HengeClientRequestObservationConvention extends DefaultClientRequestObservationConvention {

    static final String CLIENT_NAME = "henge";

    @Override
    public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
        return KeyValues.of(super.getLowCardinalityKeyValues(context).stream()
                .map(keyValue -> keyValue.getKey().equals("client.name") ? KeyValue.of("client.name", CLIENT_NAME) : keyValue)
                .toList());
    }
}
