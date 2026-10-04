package digital.demilich.henge.spring.fixture.duplicateinterfacename.pkga;

import digital.demilich.henge.core.HengeService;

/** Deliberately shares its simple name with {@code pkgb.WidgetService} to exercise the collision check. */
@HengeService
public interface WidgetService {

    void doThing();
}
