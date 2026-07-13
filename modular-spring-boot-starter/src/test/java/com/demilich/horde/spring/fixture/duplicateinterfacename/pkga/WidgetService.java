package com.demilich.horde.spring.fixture.duplicateinterfacename.pkga;

import com.demilich.horde.core.ModularService;

/** Deliberately shares its simple name with {@code pkgb.WidgetService} to exercise the collision check. */
@ModularService
public interface WidgetService {

    void doThing();
}
