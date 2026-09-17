package digital.demilich.henge.spring.fixture.duplicateinterfacename.pkgb;

import digital.demilich.henge.core.ModularService;

/** Deliberately shares its simple name with {@code pkga.WidgetService} to exercise the collision check. */
@ModularService
public interface WidgetService {

    void doOtherThing();
}
