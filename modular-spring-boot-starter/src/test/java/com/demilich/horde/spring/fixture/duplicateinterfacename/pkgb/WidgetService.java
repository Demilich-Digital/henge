package com.demilich.horde.spring.fixture.duplicateinterfacename.pkgb;

import com.demilich.horde.core.ModularService;

/** Deliberately shares its simple name with {@code pkga.WidgetService} to exercise the collision check. */
@ModularService
public interface WidgetService {

    void doOtherThing();
}
