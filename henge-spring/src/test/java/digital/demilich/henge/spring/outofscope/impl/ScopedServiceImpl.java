package digital.demilich.henge.spring.outofscope.impl;

import digital.demilich.henge.core.ServiceVersion;
import digital.demilich.henge.spring.outofscope.api.ScopedService;

@ServiceVersion(value = ScopedService.class, version = 1)
public class ScopedServiceImpl implements ScopedService {

    @Override
    public String a() {
        return "a";
    }
}
