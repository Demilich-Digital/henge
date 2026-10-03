package digital.demilich.henge.spring.fixture.duplicateinterfacename.pkga;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = WidgetService.class, version = 1)
public class WidgetServiceImpl implements WidgetService {

    @Override
    public void doThing() {
    }
}
