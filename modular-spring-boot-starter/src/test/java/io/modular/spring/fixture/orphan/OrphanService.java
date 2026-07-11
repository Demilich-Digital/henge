package io.modular.spring.fixture.orphan;

import io.modular.core.ModularService;

/** Deliberately has no implementation on the classpath, to exercise the fail-fast path. */
@ModularService(name = "orphan-service")
public interface OrphanService {

    void doThing();
}
