package io.modular.examples.contracts;

import io.modular.core.AddedIn;
import io.modular.core.ModularService;
import java.util.List;

@ModularService(name = "audit-service")
public interface AuditService {

    void recordEvent(String event);

    List<String> getEvents();

    /**
     * Only exists from version 2 onward. Because of that, {@code modular-processor} generates
     * {@code AuditServiceSkeleton} with a throwing override of this method — version "1"'s
     * implementation extends that skeleton and simply never overrides it, rather than being
     * forced to implement something that doesn't apply to it.
     */
    @AddedIn("2")
    List<String> getRecentEvents(int limit);
}
