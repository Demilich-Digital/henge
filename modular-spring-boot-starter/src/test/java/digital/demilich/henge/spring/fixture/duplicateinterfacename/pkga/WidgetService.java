package digital.demilich.henge.spring.fixture.duplicateinterfacename.pkga;

import digital.demilich.henge.core.ModularService;

/** Deliberately shares its simple name with {@code pkgb.WidgetService} to exercise the collision check. */
@ModularService
public interface WidgetService {

    void doThing();
}
