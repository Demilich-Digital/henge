package digital.demilich.henge.spring;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.ModularService;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ServiceNames;
import digital.demilich.henge.core.ServiceVersion;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.BeanUtils;
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
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
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

    private static final String REGISTRY_BEAN_NAME = "modularServiceRegistry";
    private static final String DATASTORE_INSTALLER_BEAN_NAME = "modularDatastoreInstaller";
    private static final String ADVERTISER_BEAN_NAME = "modularServiceAdvertiser";
    private static final String TOPOLOGY_CATALOG_BEAN_NAME = "modularTopologyCatalog";
    static final String LEASE_KEEPER_BEAN_NAME = "modularLeaseKeeper";
    private static final String IMPORTED_BY_ATTRIBUTE = ModularServiceRegistrar.class.getName() + ".importedBy";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        // Each run registers its own registry of embedded services for the dispatcher; a second one
        // would replace the first (silently in plain Spring, where /_modular then 404s the first
        // run's services) or fail on the duplicate bean name (Boot) without saying why.
        if (registry.containsBeanDefinition(REGISTRY_BEAN_NAME)) {
            Object firstImporter = registry.getBeanDefinition(REGISTRY_BEAN_NAME).getAttribute(IMPORTED_BY_ATTRIBUTE);
            throw new IllegalStateException("@EnableModularServices is declared on both " + firstImporter + " and "
                    + importingClassMetadata.getClassName() + "; declare it once, listing every base package in "
                    + "basePackages/basePackageClasses.");
        }

        // The bean factory's own classloader, not this class's -- under Spring Boot DevTools'
        // restart classloader (or any other classloader indirection), a candidate class found by
        // scanning the application's classpath may not be loadable via the classloader that
        // happened to load this framework class. The scanners read class files through the same
        // loader, so what is found and what is loaded can never disagree.
        ClassLoader classLoader = (registry instanceof ConfigurableBeanFactory beanFactory)
                ? beanFactory.getBeanClassLoader()
                : ModularServiceRegistrar.class.getClassLoader();

        Set<String> basePackages = resolveBasePackages(importingClassMetadata);
        Set<Class<?>> serviceInterfaces = discoverServiceInterfaces(basePackages, classLoader);
        Map<Class<?>, Map<Integer, Class<?>>> localImpls = discoverServiceVersionImpls(basePackages, serviceInterfaces, classLoader);
        ModularProperties properties = new ModularProperties(environment);
        ServeSpec serveSpec = ServeSpec.parse(properties.getServe());

        List<ModularServiceDescriptor> embedded = new ArrayList<>();
        List<ModularTopologyCatalog.Entry> catalogEntries = new ArrayList<>();
        List<String> serviceBeanNames = new ArrayList<>();
        Set<String> leasedBeanNames = new LinkedHashSet<>();
        Map<String, Map<Integer, LeasedBean>> leasedVersions = new LinkedHashMap<>();
        Set<String> declaredLeaseNames = new LinkedHashSet<>();
        Map<String, List<LeaseNeed>> allLeasedServices = new LinkedHashMap<>();
        Map<String, Class<?>> namesToInterfaces = new LinkedHashMap<>();

        for (Class<?> serviceInterface : serviceInterfaces) {
            ModularService annotation = serviceInterface.getAnnotation(ModularService.class);
            String name = ServiceNames.serviceName(annotation.name(), serviceInterface.getSimpleName());
            validateNames(serviceInterface, name);

            Class<?> existingOwner = namesToInterfaces.putIfAbsent(name, serviceInterface);
            if (existingOwner != null) {
                throw new IllegalStateException("Two @ModularService interfaces resolve to the same service name '"
                        + name + "': " + existingOwner.getName() + " and " + serviceInterface.getName()
                        + " -- disambiguate with @ModularService(name = ...) on one of them.");
            }

            int defaultVersion = annotation.defaultVersion();

            Map<Integer, Class<?>> implsByVersion = localImpls.getOrDefault(serviceInterface, Map.of());
            ModularProperties.ServiceConfig config = properties.service(name);

            Set<Integer> versions = new LinkedHashSet<>(implsByVersion.keySet());
            versions.addAll(config.explicitVersions());
            versions.addAll(serveSpec.versionsFor(name));
            versions.add(defaultVersion);

            for (int version : versions) {
                String qualifiedName = name + "@" + version;
                String explicitMode = config.resolveMode(version);
                boolean servedHere = serveSpec.isEmpty() || serveSpec.matches(name, version);
                ModularTopologyCatalog.ModeSource modeSource = explicitMode != null ? ModularTopologyCatalog.ModeSource.EXPLICIT
                        : !serveSpec.isEmpty() ? ModularTopologyCatalog.ModeSource.SERVE : ModularTopologyCatalog.ModeSource.DEFAULT;
                ModularMode mode = ModularMode.parse(explicitMode != null ? explicitMode : (servedHere ? "embedded" : "internal-rest"), qualifiedName);

                if (explicitMode != null && mode == ModularMode.INTERNAL_REST && serveSpec.matches(name, version)) {
                    throw new IllegalStateException("Modular service '" + qualifiedName + "' is listed in --modular.serve "
                            + "(meaning it should be embedded in this process) but its configured mode (under "
                            + "modular.services." + name + ", possibly per-version) is explicitly internal-rest -- "
                            + "remove it from --modular.serve, or drop the explicit mode override.");
                }

                Class<?> implClass = implsByVersion.get(version);
                if (implClass != null) {
                    List<LeaseNeed> configured = configuredLeaseNeeds(properties, implClass);
                    if (!configured.isEmpty()) {
                        allLeasedServices.put(qualifiedName, configured);
                    }
                }
                // Derived from the resolved service name (not the interface's raw simple name) so
                // that two interfaces with the same simple name in different packages -- already
                // rejected above unless disambiguated via @ModularService(name = ...) -- get
                // distinct bean names too, once disambiguated.
                String beanName = name + "-" + version;
                boolean isDefault = version == defaultVersion;

                RootBeanDefinition definition;
                if (mode == ModularMode.EMBEDDED) {
                    if (implClass == null) {
                        throw new IllegalStateException("Modular service '" + name + "' version '" + version
                                + "' is configured as embedded (the default) but no @ServiceVersion(" + serviceInterface.getSimpleName()
                                + ".class, " + version + ") implementation was found on the classpath. Either add one, or set "
                                + "modular.services." + name + ".versions." + version + ".mode=internal-rest with a matching "
                                + ".url pointing at the process that hosts it.");
                    }
                    List<RequiresLease> declaredLeases = List.of(implClass.getAnnotationsByType(RequiresLease.class));
                    if (declaredLeases.isEmpty()) {
                        definition = new RootBeanDefinition(implClass);
                    } else {
                        definition = new RootBeanDefinition(ModularLeasedServiceFactoryBean.class);
                        LeasedImplementation leased =
                                leasedImplementation(properties, serviceInterface, name, version, implClass, declaredLeases);
                        allLeasedServices.put(qualifiedName, leased.needs());
                        definition.getConstructorArgumentValues().addIndexedArgumentValue(0, leased);
                        definition.setDependsOn(LEASE_KEEPER_BEAN_NAME);
                        leasedBeanNames.add(beanName);
                        leasedVersions.computeIfAbsent(name, k -> new TreeMap<>(Comparator.reverseOrder()))
                                .put(version, new LeasedBean(beanName, definition));
                        declaredLeases.forEach(lease -> declaredLeaseNames.add(lease.value()));
                    }
                    embedded.add(ModularServiceDescriptor.of(name, version, serviceInterface, beanName));
                } else {
                    definition = new RootBeanDefinition(ModularServiceProxyFactoryBean.class);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(0, serviceInterface);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(1, name);
                    definition.getConstructorArgumentValues().addIndexedArgumentValue(2, version);
                }

                catalogEntries.add(new ModularTopologyCatalog.Entry(name, version, serviceInterface.getName(), isDefault, mode,
                        modeSource, implClass == null ? null : implClass.getName(), beanName,
                        leaseDeclarations(properties, implClass), config.resolveUrl(version)));

                addServiceVersionQualifier(definition, serviceInterface, version);
                definition.setPrimary(isDefault);
                registry.registerBeanDefinition(beanName, definition);
                serviceBeanNames.add(beanName);
            }
        }

        List<String> unknownProperties = properties.unknownServiceProperties(namesToInterfaces.keySet());
        if (!unknownProperties.isEmpty()) {
            throw new IllegalStateException("Unrecognized modular.services configuration -- nothing reads these, so the "
                    + "service(s) they meant to configure would silently keep their defaults:\n  "
                    + String.join("\n  ", unknownProperties));
        }

        requireStaticallyGrantable(allLeasedServices);
        claimNewestVersionsFirst(leasedVersions);

        List<String> unknownLeaseProperties = properties.unknownLeaseProperties(declaredLeaseNames);
        if (!unknownLeaseProperties.isEmpty()) {
            throw new IllegalStateException("Unrecognized modular.leases configuration -- nothing reads these:\n  "
                    + String.join("\n  ", unknownLeaseProperties));
        }

        // A name that matches no discovered interface (almost always a typo) would otherwise leave
        // every service in this process as an internal-rest proxy, silently hosting nothing.
        Set<String> unknownServeNames = new LinkedHashSet<>(serveSpec.names());
        unknownServeNames.removeAll(namesToInterfaces.keySet());
        if (!unknownServeNames.isEmpty()) {
            throw new IllegalStateException("modular.serve names " + unknownServeNames + " but no @ModularService with "
                    + (unknownServeNames.size() == 1 ? "that name was" : "those names were") + " found; discovered services: "
                    + namesToInterfaces.keySet());
        }

        // Spring instantiates a bean-factory post-processor before everything else, so this one is
        // given plain values only; it runs once every bean definition, ours and the application's, exists.
        registry.registerBeanDefinition(DATASTORE_INSTALLER_BEAN_NAME, BeanDefinitionBuilder
                .genericBeanDefinition(ModularDatastoreInstaller.class)
                .addConstructorArgValue(serviceBeanNames)
                .getBeanDefinition());

        if (!leasedBeanNames.isEmpty()) {
            // The datastore (constructor argument 0) is autowired; only the TTL is given.
            BeanDefinition keeper = BeanDefinitionBuilder.genericBeanDefinition(ModularLeaseKeeper.class).getBeanDefinition();
            keeper.getConstructorArgumentValues().addIndexedArgumentValue(1, ModularLeaseKeeper.DEFAULT_TTL);
            registry.registerBeanDefinition(LEASE_KEEPER_BEAN_NAME, keeper);
        }

        // The datastore and the registry (arguments 0 and 1) are autowired.
        BeanDefinition advertiser = BeanDefinitionBuilder.genericBeanDefinition(ModularServiceAdvertiser.class).getBeanDefinition();
        advertiser.getConstructorArgumentValues().addIndexedArgumentValue(2, properties.getAdvertiseUrl(), String.class.getName());
        advertiser.getConstructorArgumentValues().addIndexedArgumentValue(3, ModularLeaseKeeper.DEFAULT_TTL);
        registry.registerBeanDefinition(ADVERTISER_BEAN_NAME, advertiser);

        registry.registerBeanDefinition(TOPOLOGY_CATALOG_BEAN_NAME, BeanDefinitionBuilder
                .genericBeanDefinition(ModularTopologyCatalog.class)
                .addConstructorArgValue(catalogEntries)
                .getBeanDefinition());

        BeanDefinition registryDefinition = BeanDefinitionBuilder.genericBeanDefinition(ModularServiceRegistry.class)
                .addConstructorArgValue(embedded)
                .addConstructorArgValue(leasedBeanNames)
                .getBeanDefinition();
        registryDefinition.setAttribute(IMPORTED_BY_ATTRIBUTE, importingClassMetadata.getClassName());
        registry.registerBeanDefinition(REGISTRY_BEAN_NAME, registryDefinition);
    }

    /** Every lease {@code implClass} declares, with whatever the configuration says about it; none if there is no implementation. */
    private static List<ModularTopologyCatalog.LeaseDeclaration> leaseDeclarations(
            ModularProperties properties, Class<?> implClass) {
        if (implClass == null) {
            return List.of();
        }
        return Arrays.stream(implClass.getAnnotationsByType(RequiresLease.class))
                .map(declared -> new ModularTopologyCatalog.LeaseDeclaration(declared.value(),
                        properties.leaseAmount(declared.value()), properties.leaseCapacity(declared.value())))
                .toList();
    }

    /**
     * The leases an implementation declares that are fully configured (capacity and this service's
     * amount), for the check below; unlike {@link #leasedImplementation} it never throws, since a
     * service this process doesn't host has no obligation to be configured here.
     */
    private static List<LeaseNeed> configuredLeaseNeeds(ModularProperties properties, Class<?> implClass) {
        List<LeaseNeed> needs = new ArrayList<>();
        for (RequiresLease declared : implClass.getAnnotationsByType(RequiresLease.class)) {
            Integer capacity = properties.leaseCapacity(declared.value());
            Integer amount = properties.leaseAmount(declared.value());
            if (capacity != null && amount != null) {
                needs.add(new LeaseNeed(declared.value(), amount, capacity));
            }
        }
        return needs;
    }

    /**
     * A lease's capacity is cluster-wide and every leased service has to be hosted somewhere at least
     * once, so if one instance of each service that declares it claims more than the capacity, no
     * deployment of this jar, monolith or split however finely, can ever host them all. That is
     * known at boot, from the configuration alone, so it's refused at boot, counting every leased
     * implementation on the classpath whether or not this process hosts it.
     */
    private record LeasedBean(String beanName, RootBeanDefinition definition) {
    }

    /**
     * Decides who is refused when a lease can't cover everyone. Leased implementations are constructed
     * eagerly, so left alone the order they claim in is an accident of scanning, and a node short of
     * capacity would refuse whichever version happened to come last. Instead they claim in rounds: the
     * newest version of every service, then the second newest of every service, and so on, so a
     * shortfall lands on the old versions, which are reached remotely. Spring creates a bean's
     * {@code depends-on} first, so each round depends on the one before it.
     */
    private static void claimNewestVersionsFirst(Map<String, Map<Integer, LeasedBean>> leasedVersions) {
        List<LeasedBean> previousRound = List.of();
        for (int rank = 0; ; rank++) {
            List<LeasedBean> round = new ArrayList<>();
            for (Map<Integer, LeasedBean> versions : leasedVersions.values()) {
                if (versions.size() > rank) {
                    round.add(new ArrayList<>(versions.values()).get(rank));
                }
            }
            if (round.isEmpty()) {
                return;
            }
            String[] after = previousRound.stream().map(LeasedBean::beanName).toArray(String[]::new);
            for (LeasedBean bean : round) {
                Set<String> dependsOn = new LinkedHashSet<>(Arrays.asList(bean.definition().getDependsOn()));
                dependsOn.addAll(Arrays.asList(after));
                bean.definition().setDependsOn(dependsOn.toArray(String[]::new));
            }
            previousRound = round;
        }
    }

    private static void requireStaticallyGrantable(Map<String, List<LeaseNeed>> leasedServices) {
        Map<String, List<String>> claimants = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, Integer> capacities = new LinkedHashMap<>();
        leasedServices.forEach((service, needs) -> {
            for (LeaseNeed need : needs) {
                claimants.computeIfAbsent(need.name(), k -> new ArrayList<>()).add(service + " " + need.amount());
                totals.merge(need.name(), need.amount(), Integer::sum);
                capacities.put(need.name(), need.capacity());
            }
        });
        for (Map.Entry<String, Integer> total : totals.entrySet()) {
            String lease = total.getKey();
            if (total.getValue() > capacities.get(lease)) {
                throw new IllegalStateException("Lease '" + lease + "' has capacity " + capacities.get(lease) + ", but one instance of "
                        + "each service that declares it claims " + total.getValue() + " (" + String.join(", ", claimants.get(lease))
                        + "). The capacity is cluster-wide and each of those services has to run somewhere, so no deployment "
                        + "of this jar, however it's split, can host them all. Raise modular.leases." + lease + ".capacity, or "
                        + "lower modular.leases." + lease + ".amount.");
            }
        }
    }

    /**
     * Everything a leased implementation needs, checked now so a mistake fails startup naming the
     * property or parameter to fix: each lease's capacity and this service's amount are configured and
     * the amount fits the capacity, and every {@code Lease} constructor parameter says which lease it is.
     */
    private LeasedImplementation leasedImplementation(ModularProperties properties, Class<?> serviceInterface,
            String name, int version, Class<?> implClass, List<RequiresLease> declaredLeases) {
        String who = name + "@" + version + " (" + implClass.getName() + ")";
        if (properties.getRemoteUrlTemplate() != null) {
            throw new IllegalStateException(who + " declares @RequiresLease, which can't be combined with "
                    + "modular.remote-url-template: the template assumes every process behind the name hosts the service, "
                    + "and a process that is refused a lease doesn't. Route to it with an explicit "
                    + "modular.services." + name + ".url instead.");
        }
        List<LeaseNeed> needs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (RequiresLease declared : declaredLeases) {
            String lease = declared.value();
            if (!ServiceNames.isValidServiceName(lease)) {
                throw new IllegalStateException(who + " declares @RequiresLease(\"" + lease + "\"), but a lease name must be "
                        + "lowercase kebab case ([a-z0-9]+ separated by single '-'): it is a modular.leases.<name> property key.");
            }
            if (!names.add(lease)) {
                throw new IllegalStateException(who + " declares @RequiresLease(\"" + lease + "\") twice.");
            }
            Integer capacity = properties.leaseCapacity(lease);
            if (capacity == null) {
                throw new IllegalStateException(who + " declares @RequiresLease(\"" + lease + "\"), but modular.leases."
                        + lease + ".capacity isn't set: the lease has no cluster-wide capacity to share out.");
            }
            Integer amount = properties.leaseAmount(lease);
            if (amount == null) {
                throw new IllegalStateException(who + " declares @RequiresLease(\"" + lease + "\"), but modular.leases."
                        + lease + ".amount isn't set: how much of the lease does one node claim?");
            }
            if (amount > capacity) {
                throw new IllegalStateException("modular.leases." + lease + ".amount=" + amount
                        + " exceeds modular.leases." + lease + ".capacity=" + capacity + ": " + who + " could never be granted it.");
            }
            needs.add(new LeaseNeed(lease, amount, capacity));
        }

        Map<Integer, String> leaseParameters = new LinkedHashMap<>();
        Constructor<?> constructor;
        try {
            constructor = BeanUtils.getResolvableConstructor(implClass);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(who + " declares @RequiresLease, so it needs one constructor Henge can pass its "
                    + "Lease to: give it a single constructor, or mark one @Autowired.", e);
        }
        Parameter[] parameters = constructor.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getType() != Lease.class) {
                continue;
            }
            RequiresLease marked = parameters[i].getAnnotation(RequiresLease.class);
            String lease = marked != null ? marked.value() : (names.size() == 1 ? names.iterator().next() : null);
            if (lease == null) {
                throw new IllegalStateException(who + " declares several leases " + names + ", so its Lease constructor "
                        + "parameter '" + parameters[i].getName() + "' must say which one with @RequiresLease(\"...\").");
            }
            if (!names.contains(lease)) {
                throw new IllegalStateException(who + ": constructor parameter '" + parameters[i].getName()
                        + "' asks for lease '" + lease + "', which the class doesn't declare (declared: " + names + ").");
            }
            leaseParameters.put(i, lease);
        }

        return new LeasedImplementation(serviceInterface, name, version, implClass, needs, leaseParameters);
    }

    private static void addServiceVersionQualifier(RootBeanDefinition definition, Class<?> serviceInterface, int version) {
        AutowireCandidateQualifier qualifier = new AutowireCandidateQualifier(ServiceVersion.class);
        qualifier.setAttribute("value", serviceInterface);
        qualifier.setAttribute("version", version);
        definition.addQualifier(qualifier);
    }

    private Set<Class<?>> discoverServiceInterfaces(Set<String> basePackages, ClassLoader classLoader) {
        Set<Class<?>> found = new LinkedHashSet<>();
        ServiceInterfaceScanner scanner = new ServiceInterfaceScanner(classLoader);
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                found.add(resolveClass(candidate.getBeanClassName(), classLoader));
            }
        }
        return found;
    }

    private Map<Class<?>, Map<Integer, Class<?>>> discoverServiceVersionImpls(
            Set<String> basePackages, Set<Class<?>> serviceInterfaces, ClassLoader classLoader) {
        Map<Class<?>, Map<Integer, Class<?>>> result = new LinkedHashMap<>();
        ServiceVersionScanner scanner = new ServiceVersionScanner(classLoader);
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                Class<?> implClass = resolveClass(candidate.getBeanClassName(), classLoader);
                ServiceVersion annotation = implClass.getAnnotation(ServiceVersion.class);
                Class<?> serviceInterface = annotation.value();
                int version = annotation.version();

                if (!serviceInterfaces.contains(serviceInterface) && serviceInterface.isAnnotationPresent(ModularService.class)) {
                    throw new IllegalStateException(implClass.getName() + " implements @ModularService " + serviceInterface.getName()
                            + ", but that interface's package isn't scanned -- add \"" + serviceInterface.getPackageName()
                            + "\" to @EnableModularServices' basePackages (scanning: " + basePackages + ").");
                }
                if (!serviceInterfaces.contains(serviceInterface)) {
                    throw new IllegalStateException(implClass.getName() + " is annotated @ServiceVersion("
                            + serviceInterface.getName() + ".class, " + version + ") but " + serviceInterface.getName()
                            + " is not annotated @ModularService");
                }
                if (!serviceInterface.isAssignableFrom(implClass)) {
                    throw new IllegalStateException(implClass.getName() + " is annotated @ServiceVersion("
                            + serviceInterface.getName() + ".class, ...) but does not implement " + serviceInterface.getName());
                }

                // modular-processor rejects this at compile time; repeated for classes compiled without it.
                if (AnnotatedElementUtils.hasAnnotation(implClass, Component.class)) {
                    throw new IllegalStateException(implClass.getName() + " is annotated both @ServiceVersion and a Spring "
                            + "stereotype (@Component, @Service, ...): the framework registers @ServiceVersion implementations "
                            + "itself, so component scanning would register a second, independent instance. Remove the stereotype.");
                }

                Map<Integer, Class<?>> byVersion = result.computeIfAbsent(serviceInterface, k -> new LinkedHashMap<>());
                Class<?> existing = byVersion.putIfAbsent(version, implClass);
                // Overlapping basePackages (e.g. "a" and "a.b") make the scanner find the same class twice.
                if (existing != null && existing != implClass) {
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

    /**
     * The same rule {@code modular-processor} enforces at compile time (see {@link ServiceNames}),
     * repeated here for interfaces compiled without it: both names end up as dispatch-path segments,
     * and a bad one would only fail -- as a 404 -- once the service is split.
     */
    private static void validateNames(Class<?> serviceInterface, String name) {
        if (!ServiceNames.isValidServiceName(name)) {
            throw new IllegalStateException(ServiceNames.invalidServiceNameMessage(name, serviceInterface.getName()));
        }
        for (Method method : serviceInterface.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue; // not an operation; see ModularServiceDescriptor
            }
            String rpcName = ModularServiceDescriptor.rpcName(method);
            if (!ServiceNames.isValidMethodName(rpcName)) {
                throw new IllegalStateException(ServiceNames.invalidMethodNameMessage(rpcName, serviceInterface.getName()));
            }
        }
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

        ServiceInterfaceScanner(ClassLoader classLoader) {
            super(false);
            setResourceLoader(new DefaultResourceLoader(classLoader));
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

        ServiceVersionScanner(ClassLoader classLoader) {
            super(false);
            setResourceLoader(new DefaultResourceLoader(classLoader));
            addIncludeFilter(new AnnotationTypeFilter(ServiceVersion.class));
        }
    }
}
