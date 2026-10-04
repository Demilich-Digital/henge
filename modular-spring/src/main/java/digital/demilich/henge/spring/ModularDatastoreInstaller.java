package digital.demilich.henge.spring;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Makes sure the process's {@link SystemEphemeralDatastore} exists, and is constructed before any
 * modular service bean: a service that needs a lease (or anything else the datastore holds) can then
 * rely on it being there when its own construction begins, however the application orders its other
 * beans.
 *
 * <p>It runs as a {@link BeanFactoryPostProcessor}, after every bean definition has been registered,
 * including the ones registered by other configuration, auto-configuration and registrars. That is
 * what lets it tell whether the application brought its own datastore (a bean of that type, however
 * named): if so that one is used, and if not an {@link InProcessEphemeralDatastore} is registered. More
 * than one is an error unless exactly one is {@code @Primary}. It then adds the datastore to the
 * {@code depends-on} of every service bean ({@link ModularServiceRegistrar} passes their names), so
 * Spring creates it first and destroys it last.
 */
class ModularDatastoreInstaller implements BeanFactoryPostProcessor {

    static final String DEFAULT_BEAN_NAME = "modularDatastore";

    private final List<String> serviceBeanNames;

    ModularDatastoreInstaller(List<String> serviceBeanNames) {
        this.serviceBeanNames = serviceBeanNames;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String datastoreBeanName = findOrRegisterDatastore(beanFactory);
        for (String serviceBeanName : serviceBeanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(serviceBeanName);
            List<String> dependsOn = new ArrayList<>(Arrays.asList(
                    definition.getDependsOn() == null ? new String[0] : definition.getDependsOn()));
            dependsOn.add(datastoreBeanName);
            definition.setDependsOn(dependsOn.toArray(String[]::new));
        }
    }

    private static String findOrRegisterDatastore(ConfigurableListableBeanFactory beanFactory) {
        String[] names = beanFactory.getBeanNamesForType(SystemEphemeralDatastore.class, true, false);
        if (names.length == 0) {
            ((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(
                    DEFAULT_BEAN_NAME, new RootBeanDefinition(InProcessEphemeralDatastore.class));
            return DEFAULT_BEAN_NAME;
        }
        if (names.length == 1) {
            return names[0];
        }
        List<String> primaries = Arrays.stream(names)
                .filter(name -> beanFactory.getBeanDefinition(name).isPrimary())
                .toList();
        if (primaries.size() != 1) {
            throw new IllegalStateException("Found " + names.length + " SystemEphemeralDatastore beans "
                    + Arrays.toString(names) + "; Henge keeps its shared state in exactly one. Remove all but one, or mark one @Primary.");
        }
        return primaries.get(0);
    }
}
