package io.modular.spring;

import io.modular.core.ModularService;
import io.modular.core.ServiceVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
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
        Set<String> basePackages = resolveBasePackages(importingClassMetadata);
        Set<Class<?>> serviceInterfaces = discoverServiceInterfaces(basePackages);
        Map<Class<?>, Map<String, Class<?>>> localImpls = discoverServiceVersionImpls(basePackages, serviceInterfaces);
        Binder binder = Binder.get(environment);

        List<ModularServiceDescriptor> embedded = new ArrayList<>();

        for (Class<?> serviceInterface : serviceInterfaces) {
            ModularService annotation = serviceInterface.getAnnotation(ModularService.class);
            String name = defaultName(serviceInterface, annotation);
            String defaultVersion = annotation.defaultVersion();

            Map<String, Class<?>> implsByVersion = localImpls.getOrDefault(serviceInterface, Map.of());
            ModularProperties.ServiceConfig config = binder.bind("modular.services." + name, Bindable.of(ModularProperties.ServiceConfig.class))
                    .orElseGet(ModularProperties.ServiceConfig::new);

            Set<String> versions = new LinkedHashSet<>(implsByVersion.keySet());
            versions.addAll(config.getVersions().keySet());
            versions.add(defaultVersion);

            for (String version : versions) {
                String qualifiedName = name + "@" + version;
                ModularMode mode = ModularMode.parse(config.resolveMode(version), qualifiedName);
                Class<?> implClass = implsByVersion.get(version);
                String beanName = ClassUtils.getShortNameAsProperty(serviceInterface) + "-" + version;
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

    private Set<Class<?>> discoverServiceInterfaces(Set<String> basePackages) {
        Set<Class<?>> found = new LinkedHashSet<>();
        ServiceInterfaceScanner scanner = new ServiceInterfaceScanner();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                Class<?> type = resolveClass(candidate.getBeanClassName());
                if (type != null) {
                    found.add(type);
                }
            }
        }
        return found;
    }

    private Map<Class<?>, Map<String, Class<?>>> discoverServiceVersionImpls(
            Set<String> basePackages, Set<Class<?>> serviceInterfaces) {
        Map<Class<?>, Map<String, Class<?>>> result = new LinkedHashMap<>();
        ServiceVersionScanner scanner = new ServiceVersionScanner();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                Class<?> implClass = resolveClass(candidate.getBeanClassName());
                if (implClass == null) {
                    continue;
                }
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

    private static Class<?> resolveClass(String className) {
        if (className == null) {
            return null;
        }
        try {
            return ClassUtils.forName(className, ModularServiceRegistrar.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
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
