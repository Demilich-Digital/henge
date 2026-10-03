package digital.demilich.henge.examples.services;

import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ServiceVersion;
import digital.demilich.henge.examples.contracts.AuditService;
import digital.demilich.henge.examples.contracts.AuditServiceSkeleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Extends the generated {@link AuditServiceSkeleton} rather than implementing {@link AuditService}
 * directly, so it doesn't have to implement {@code getRecentEvents} — that method is
 * {@code @AddedIn(2)}, and this is version 1. Calling it here falls through to the skeleton's
 * generated throwing stub.
 */
@ServiceVersion(value = AuditService.class, version = 1)
public class AuditServiceImpl extends AuditServiceSkeleton {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public void recordEvent(String event) {
        events.add(event);
    }

    @Override
    public ImmutableList<String> getEvents() {
        return ImmutableList.copyOf(events);
    }
}
