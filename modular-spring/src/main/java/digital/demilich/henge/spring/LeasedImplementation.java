package digital.demilich.henge.spring;

import java.util.List;
import java.util.Map;

/**
 * What {@link ModularLeasedServiceFactoryBean} needs to know about one embedded implementation that
 * declares leases: what it is, which leases it needs, which constructor parameter takes which
 * {@code Lease}.
 */
record LeasedImplementation(
        Class<?> serviceInterface,
        String serviceName,
        int version,
        Class<?> implClass,
        List<LeaseNeed> needs,
        Map<Integer, String> leaseParameters) {

    String localName() {
        return serviceName + "@" + version;
    }
}
