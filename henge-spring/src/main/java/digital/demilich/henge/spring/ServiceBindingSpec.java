package digital.demilich.henge.spring;

/**
 * What {@link HengeServiceBindingFactoryBean} builds one {@link ServiceBinding} from: the service
 * version, and how it is fulfilled in this process.
 *
 * @param implClass the implementation to construct here; {@code null} for a service reached over the transport
 * @param leased the leases that implementation needs, if it declares any; {@code null} otherwise
 */
record ServiceBindingSpec(
        Class<?> serviceInterface, String serviceName, int version, Class<?> implClass, LeasedImplementation leased) {

    /** Reached over the transport. */
    static ServiceBindingSpec remote(Class<?> serviceInterface, String serviceName, int version) {
        return new ServiceBindingSpec(serviceInterface, serviceName, version, null, null);
    }

    /** Implemented here, with nothing to lease. */
    static ServiceBindingSpec embedded(Class<?> serviceInterface, String serviceName, int version, Class<?> implClass) {
        return new ServiceBindingSpec(serviceInterface, serviceName, version, implClass, null);
    }

    /** Implemented here if its leases are granted, else reached over the transport. */
    static ServiceBindingSpec leased(LeasedImplementation leased) {
        return new ServiceBindingSpec(leased.serviceInterface(), leased.serviceName(), leased.version(), leased.implClass(), leased);
    }

    String localName() {
        return serviceName + "@" + version;
    }
}
