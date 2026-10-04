package digital.demilich.henge.spring;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>Whether a version is hosted is its {@link ServiceBinding}'s answer: a leased service is only hosted
 * if its leases were granted here, which isn't known until its binding has been created.
 */
public class ModularServiceRegistry implements BeanFactoryAware {

    private final Map<String, ModularServiceDescriptor> byKey;
    private BeanFactory beanFactory;

    ModularServiceRegistry(List<ModularServiceDescriptor> descriptors) {
        this.byKey = descriptors.stream().collect(Collectors.toMap(ModularServiceRegistry::key, Function.identity()));
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

    /** The binding of {@code descriptor}'s service version, created if it hasn't been. */
    ServiceBinding binding(ModularServiceDescriptor descriptor) {
        return beanFactory.getBean(BeanFactory.FACTORY_BEAN_PREFIX + descriptor.beanName(), ModularServiceBindingFactoryBean.class)
                .binding();
    }

    private boolean hostedHere(ModularServiceDescriptor descriptor) {
        return binding(descriptor).isLocal();
    }

    private static String key(ModularServiceDescriptor descriptor) {
        return key(descriptor.name(), descriptor.version());
    }

    private static String key(String name, int version) {
        return name + "/" + version;
    }
}
