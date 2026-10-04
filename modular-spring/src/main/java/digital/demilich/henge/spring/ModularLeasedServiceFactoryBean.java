package digital.demilich.henge.spring;

import digital.demilich.henge.core.Lease;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Stands in for an embedded implementation that declares {@code @RequiresLease}: it first acquires
 * the leases, and only if they are all granted does it construct the implementation, which then
 * gets its {@link Lease}s as constructor arguments. Otherwise it produces the same remote proxy a
 * service configured {@code internal-rest} gets, so the implementation (and whatever pool it would
 * have opened) is never constructed on this node. Callers inject the interface exactly as before.
 *
 * <p>The implementation is registered as a second, hidden bean ({@code <name>.impl}, not an
 * autowire candidate) at the moment its leases are granted, so it gets normal dependency injection
 * and lifecycle. It is created eagerly ({@link #isEagerInit()}), so a refusal or a configuration
 * mistake fails startup rather than the first call.
 *
 * <p>A refusal that nothing else in the cluster could explain, because this process holds the whole
 * of what is claimed, means the process's own services over-allocate the lease. That is a
 * configuration error, reported with who holds what.
 */
class ModularLeasedServiceFactoryBean implements SmartFactoryBean<Object>, ApplicationContextAware, BeanNameAware, DisposableBean {

    private static final Log log = LogFactory.getLog(ModularLeasedServiceFactoryBean.class);

    private final LeasedImplementation leased;

    private ApplicationContext applicationContext;
    private String beanName;
    private ModularLeaseKeeper keeper;
    private Object product;

    ModularLeasedServiceFactoryBean(LeasedImplementation leased) {
        this.leased = leased;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    @Override
    public synchronized Object getObject() {
        if (product == null) {
            keeper = applicationContext.getBean(ModularLeaseKeeper.class);
            LeaseRefusal refusal = keeper.acquireAll(leased.localName(), leased.needs());
            product = refusal == null ? constructImplementation() : remoteInstead(refusal);
        }
        return product;
    }

    private Object constructImplementation() {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        String implBeanName = beanName + ".impl";

        RootBeanDefinition definition = new RootBeanDefinition(leased.implClass());
        definition.setAutowireCandidate(false);
        definition.setDependsOn(ModularServiceRegistrar.LEASE_KEEPER_BEAN_NAME);
        leased.leaseParameters().forEach((index, leaseName) -> {
            LeaseNeed need = leased.needs().stream().filter(n -> n.name().equals(leaseName)).findFirst().orElseThrow();
            definition.getConstructorArgumentValues().addIndexedArgumentValue(index, new Lease(need.name(), need.amount()));
        });
        registry.registerBeanDefinition(implBeanName, definition);
        try {
            Object implementation = applicationContext.getBean(implBeanName);
            // The implementation is destroyed first, then this bean, which hands the leases back.
            ((ConfigurableApplicationContext) applicationContext).getBeanFactory().registerDependentBean(beanName, implBeanName);
            return implementation;
        } catch (RuntimeException e) {
            keeper.release(leased.localName());
            throw e;
        }
    }

    private Object remoteInstead(LeaseRefusal refusal) {
        LeaseNeed need = refusal.need();
        if (refusal.heldOnlyByThisProcess()) {
            throw new IllegalStateException("Lease '" + need.name() + "' (capacity " + need.capacity() + ") can't be granted to "
                    + leased.localName() + ", which needs " + need.amount() + ": this process's own services already hold "
                    + describeHolders(refusal) + ", and nothing else holds it, so no other process could host "
                    + leased.localName() + " either. The services in this process claim more of '" + need.name()
                    + "' than its capacity. Raise modular.leases." + need.name() + ".capacity, lower the amounts under "
                    + "modular.services.<service>.leases." + need.name() + ", or host fewer of these services here "
                    + "(--modular.serve).");
        }
        if (!leased.remoteUrlConfigured()) {
            throw new IllegalStateException("Lease '" + need.name() + "' (capacity " + need.capacity() + ") is held by other "
                    + "processes, so " + leased.localName() + " must be reached remotely from here, but no url is "
                    + "configured for it. Set modular.services." + leased.serviceName() + ".url (modular.remote-url-template "
                    + "can't be combined with leases).");
        }
        log.info("Lease '" + need.name() + "' is full; " + leased.localName() + " is reached remotely from this process");
        ModularServiceProxyFactoryBean proxy =
                new ModularServiceProxyFactoryBean(leased.serviceInterface(), leased.serviceName(), leased.version());
        proxy.setApplicationContext(applicationContext);
        return proxy.getObject();
    }

    private static String describeHolders(LeaseRefusal refusal) {
        int total = refusal.heldByThisProcess().values().stream().mapToInt(Integer::intValue).sum();
        String holders = refusal.heldByThisProcess().entrySet().stream()
                .map(holder -> holder.getKey() + " " + holder.getValue())
                .collect(Collectors.joining(", "));
        return total + " of " + refusal.need().capacity() + " (" + holders + ")";
    }

    @Override
    public Class<?> getObjectType() {
        return leased.serviceInterface();
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public boolean isEagerInit() {
        return true;
    }

    @Override
    public void destroy() {
        if (keeper != null) {
            keeper.release(leased.localName());
        }
    }
}
