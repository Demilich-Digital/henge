package digital.demilich.henge.examples.contracts;

import digital.demilich.henge.core.AddedIn;
import digital.demilich.henge.core.ImmutableList;
import digital.demilich.henge.core.ModularService;

@ModularService(name = "audit-service")
public interface AuditService {

    void recordEvent(String event);

    ImmutableList<String> getEvents();

    /**
     * Only exists from version 2 onward. Because of that, {@code modular-processor} generates
     * {@code AuditServiceSkeleton} with a throwing override of this method — version 1's
     * implementation extends that skeleton and simply never overrides it, rather than being
     * forced to implement something that doesn't apply to it.
     */
    @AddedIn(2)
    ImmutableList<String> getRecentEvents(int limit);
}
