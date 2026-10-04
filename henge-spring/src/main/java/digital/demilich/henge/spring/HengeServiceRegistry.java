package digital.demilich.henge.spring;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;

/**
 * Holds a {@link HengeServiceDescriptor} for every {@code @HengeService} interface that
 * THIS process hosts an {@link HengeMode#EMBEDDED} implementation of. Consulted by
 * {@link HengeDispatcherController} to decide whether an incoming dispatch request is
 * actually servable here.
 *
 * <p>Whether a version is hosted is its {@link ServiceBinding}'s answer: a leased service is only hosted
 * if its leases were granted here, which isn't known until its binding has been created.
 */
public class HengeServiceRegistry implements BeanFactoryAware {

    private final Map<String, HengeServiceDescriptor> byKey;
    private BeanFactory beanFactory;

    HengeServiceRegistry(List<HengeServiceDescriptor> descriptors) {
        this.byKey = descriptors.stream().collect(Collectors.toMap(HengeServiceRegistry::key, Function.identity()));
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    public Optional<HengeServiceDescriptor> find(String name, int version) {
        return Optional.ofNullable(byKey.get(key(name, version))).filter(this::hostedHere);
    }

    /** Every service version this process serves: all the embedded ones, minus leased ones it wasn't granted. */
    List<HengeServiceDescriptor> hosted() {
        return byKey.values().stream().filter(this::hostedHere).toList();
    }

    /** The binding of {@code descriptor}'s service version, created if it hasn't been. */
    ServiceBinding binding(HengeServiceDescriptor descriptor) {
        return beanFactory.getBean(BeanFactory.FACTORY_BEAN_PREFIX + descriptor.beanName(), HengeServiceBindingFactoryBean.class)
                .binding();
    }

    /**
     * Stops hosting a service version, so this process reaches it remotely like any other caller, and
     * releases what it held. In the order a caller can follow without a failed call: stop advertising it,
     * wait {@code grace} (callers that already read the advertisement keep being served meanwhile),
     * then switch, drain, destroy and release (see {@link HengeServiceBindingFactoryBean#retire}). The
     * grace should be at least the advertisement's TTL and the callers' refresh interval. Does nothing
     * if this process doesn't host the version.
     *
     * @throws IllegalArgumentException if no such service version is declared here
     */
    void retire(String name, int version, Duration grace, Duration drainTimeout) throws InterruptedException {
        HengeServiceDescriptor descriptor = byKey.get(key(name, version));
        if (descriptor == null) {
            throw new IllegalArgumentException("No service version " + name + "@" + version + " is declared here");
        }
        if (!hostedHere(descriptor)) {
            return;
        }
        HengeServiceAdvertiser advertiser = beanFactory.getBeanProvider(HengeServiceAdvertiser.class).getIfAvailable();
        if (advertiser != null) {
            advertiser.withdraw(name, version);
        }
        Thread.sleep(grace);
        beanFactory.getBean(BeanFactory.FACTORY_BEAN_PREFIX + descriptor.beanName(), HengeServiceBindingFactoryBean.class)
                .retire(drainTimeout);
    }

    private boolean hostedHere(HengeServiceDescriptor descriptor) {
        return binding(descriptor).isLocal();
    }

    private static String key(HengeServiceDescriptor descriptor) {
        return key(descriptor.name(), descriptor.version());
    }

    private static String key(String name, int version) {
        return name + "/" + version;
    }
}
