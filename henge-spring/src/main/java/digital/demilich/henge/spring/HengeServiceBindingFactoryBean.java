package digital.demilich.henge.spring;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.ServiceTransport;
import digital.demilich.henge.core.StoreUnavailableException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Registered by {@link HengeServiceRegistrar} under every service version's bean name, and produces the
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
 *       lease, so the service is reached at its configured url, or else wherever it is advertised. If the
 *       datastore can't be reached to ask, the decision waits ({@link HengeBootGate}) and calls fail
 *       meanwhile as the datastore being unavailable. A refused service stays a candidate of the
 *       {@link HengeLeasePoller}, which hosts it here if its leases are granted later;
 *   <li>the transport, for a service configured {@code internal-rest}.
 * </ul>
 *
 * <p>One with an implementation here is created eagerly ({@link #isEagerInit()}), so a refusal or a
 * configuration mistake fails startup rather than the first call. One that is only ever remote is created
 * when something first asks for it, as it always was, so a process that never calls it needs no
 * {@link ServiceTransport}. (Services that over-allocate a lease are rejected before anything is
 * constructed, see {@link HengeServiceRegistrar}.)
 */
class HengeServiceBindingFactoryBean implements SmartFactoryBean<Object>, ApplicationContextAware, BeanNameAware, DisposableBean {

    private static final Log log = LogFactory.getLog(HengeServiceBindingFactoryBean.class);

    private final ServiceBindingSpec spec;

    private ApplicationContext applicationContext;
    private String beanName;
    private HengeLeaseKeeper keeper;
    private ServiceBinding binding;
    private Object proxy;
    private final AtomicBoolean retired = new AtomicBoolean();

    HengeServiceBindingFactoryBean(ServiceBindingSpec spec) {
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
                    new HengeServiceInvocationHandler(binding));
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
            keeper = applicationContext.getBean(HengeLeaseKeeper.class);
            ServiceBinding pending = ServiceBinding.pending(spec.serviceName(), spec.version(), this::interceptors);
            if (!decide(pending, false)) {
                // The datastore can't be reached: this process starts anyway, not ready, and decides once it can.
                applicationContext.getBean(HengeBootGate.class).await(spec.localName(), () -> decide(pending, true));
            }
            return pending;
        }
        return ServiceBinding.local(spec.serviceName(), spec.version(), constructImplementation(), this::interceptors);
    }

    /**
     * Claims the leases and makes {@code pending} hosted here if they are granted, reached remotely if
     * not. False if the datastore couldn't be reached to ask, so it is still pending ({@code quiet}: it
     * was already said so).
     */
    private boolean decide(ServiceBinding pending, boolean quiet) {
        LeaseNeed refused;
        try {
            refused = keeper.acquireAll(spec.localName(), spec.leased().needs());
        } catch (StoreUnavailableException e) {
            if (!quiet) {
                log.warn(spec.localName() + " needs a lease, and the ephemeral store can't be reached to claim it; "
                        + "calls to it fail until it can be", e);
            }
            return false;
        }
        if (refused != null) {
            log.info("Lease '" + refused.name() + "' is full; " + spec.localName() + " is reached remotely from this process");
            pending.becomeRemote(transport());
            applicationContext.getBean(HengeLeasePoller.class).add(candidate(pending));
        } else {
            pending.becomeLocal(constructImplementation());
            keeper.onEviction(spec.localName(), this::evict);
        }
        return true;
    }

    /**
     * Claims the leases again, for the poller, and hosts the service here if they are granted. False if
     * they are refused. If the implementation can't be built, what was claimed is handed back (see
     * {@link #constructImplementation}) and the failure goes to the poller.
     */
    private boolean rehost(ServiceBinding binding) {
        if (keeper.acquireAll(spec.localName(), spec.leased().needs()) != null) {
            return false;
        }
        binding.rehost(constructImplementation());
        keeper.onEviction(spec.localName(), this::evict);
        log.info("A lease was granted after all: " + spec.localName() + " is hosted by this process");
        HengeServiceAdvertiser advertiser = applicationContext.getBeanProvider(HengeServiceAdvertiser.class).getIfAvailable();
        if (advertiser != null) {
            advertiser.renew();
        }
        return true;
    }

    private HengeLeasePoller.Candidate candidate(ServiceBinding binding) {
        return new HengeLeasePoller.Candidate(spec.localName(), spec.leased().needs(), () -> rehost(binding));
    }

    /**
     * Gives this service up because a lease it stands on can't be kept (see {@link HengeLeaseKeeper}): stops
     * hosting it as a retirement does, releasing what it held, and then waits to be hosted again like a service
     * that was refused its lease, since the lease may well come back. Unlike a retirement, which is final.
     *
     * <p>The store calls in a retirement are best-effort, each finished by its entry's TTL if the store doesn't
     * take it, so a store that is away can't stop an eviction part-way. Whatever happens, once the service is no
     * longer hosted here it is advertised again when it is, and it is a candidate: a service that is neither
     * hosted nor a candidate would never be hosted here again.
     */
    private void evict() {
        HengeProperties properties = new HengeProperties(applicationContext.getEnvironment());
        try {
            applicationContext.getBean(HengeServiceRegistry.class).retire(spec.serviceName(), spec.version(),
                    properties.getLeaseEvictGrace(), properties.getLeaseEvictDrainTimeout());
        } catch (InterruptedException e) {
            // Only a closing context interrupts an eviction; nothing is hosted again after it.
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            log.error("Giving up " + spec.localName() + " failed", e);
        }
        HengeServiceAdvertiser advertiser = applicationContext.getBeanProvider(HengeServiceAdvertiser.class).getIfAvailable();
        if (binding().isLocal()) {
            // Still hosted, on a lease that is being given up: a bug, since nothing left in an eviction fails on the
            // store. It is advertised again, so that it is at least reached while it is hosted.
            log.error(spec.localName() + " is still hosted here, on a lease this process has given up");
            if (advertiser != null) {
                advertiser.resume(spec.serviceName(), spec.version());
            }
            return;
        }
        retired.set(false);
        if (advertiser != null) {
            advertiser.resume(spec.serviceName(), spec.version());
        }
        applicationContext.getBean(HengeLeasePoller.class).add(candidate(binding()));
        log.warn(spec.localName() + " is no longer hosted by this process: its lease was lost. It is reached remotely, "
                + "and hosted here again if the lease has room later");
    }

    private ServiceBinding remote() {
        return ServiceBinding.remote(spec.serviceName(), spec.version(), transport(), this::interceptors);
    }

    private ServiceTransport transport() {
        try {
            return applicationContext.getBean(ServiceTransport.class);
        } catch (NoSuchBeanDefinitionException e) {
            // Only reachable without the Boot starter, which always provides one.
            throw new IllegalStateException("Henge service '" + spec.serviceName() + "' version '" + spec.version()
                    + "' is internal-rest, but there is no ServiceTransport bean to call it with -- @Import "
                    + "HengeTransportConfiguration (or HengeConfiguration, to also serve embedded services).", e);
        }
    }

    private List<ServiceCallInterceptor> interceptors() {
        return applicationContext.getBeanProvider(ServiceCallInterceptor.class).orderedStream().toList();
    }

    /**
     * Stops hosting this service version and gives back what it held: new calls go to the transport, the
     * calls already running in the implementation get up to {@code drainTimeout} to finish, the
     * implementation is destroyed (so whatever it owns is closed), and only then is its share of its leases
     * released, since it stands on the lease's resource. Does nothing for a version that isn't hosted here,
     * and the second of two concurrent retirements. An implementation still busy after the timeout is
     * destroyed anyway, since a service that never finishes would otherwise hold its leases forever.
     *
     * <p>Not synchronized: the dispatcher asks {@link #binding()} whether this version is hosted, and must
     * not wait out a drain to be told no.
     */
    void retire(Duration drainTimeout) throws InterruptedException {
        ServiceBinding current = binding();
        if (!current.isLocal() || !retired.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!current.retire(transport(), drainTimeout)) {
                log.warn(spec.localName() + " still had calls running after " + drainTimeout + "; destroying it anyway");
            }
        } finally {
            destroyImplementation();
            if (keeper != null) {
                keeper.release(spec.localName());
            }
        }
    }

    private void destroyImplementation() {
        ConfigurableApplicationContext context = (ConfigurableApplicationContext) applicationContext;
        String implBeanName = beanName + ".impl";
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) context.getBeanFactory();
        beanFactory.destroySingleton(implBeanName);
        beanFactory.removeBeanDefinition(implBeanName);
    }

    private Object constructImplementation() {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        String implBeanName = beanName + ".impl";

        RootBeanDefinition definition = new RootBeanDefinition(spec.implClass());
        definition.setAutowireCandidate(false);
        LeasedImplementation leased = spec.leased();
        if (leased != null) {
            definition.setDependsOn(HengeServiceRegistrar.LEASE_KEEPER_BEAN_NAME);
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
