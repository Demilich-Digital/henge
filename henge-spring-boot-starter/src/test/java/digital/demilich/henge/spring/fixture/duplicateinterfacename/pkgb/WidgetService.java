package digital.demilich.henge.spring.fixture.duplicateinterfacename.pkgb;

import digital.demilich.henge.core.HengeService;

/** Deliberately shares its simple name with {@code pkga.WidgetService} to exercise the collision check. */
@HengeService
public interface WidgetService {

    void doOtherThing();
}
