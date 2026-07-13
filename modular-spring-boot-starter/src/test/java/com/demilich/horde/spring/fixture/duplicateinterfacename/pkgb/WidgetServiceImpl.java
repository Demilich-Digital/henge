package com.demilich.horde.spring.fixture.duplicateinterfacename.pkgb;

import com.demilich.horde.core.ServiceVersion;

@ServiceVersion(value = WidgetService.class, version = "1")
public class WidgetServiceImpl implements WidgetService {

    @Override
    public void doOtherThing() {
    }
}
