package digital.demilich.henge.spring.everynodefixture.other;

import digital.demilich.henge.core.ServiceVersion;

@ServiceVersion(value = AuditService.class, version = 1)
public class AuditServiceImpl implements AuditService {

    @Override
    public String audit() {
        return "";
    }
}
