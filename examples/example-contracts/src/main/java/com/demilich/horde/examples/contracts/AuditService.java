package com.demilich.horde.examples.contracts;

import com.demilich.horde.core.AddedIn;
import com.demilich.horde.core.ImmutableList;
import com.demilich.horde.core.ModularService;

@ModularService(name = "audit-service")
public interface AuditService {

    void recordEvent(String event);

    ImmutableList<String> getEvents();

    /**
     * Only exists from version 2 onward. Because of that, {@code modular-processor} generates
     * {@code AuditServiceSkeleton} with a throwing override of this method — version "1"'s
     * implementation extends that skeleton and simply never overrides it, rather than being
     * forced to implement something that doesn't apply to it.
     */
    @AddedIn("2")
    ImmutableList<String> getRecentEvents(int limit);
}
