package digital.demilich.henge.spring;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.ServiceTransport;
import java.lang.reflect.Proxy;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Registered by {@link ModularServiceRegistrar} under every service version's bean name, and produces the
 * proxy every injection point of it gets: one JDK proxy over one {@link ServiceBinding}, whether the
 * implementation is here or not. What differs is the binding's target:
 *
 * <ul>
 *   <li>an implementation here, constructed as the hidden bean {@code <name>.impl} (not an autowire
 *       candidate, so callers hold the interface and never the implementation), with normal dependency
 *       injection and lifecycle;
 *   <li>one that declares leases: they are acquired first, and only if they are all granted is the
 *       implementation constructed, which then gets its {@link Lease}s and resources as constructor
 *       arguments. Refused, the target is the transport instead, so the implementation (and whatever pool
 *       it would have opened) is never constructed on this node. A refusal means other processes hold the
 *       lease, so the service is reached at its configured url, or else wherever it is advertised;
 *   <li>the transport, for a service configured {@code internal-rest}.
 * </ul>
 *
 * <p>One with an implementation here is created eagerly ({@link #isEagerInit()}), so a refusal or a
 * configuration mistake fails startup rather than the first call. One that is only ever remote is created
 * when something first asks for it, as it always was, so a process that never calls it needs no
 * {@link ServiceTransport}. (Services that over-allocate a lease are rejected before anything is
 * constructed, see {@link ModularServiceRegistrar}.)
 */
class ModularServiceBindingFactoryBean implements SmartFactoryBean<Object>, ApplicationContextAware, BeanNameAware, DisposableBean {

    private static final Log log = LogFactory.getLog(ModularServiceBindingFactoryBean.class);

    private final ServiceBindingSpec spec;

    private ApplicationContext applicationContext;
    private String beanName;
    private ModularLeaseKeeper keeper;
    private ServiceBinding binding;
    private Object proxy;

    ModularServiceBindingFactoryBean(ServiceBindingSpec spec) {
        this.spec = spec;
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
        if (proxy == null) {
            binding = buildBinding();
            proxy = Proxy.newProxyInstance(
                    spec.serviceInterface().getClassLoader(),
                    new Class<?>[] {spec.serviceInterface()},
                    new ModularServiceInvocationHandler(binding));
        }
        return proxy;
    }

    /** The binding behind {@link #getObject()}; creating it first if it hasn't been, which is what decides a leased service. */
    synchronized ServiceBinding binding() {
        getObject();
        return binding;
    }

    private ServiceBinding buildBinding() {
        if (spec.implClass() == null) {
            return remote();
        }
        if (spec.leased() != null) {
            keeper = applicationContext.getBean(ModularLeaseKeeper.class);
            LeaseNeed refused = keeper.acquireAll(spec.localName(), spec.leased().needs());
            if (refused != null) {
                log.info("Lease '" + refused.name() + "' is full; " + spec.localName() + " is reached remotely from this process");
                return remote();
            }
        }
        return ServiceBinding.local(spec.serviceName(), spec.version(), constructImplementation(), this::interceptors);
    }

    private ServiceBinding remote() {
        ServiceTransport transport;
        try {
            transport = applicationContext.getBean(ServiceTransport.class);
        } catch (NoSuchBeanDefinitionException e) {
            // Only reachable without the Boot starter, which always provides one.
            throw new IllegalStateException("Modular service '" + spec.serviceName() + "' version '" + spec.version()
                    + "' is internal-rest, but there is no ServiceTransport bean to call it with -- @Import "
                    + "ModularTransportConfiguration (or ModularConfiguration, to also serve embedded services).", e);
        }
        return ServiceBinding.remote(spec.serviceName(), spec.version(), transport, this::interceptors);
    }

    private List<ServiceCallInterceptor> interceptors() {
        return applicationContext.getBeanProvider(ServiceCallInterceptor.class).orderedStream().toList();
    }

    private Object constructImplementation() {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        String implBeanName = beanName + ".impl";

        RootBeanDefinition definition = new RootBeanDefinition(spec.implClass());
        definition.setAutowireCandidate(false);
        LeasedImplementation leased = spec.leased();
        if (leased != null) {
            definition.setDependsOn(ModularServiceRegistrar.LEASE_KEEPER_BEAN_NAME);
            leased.leaseParameters().forEach((index, leaseName) -> {
                LeaseNeed need = leased.needs().stream().filter(n -> n.name().equals(leaseName)).findFirst().orElseThrow();
                definition.getConstructorArgumentValues().addIndexedArgumentValue(index, new Lease(need.name(), need.amount()));
            });
            leased.resourceParameters().forEach((index, leaseName) ->
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(index, keeper.resource(leaseName)));
        }
        registry.registerBeanDefinition(implBeanName, definition);
        try {
            Object implementation = applicationContext.getBean(implBeanName);
            // The implementation is destroyed first, then this bean, which hands the leases back.
            ((ConfigurableApplicationContext) applicationContext).getBeanFactory().registerDependentBean(beanName, implBeanName);
            return implementation;
        } catch (RuntimeException e) {
            if (keeper != null) {
                keeper.release(spec.localName());
            }
            throw e;
        }
    }

    @Override
    public Class<?> getObjectType() {
        return spec.serviceInterface();
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public boolean isEagerInit() {
        return spec.implClass() != null;
    }

    @Override
    public void destroy() {
        if (keeper != null) {
            keeper.release(spec.localName());
        }
    }
}
