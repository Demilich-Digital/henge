package digital.demilich.henge.spring;

import digital.demilich.henge.core.ModularService;
import digital.demilich.henge.core.ServiceVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Discovers {@code @ModularService} interfaces and their {@code @ServiceVersion}-annotated
 * implementations on startup, and for every (interface, version) pair in play — whether declared
 * by a local impl class, by explicit {@code modular.services.<name>.versions.<version>.*} config,
 * or implicitly via the interface's {@code defaultVersion()} — registers exactly one bean:
 *
 * <ul>
 *   <li><b>embedded</b> (default) — the local {@code @ServiceVersion}-annotated implementation
 *       class, registered directly (impls are no longer picked up by plain {@code @ComponentScan}
 *       at all, since {@code @ServiceVersion} carries no {@code @Component} meta-annotation).
 *       Fails fast if no local implementation exists for a version configured/expected as
 *       embedded.</li>
 *   <li><b>internal-rest</b> — a dynamic proxy that dispatches calls over HTTP.</li>
 * </ul>
 *
 * Every registered bean carries {@code @ServiceVersion} qualifier metadata (so a dependency can
 * pin a specific version via the same annotation on its injection point) and, for whichever
 * version matches the interface's {@code defaultVersion()}, is marked {@code @Primary} (so a
 * dependency with no qualifier at all resolves there — the zero-ceremony common case).
 *
 * <p>Runs as an {@link ImportBeanDefinitionRegistrar} imported by {@link EnableModularServices}.
 * {@code @Import}-triggered registrars run after the importing class's own {@code @ComponentScan}
 * (Spring always processes a {@code @Configuration} class's {@code @ComponentScan} before its
 * {@code @Import}s), so plain component-scanned beans this process also defines are already
 * present here — though modular service implementations themselves are registered by this class,
 * not by {@code @ComponentScan}.
 */
class ModularServiceRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        // The bean factory's own classloader, not this class's -- under Spring Boot DevTools'
        // restart classloader (or any other classloader indirection), a candidate class found by
        // scanning the application's classpath may not be loadable via the classloader that
        // happened to load this framework class.
        ClassLoader classLoader = (registry instanceof ConfigurableBeanFactory beanFactory)
                ? beanFactory.getBeanClassLoader()
                : ModularServiceRegistrar.class.getClassLoader();

        Set<String> basePackages = resolveBasePackages(importingClassMetadata);
        Set<Class<?>> serviceInterfaces = discoverServiceInterfaces(basePackages, classLoader);
        Map<Class<?>, Map<String, Class<?>>> localImpls = discoverServiceVersionImpls(basePackages, serviceInterfaces, classLoader);
        ModularProperties properties = new ModularProperties(environment);
        ServeSpec serveSpec = ServeSpec.parse(properties.getServe());

        List<ModularServiceDescriptor> embedded = new ArrayList<>();
        Map<String, Class<?>> namesToInterfaces = new LinkedHashMap<>();

        for (Class<?> serviceInterface : serviceInterfaces) {
            ModularService annotation = serviceInterface.getAnnotation(ModularService.class);
            String name = defaultName(serviceInterface, annotation);

            Class<?> existingOwner = namesToInterfaces.putIfAbsent(name, serviceInterface);
            if (existingOwner != null) {
                throw new IllegalStateException("Two @ModularService interfaces resolve to the same service name '"
                        + name + "': " + existingOwner.getName() + " and " + serviceInterface.getName()
                        + " -- disambiguate with @ModularService(name = ...) on one of them.");
            }

            String defaultVersion = annotation.defaultVersion();

            Map<String, Class<?>> implsByVersion = localImpls.getOrDefault(serviceInterface, Map.of());
            ModularProperties.ServiceConfig config = properties.service(name);

            Set<String> versions = new LinkedHashSet<>(implsByVersion.keySet());
            versions.addAll(config.explicitVersions());
            versions.addAll(serveSpec.versionsFor(name));
            versions.add(defaultVersion);

            for (String version : versions) {
                String qualifiedName = name + "@" + version;
                String explicitMode = config.resolveMode(version);
                boolean servedHere = serveSpec.isEmpty() || serveSpec.matches(name, version);
                ModularMode mode = ModularMode.parse(explicitMode != null ? explicitMode : (servedHere ? "embedded" : "internal-rest"), qualifiedName);

                if (explicitMode != null && mode == ModularMode.INTERNAL_REST && serveSpec.matches(name, version)) {
                    throw new IllegalStateException("Modular service '" + qualifiedName + "' is listed in --modular.serve "
                            + "(meaning it should be embedded in this process) but its configured mode (under "
                            + "modular.services." + name + ", possibly per-version) is explicitly internal-rest -- "
                            + "remove it from --modular.serve, or drop the explicit mode override.");
                }

                Class<?> implClass = implsByVersion.get(version);
                // Derived from the resolved service name (not the interface's raw simple name) so
                // that two interfaces with the same simple name in different packages -- already
                // rejected above unless disambiguated via @ModularService(name = ...) -- get
                // distinct bean names too, once disambiguated.
                String beanName = name + "-" + version;
                boolean isDefault = version.equals(defaultVersion);

                RootBeanDefinition definition;
                if (mode == ModularMode.EMBEDDED) {
                    if (implClass == null) {
                        throw new IllegalStateException("Modular service '" + name + "' version '" + version
                                + "' is configured as embedded (the default) but no @ServiceVersion(" + serviceInterface.getSimpleName()
                                + ".class, \"" + version + "\") implementation was found on the classpath. Either add one, or set "
                                + "modular.services." + name + ".versions." + version + ".mode=internal-rest with a matching "
                                + ".url pointing at the process that hosts it.");
                    }
                    definition = new RootBeanDefinition(implClass);
                    embedded.add(ModularServiceDescriptor.of(name, version, serviceInterface, beanName));
                } else {
                    definition = new RootBeanDefinition(ModularServiceProxyFactoryBean.class);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(0, serviceInterface);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(1, name);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(2, version);
                }

                addServiceVersionQualifier(definition, serviceInterface, version);
                definition.setPrimary(isDefault);
                registry.registerBeanDefinition(beanName, definition);
            }
        }

        registry.registerBeanDefinition("modularServiceRegistry",
                BeanDefinitionBuilder.genericBeanDefinition(ModularServiceRegistry.class)
                        .addConstructorArgValue(embedded)
                        .getBeanDefinition());
    }

    private static void addServiceVersionQualifier(RootBeanDefinition definition, Class<?> serviceInterface, String version) {
        AutowireCandidateQualifier qualifier = new AutowireCandidateQualifier(ServiceVersion.class);
        qualifier.setAttribute("value", serviceInterface);
        qualifier.setAttribute("version", version);
        definition.addQualifier(qualifier);
    }

    private Set<Class<?>> discoverServiceInterfaces(Set<String> basePackages, ClassLoader classLoader) {
        Set<Class<?>> found = new LinkedHashSet<>();
        ServiceInterfaceScanner scanner = new ServiceInterfaceScanner();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                found.add(resolveClass(candidate.getBeanClassName(), classLoader));
            }
        }
        return found;
    }

    private Map<Class<?>, Map<String, Class<?>>> discoverServiceVersionImpls(
            Set<String> basePackages, Set<Class<?>> serviceInterfaces, ClassLoader classLoader) {
        Map<Class<?>, Map<String, Class<?>>> result = new LinkedHashMap<>();
        ServiceVersionScanner scanner = new ServiceVersionScanner();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                Class<?> implClass = resolveClass(candidate.getBeanClassName(), classLoader);
                ServiceVersion annotation = implClass.getAnnotation(ServiceVersion.class);
                Class<?> serviceInterface = annotation.value();
                String version = annotation.version();

                if (!serviceInterfaces.contains(serviceInterface)) {
                    throw new IllegalStateException(implClass.getName() + " is annotated @ServiceVersion("
                            + serviceInterface.getName() + ".class, \"" + version + "\") but " + serviceInterface.getName()
                            + " is not annotated @ModularService");
                }
                if (!serviceInterface.isAssignableFrom(implClass)) {
                    throw new IllegalStateException(implClass.getName() + " is annotated @ServiceVersion("
                            + serviceInterface.getName() + ".class, ...) but does not implement " + serviceInterface.getName());
                }

                Map<String, Class<?>> byVersion = result.computeIfAbsent(serviceInterface, k -> new LinkedHashMap<>());
                Class<?> existing = byVersion.putIfAbsent(version, implClass);
                if (existing != null) {
                    throw new IllegalStateException("Two implementations both claim version '" + version + "' of "
                            + serviceInterface.getName() + ": " + existing.getName() + " and " + implClass.getName());
                }
            }
        }
        return result;
    }

    private static Set<String> resolveBasePackages(AnnotationMetadata importingClassMetadata) {
        Map<String, Object> attributes =
                importingClassMetadata.getAnnotationAttributes(EnableModularServices.class.getName());

        Set<String> basePackages = new LinkedHashSet<>();
        if (attributes != null) {
            for (String basePackage : (String[]) attributes.get("basePackages")) {
                if (!basePackage.isBlank()) {
                    basePackages.add(basePackage);
                }
            }
            for (Class<?> markerClass : (Class<?>[]) attributes.get("basePackageClasses")) {
                basePackages.add(markerClass.getPackageName());
            }
        }

        if (basePackages.isEmpty()) {
            // Same default as @ComponentScan: the package of the annotated class itself.
            basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
        }
        return basePackages;
    }

    private static String defaultName(Class<?> serviceInterface, ModularService annotation) {
        if (!annotation.name().isBlank()) {
            return annotation.name();
        }
        String simple = serviceInterface.getSimpleName();
        StringBuilder kebab = new StringBuilder();
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    kebab.append('-');
                }
                kebab.append(Character.toLowerCase(c));
            } else {
                kebab.append(c);
            }
        }
        return kebab.toString();
    }

    /**
     * These classes were just found by classpath scanning -- failing to load them is never a
     * benign condition, so this fails fast with a descriptive message instead of silently
     * dropping the candidate (which previously made an unloadable service vanish without a trace,
     * worst case under Spring Boot DevTools' restart classloader where an entire batch could
     * vanish at once). Package-private (rather than {@code private}) so it's directly testable.
     */
    static Class<?> resolveClass(String className, ClassLoader classLoader) {
        try {
            return ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("Modular service discovery found '" + className + "' by classpath scanning "
                    + "but failed to load it (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "). This should "
                    + "never happen for a class the scanner itself just found; check for a classloader mismatch (e.g. "
                    + "Spring Boot DevTools' restart classloader) or a missing/incompatible dependency.", e);
        }
    }

    /**
     * By default {@link ClassPathScanningCandidateComponentProvider} only accepts concrete,
     * independent classes (since it's normally used to find beans to instantiate). We're
     * scanning for the interfaces themselves, so this override widens candidacy to independent
     * interfaces annotated with {@code @ModularService} — the same technique Spring Cloud
     * OpenFeign uses to find {@code @FeignClient} interfaces.
     */
    private static final class ServiceInterfaceScanner extends ClassPathScanningCandidateComponentProvider {

        ServiceInterfaceScanner() {
            super(false);
            addIncludeFilter(new AnnotationTypeFilter(ModularService.class));
        }

        @Override
        protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
            AnnotationMetadata metadata = beanDefinition.getMetadata();
            return metadata.isIndependent() && metadata.isInterface();
        }
    }

    /**
     * Finds {@code @ServiceVersion}-annotated implementation classes. Unlike
     * {@link ServiceInterfaceScanner}, no override is needed — the default candidacy check
     * (concrete, independent classes) is exactly what implementations are.
     */
    private static final class ServiceVersionScanner extends ClassPathScanningCandidateComponentProvider {

        ServiceVersionScanner() {
            super(false);
            addIncludeFilter(new AnnotationTypeFilter(ServiceVersion.class));
        }
    }
}
