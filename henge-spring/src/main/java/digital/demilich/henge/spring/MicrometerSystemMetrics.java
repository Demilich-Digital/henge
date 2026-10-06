package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The system's own meters, on a {@link MeterRegistry}:
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><td>{@code henge.lease.claims}</td><td>counter of asks of the cluster: {@code lease}, {@code outcome} ({@code granted} or {@code refused})</td></tr>
 *   <tr><td>{@code henge.lease.renewals}</td><td>counter: {@code lease}, {@code outcome} ({@code renewed}, {@code over-capacity} or {@code error})</td></tr>
 *   <tr><td>{@code henge.lease.held}</td><td>gauge: {@code lease}; the amount this node holds, {@code 0} after it hands it back</td></tr>
 *   <tr><td>{@code henge.advertisement.renewals}</td><td>counter: {@code service}, {@code version}, {@code outcome} ({@code success} or {@code error})</td></tr>
 *   <tr><td>{@code henge.service.advertisers}</td><td>gauge: {@code service}, {@code version}; the nodes advertising it as last seen by a caller looking for it</td></tr>
 *   <tr><td>{@code henge.transport.retries}</td><td>counter: {@code service}, {@code version}, {@code reason} ({@code connect} or {@code not-served})</td></tr>
 *   <tr><td>{@code henge.transport.giveups}</td><td>counter: {@code service}, {@code version}, {@code reason}</td></tr>
 *   <tr><td>{@code henge.transport.endpoint.failures}</td><td>counter: {@code service}, {@code version}</td></tr>
 *   <tr><td>{@code henge.rate-limit.acquisitions}</td><td>counter: {@code limit}, {@code outcome} ({@code granted} or {@code refused})</td></tr>
 *   <tr><td>{@value MeteredDatastore#NAME}</td><td>timer: {@code purpose}, {@code operation}, {@code outcome}</td></tr>
 * </table>
 *
 * Boot registers it when a {@link MeterRegistry} bean exists. Without Boot, declare it as a bean.
 */
public class MicrometerSystemMetrics implements SystemMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> held = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> advertisers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> subscribers = new ConcurrentHashMap<>();

    public MicrometerSystemMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public SystemEphemeralDatastore measured(SystemEphemeralDatastore datastore, String purpose) {
        return new MeteredDatastore(datastore, registry, purpose);
    }

    @Override
    public void leaseClaimed(String lease, boolean granted) {
        registry.counter("henge.lease.claims", "lease", lease, "outcome", granted ? "granted" : "refused").increment();
    }

    @Override
    public void leaseRenewed(String lease, Renewal outcome) {
        registry.counter("henge.lease.renewals", "lease", lease, "outcome", outcome.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .increment();
    }

    @Override
    public void leaseHeld(String lease, int amount) {
        held.computeIfAbsent(lease, name -> {
            AtomicInteger amountHeld = new AtomicInteger();
            Gauge.builder("henge.lease.held", amountHeld, AtomicInteger::get).tag("lease", name).register(registry);
            return amountHeld;
        }).set(amount);
    }

    @Override
    public void advertisementRenewed(String service, int version, boolean succeeded) {
        registry.counter("henge.advertisement.renewals", "service", service, "version", String.valueOf(version),
                "outcome", succeeded ? "success" : "error").increment();
    }

    @Override
    public void advertisersSeen(String service, int version, int nodes) {
        advertisers.computeIfAbsent(service + "@" + version, name -> {
            AtomicInteger seen = new AtomicInteger();
            Gauge.builder("henge.service.advertisers", seen, AtomicInteger::get)
                    .tag("service", service).tag("version", String.valueOf(version)).register(registry);
            return seen;
        }).set(nodes);
    }

    @Override
    public void callRetried(String service, int version, String reason) {
        registry.counter("henge.transport.retries", "service", service, "version", String.valueOf(version), "reason", reason).increment();
    }

    @Override
    public void callGaveUp(String service, int version, String reason) {
        registry.counter("henge.transport.giveups", "service", service, "version", String.valueOf(version), "reason", reason).increment();
    }

    @Override
    public void endpointFailed(String service, int version) {
        registry.counter("henge.transport.endpoint.failures", "service", service, "version", String.valueOf(version)).increment();
    }

    @Override
    public void rateLimitAcquired(String limit, boolean granted) {
        registry.counter("henge.rate-limit.acquisitions", "limit", limit, "outcome", granted ? "granted" : "refused").increment();
    }

    @Override
    public void rateLimitSubscribers(String limit, int nodes) {
        subscribers.computeIfAbsent(limit, name -> {
            AtomicInteger seen = new AtomicInteger();
            Gauge.builder("henge.rate-limit.subscribers", seen, AtomicInteger::get).tag("limit", limit).register(registry);
            return seen;
        }).set(nodes);
    }

    @Override
    public void rateLimitDegraded(String limit) {
        registry.counter("henge.rate-limit.degraded", "limit", limit).increment();
    }
}
