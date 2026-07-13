package com.demilich.horde.spring.fixture.orphan;

import com.demilich.horde.core.ModularService;

/** Deliberately has no implementation on the classpath, to exercise the fail-fast path. */
@ModularService(name = "orphan-service")
public interface OrphanService {

    void doThing();
}
