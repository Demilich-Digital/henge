package digital.demilich.henge.spring;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;

/**
 * Holds a {@link ModularServiceDescriptor} for every {@code @ModularService} interface that
 * THIS process hosts an {@link ModularMode#EMBEDDED} implementation of. Consulted by
 * {@link ModularDispatcherController} to decide whether an incoming dispatch request is
 * actually servable here.
 */
public class ModularServiceRegistry implements BeanFactoryAware {

    private final Map<String, ModularServiceDescriptor> byKey;
    private final Set<String> leasedBeanNames;
    private BeanFactory beanFactory;

    ModularServiceRegistry(List<ModularServiceDescriptor> descriptors) {
        this(descriptors, Set.of());
    }

    /**
     * @param leasedBeanNames the beans among {@code descriptors} whose implementation declares leases:
     *     they are only embedded here if this process was granted them, which isn't known until the
     *     bean has been created.
     */
    ModularServiceRegistry(List<ModularServiceDescriptor> descriptors, Set<String> leasedBeanNames) {
        this.byKey = descriptors.stream().collect(Collectors.toMap(ModularServiceRegistry::key, Function.identity()));
        this.leasedBeanNames = leasedBeanNames;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    public Optional<ModularServiceDescriptor> find(String name, int version) {
        return Optional.ofNullable(byKey.get(key(name, version))).filter(this::hostedHere);
    }

    /** Every service version this process serves: all the embedded ones, minus leased ones it wasn't granted. */
    List<ModularServiceDescriptor> hosted() {
        return byKey.values().stream().filter(this::hostedHere).toList();
    }

    private boolean hostedHere(ModularServiceDescriptor descriptor) {
        return !leasedBeanNames.contains(descriptor.beanName()) || grantedHere(descriptor);
    }

    /** Creating the bean decides it (a lazily initialized one may not have been yet). */
    private boolean grantedHere(ModularServiceDescriptor descriptor) {
        beanFactory.getBean(descriptor.beanName());
        return beanFactory.getBean(ModularLeaseKeeper.class).hosts(descriptor.name() + "@" + descriptor.version());
    }

    private static String key(ModularServiceDescriptor descriptor) {
        return key(descriptor.name(), descriptor.version());
    }

    private static String key(String name, int version) {
        return name + "/" + version;
    }
}
