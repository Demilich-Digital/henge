package digital.demilich.henge.spring;

import java.util.List;
import java.util.Map;

/**
 * What {@link ModularLeasedServiceFactoryBean} needs to know about one embedded implementation that
 * declares leases: what it is, which leases it needs, which constructor parameter takes which
 * {@code Lease}, and whether it can be reached remotely when a lease is refused here.
 */
record LeasedImplementation(
        Class<?> serviceInterface,
        String serviceName,
        int version,
        Class<?> implClass,
        List<LeaseNeed> needs,
        Map<Integer, String> leaseParameters,
        boolean remoteUrlConfigured) {

    String localName() {
        return serviceName + "@" + version;
    }
}
