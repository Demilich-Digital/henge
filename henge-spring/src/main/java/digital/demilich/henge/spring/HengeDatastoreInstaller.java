package digital.demilich.henge.spring;

import digital.demilich.henge.core.InProcessEphemeralDatastore;
import digital.demilich.henge.core.StoreUnavailableException;
import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastoreProvider;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.TreeMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Makes sure the process's {@link SystemEphemeralDatastore} exists, and is constructed before any
 * Henge service bean: a service that needs a lease (or anything else the datastore holds) can then
 * rely on it being there when its own construction begins, however the application orders its other
 * beans.
 *
 * <p>It runs as a {@link BeanFactoryPostProcessor}, after every bean definition has been registered,
 * including the ones registered by other configuration, auto-configuration and registrars. That is
 * what lets it tell whether the application brought its own datastore (a bean of that type, however
 * named): if so that one is used, and if not one is registered as {@code henge.store.type} says: the
 * {@link InProcessEphemeralDatastore} by default, or the {@link SystemEphemeralDatastoreProvider} of
 * that type found on the classpath (e.g. {@code redis}, from {@code henge-redis}). Setting a type
 * and defining a datastore bean is a contradiction and fails. More than one bean is an error unless
 * exactly one is {@code @Primary}. It also registers the {@link HengeBootGate}. Whichever datastore it is, the process uses it through a {@link GuardedDatastore}, so
 * that one view of whether it is reachable is shared. It then adds the datastore to the
 * {@code depends-on} of every service bean ({@link HengeServiceRegistrar} passes their names), so
 * Spring creates it first and destroys it last.
 */
class HengeDatastoreInstaller implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String DEFAULT_BEAN_NAME = "hengeDatastore";
    static final String BOOT_GATE_BEAN_NAME = "hengeBootGate";

    private static final Log log = LogFactory.getLog(HengeDatastoreInstaller.class);
    static final String IN_PROCESS = "in-process";

    private Environment environment;

    private final List<String> serviceBeanNames;

    HengeDatastoreInstaller(List<String> serviceBeanNames) {
        this.serviceBeanNames = serviceBeanNames;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String datastoreBeanName = findOrRegisterDatastore(beanFactory, environment);
        HengeProperties properties = new HengeProperties(environment);
        Duration initialBackoff = properties.getStoreBackoffInitial();
        Duration maxBackoff = properties.getStoreBackoffMax();
        beanFactory.addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return beanName.equals(datastoreBeanName) && bean instanceof SystemEphemeralDatastore datastore
                        ? new GuardedDatastore(datastore, initialBackoff, maxBackoff, InstantSource.system())
                        : bean;
            }
        });
        if (!((BeanDefinitionRegistry) beanFactory).containsBeanDefinition(BOOT_GATE_BEAN_NAME)) {
            ((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(BOOT_GATE_BEAN_NAME,
                    new RootBeanDefinition(HengeBootGate.class));
        }
        for (String serviceBeanName : serviceBeanNames) {
            BeanDefinition definition = beanFactory.getBeanDefinition(serviceBeanName);
            List<String> dependsOn = new ArrayList<>(Arrays.asList(
                    definition.getDependsOn() == null ? new String[0] : definition.getDependsOn()));
            dependsOn.add(datastoreBeanName);
            definition.setDependsOn(dependsOn.toArray(String[]::new));
        }
    }

    private static String findOrRegisterDatastore(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        String[] names = beanFactory.getBeanNamesForType(SystemEphemeralDatastore.class, true, false);
        String type = environment.getProperty("henge.store.type");
        if (names.length == 0) {
            ((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(
                    DEFAULT_BEAN_NAME, configuredDatastore(type, beanFactory.getBeanClassLoader(), environment));
            return DEFAULT_BEAN_NAME;
        }
        if (type != null && !type.isBlank()) {
            throw new IllegalStateException("henge.store.type=" + type.trim() + " is set, but the application also defines a "
                    + "SystemEphemeralDatastore bean " + Arrays.toString(names) + ". Remove one: the property selects the "
                    + "datastore, the bean is the datastore.");
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

    /** The definition of the datastore {@code henge.store.type} names; the in-process one if it's unset. */
    private static RootBeanDefinition configuredDatastore(String type, ClassLoader classLoader, Environment environment) {
        if (type == null || type.isBlank() || type.trim().equals(IN_PROCESS)) {
            return new RootBeanDefinition(InProcessEphemeralDatastore.class);
        }
        Map<String, SystemEphemeralDatastoreProvider> providers = new TreeMap<>();
        for (SystemEphemeralDatastoreProvider provider : ServiceLoader.load(SystemEphemeralDatastoreProvider.class, classLoader)) {
            providers.putIfAbsent(provider.type(), provider);
        }
        SystemEphemeralDatastoreProvider provider = providers.get(type.trim());
        if (provider == null) {
            throw new IllegalStateException("henge.store.type=" + type.trim() + " matches no datastore on the classpath; "
                    + "available: " + IN_PROCESS + (providers.isEmpty() ? "" : ", " + String.join(", ", providers.keySet()))
                    + (type.trim().equals("redis") ? ". Add the henge-redis module for redis." : "."));
        }
        // Built when the bean is, not now, so a misconfiguration is a bean-creation error like any other.
        // A store that can't be reached is not: the process starts without it, not ready (HengeBootGate).
        RootBeanDefinition definition = new RootBeanDefinition(SystemEphemeralDatastore.class, () -> {
            try {
                return provider.create(environment::getProperty);
            } catch (StoreUnavailableException e) {
                log.warn("Starting without the ephemeral store, which can't be reached yet: " + e.getMessage());
                return new ReconnectingDatastore(() -> provider.create(environment::getProperty));
            }
        });
        definition.setTargetType(SystemEphemeralDatastore.class);
        return definition;
    }
}
