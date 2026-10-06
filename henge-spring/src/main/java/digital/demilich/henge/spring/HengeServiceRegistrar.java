package digital.demilich.henge.spring;

import digital.demilich.henge.core.Lease;
import digital.demilich.henge.core.LeasedResource;
import digital.demilich.henge.core.HengeService;
import digital.demilich.henge.core.RateLimit;
import digital.demilich.henge.core.RateLimited;
import digital.demilich.henge.core.RateLimiter;
import digital.demilich.henge.core.RequiresLease;
import digital.demilich.henge.core.ResourceProvider;
import digital.demilich.henge.core.RunOnEveryNode;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Discovers {@code @HengeService} interfaces and their {@code @ServiceVersion}-annotated
 * implementations on startup, and for every (interface, version) pair in play — whether declared
 * by a local impl class, by explicit {@code henge.services.<name>.versions.<version>.*} config,
 * or implicitly via the interface's {@code defaultVersion()} — registers exactly one bean, a
 * {@link HengeServiceBindingFactoryBean} whose product is a dynamic proxy over that version's
 * {@link ServiceBinding}. What the binding calls depends on the mode:
 *
 * <ul>
 *   <li><b>embedded</b> (default) — the local {@code @ServiceVersion}-annotated implementation
 *       class, which the factory bean constructs as a hidden bean of its own (impls are no longer
 *       picked up by plain {@code @ComponentScan} at all, since {@code @ServiceVersion} carries no
 *       {@code @Component} meta-annotation). Fails fast if no local implementation exists for a
 *       version configured/expected as embedded.</li>
 *   <li><b>internal-rest</b> — the transport, which dispatches calls over HTTP.</li>
 * </ul>
 *
 * Every registered bean carries {@code @ServiceVersion} qualifier metadata (so a dependency can
 * pin a specific version via the same annotation on its injection point) and, for whichever
 * version matches the interface's {@code defaultVersion()}, is marked {@code @Primary} (so a
 * dependency with no qualifier at all resolves there — the zero-ceremony common case).
 *
 * <p>Runs as an {@link ImportBeanDefinitionRegistrar} imported by {@link EnableHengeServices}.
 * {@code @Import}-triggered registrars run after the importing class's own {@code @ComponentScan}
 * (Spring always processes a {@code @Configuration} class's {@code @ComponentScan} before its
 * {@code @Import}s), so plain component-scanned beans this process also defines are already
 * present here — though Henge service implementations themselves are registered by this class,
 * not by {@code @ComponentScan}.
 */
class HengeServiceRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private static final String REGISTRY_BEAN_NAME = "hengeServiceRegistry";
    private static final String DATASTORE_INSTALLER_BEAN_NAME = "hengeDatastoreInstaller";
    private static final String ADVERTISER_BEAN_NAME = "hengeServiceAdvertiser";
    private static final String DATASOURCE_GUARD_BEAN_NAME = "hengeDataSourceGuard";
    private static final String SCHEDULED_JOBS_BEAN_NAME = "hengeScheduledJobs";
    private static final String TOPOLOGY_CATALOG_BEAN_NAME = "hengeTopologyCatalog";
    static final String LEASE_KEEPER_BEAN_NAME = "hengeLeaseKeeper";
    static final String TRUNK_BEAN_NAME = "hengeTrunkConfiguration";
    private static final String IMPORTED_BY_ATTRIBUTE = HengeServiceRegistrar.class.getName() + ".importedBy";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        // Each run registers its own registry of embedded services for the dispatcher; a second one
        // would replace the first (silently in plain Spring, where /_henge then 404s the first
        // run's services) or fail on the duplicate bean name (Boot) without saying why.
        if (registry.containsBeanDefinition(REGISTRY_BEAN_NAME)) {
            Object firstImporter = registry.getBeanDefinition(REGISTRY_BEAN_NAME).getAttribute(IMPORTED_BY_ATTRIBUTE);
            throw new IllegalStateException("@EnableHengeServices is declared on both " + firstImporter + " and "
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
                : HengeServiceRegistrar.class.getClassLoader();

        Set<String> basePackages = resolveBasePackages(importingClassMetadata);
        Set<Class<?>> serviceInterfaces = discoverServiceInterfaces(basePackages, classLoader);
        Map<Class<?>, Map<Integer, Class<?>>> localImpls = discoverServiceVersionImpls(basePackages, serviceInterfaces, classLoader);
        Map<String, ProviderDeclaration> providers = discoverLeasedResources(basePackages, classLoader);
        HengeProperties properties = new HengeProperties(environment);
        Map<String, RateLimit> rateLimits = properties.rateLimits();
        ServeSpec serveSpec = ServeSpec.parse(properties.getServe());
        int recentVersions = properties.getRecentVersions();

        List<HengeServiceDescriptor> embedded = new ArrayList<>();
        List<HengeTopologyCatalog.Entry> catalogEntries = new ArrayList<>();
        List<String> serviceBeanNames = new ArrayList<>();
        /** The versions this process reaches over the network: what makes it one of several processes. */
        List<String> remoteServices = new ArrayList<>();
        boolean anyLeased = false;
        Set<String> declaredLeaseNames = new LinkedHashSet<>(providers.keySet());
        Set<String> providersInUse = new LinkedHashSet<>();
        Map<String, Class<?>> namesToInterfaces = new LinkedHashMap<>();

        for (Class<?> serviceInterface : serviceInterfaces) {
            HengeService annotation = serviceInterface.getAnnotation(HengeService.class);
            String name = ServiceNames.serviceName(annotation.name(), serviceInterface.getSimpleName());
            validateNames(serviceInterface, name);

            Class<?> existingOwner = namesToInterfaces.putIfAbsent(name, serviceInterface);
            if (existingOwner != null) {
                throw new IllegalStateException("Two @HengeService interfaces resolve to the same service name '"
                        + name + "': " + existingOwner.getName() + " and " + serviceInterface.getName()
                        + " -- disambiguate with @HengeService(name = ...) on one of them.");
            }

            int defaultVersion = annotation.defaultVersion();

            Map<Integer, Class<?>> implsByVersion = localImpls.getOrDefault(serviceInterface, Map.of());
            HengeProperties.ServiceConfig config = properties.service(name);

            Set<Integer> versions = new LinkedHashSet<>(implsByVersion.keySet());
            versions.addAll(config.explicitVersions());
            versions.addAll(serveSpec.versionsFor(name));
            versions.add(defaultVersion);

            Set<Integer> named = new LinkedHashSet<>(config.explicitVersions());
            named.addAll(serveSpec.versionsFor(name));
            named.add(defaultVersion);
            versions = recentVersions(name, versions, named, recentVersions);

            for (int version : versions) {
                String qualifiedName = name + "@" + version;
                String explicitMode = config.resolveMode(version);
                boolean servedHere = serveSpec.isEmpty() || serveSpec.matches(name, version);
                HengeTopologyCatalog.ModeSource modeSource = explicitMode != null ? HengeTopologyCatalog.ModeSource.EXPLICIT
                        : !serveSpec.isEmpty() ? HengeTopologyCatalog.ModeSource.SERVE : HengeTopologyCatalog.ModeSource.DEFAULT;
                HengeMode mode = HengeMode.parse(explicitMode != null ? explicitMode : (servedHere ? "embedded" : "internal-rest"), qualifiedName);

                if (explicitMode != null && mode == HengeMode.INTERNAL_REST && serveSpec.matches(name, version)) {
                    throw new IllegalStateException("Henge service '" + qualifiedName + "' is listed in --henge.serve "
                            + "(meaning it should be embedded in this process) but its configured mode (under "
                            + "henge.services." + name + ", possibly per-version) is explicitly internal-rest -- "
                            + "remove it from --henge.serve, or drop the explicit mode override.");
                }

                Class<?> implClass = implsByVersion.get(version);
                if (serviceInterface.isAnnotationPresent(RunOnEveryNode.class)) {
                    refuseRemoteRunOnEveryNode(serviceInterface, qualifiedName, mode, implClass, serveSpec);
                }
                // Derived from the resolved service name (not the interface's raw simple name) so
                // that two interfaces with the same simple name in different packages -- already
                // rejected above unless disambiguated via @HengeService(name = ...) -- get
                // distinct bean names too, once disambiguated.
                String beanName = name + "-" + version;
                boolean isDefault = version == defaultVersion;

                ServiceBindingSpec bindingSpec;
                if (mode == HengeMode.EMBEDDED) {
                    if (implClass == null) {
                        throw new IllegalStateException("Henge service '" + name + "' version '" + version
                                + "' is configured as embedded (the default) but no @ServiceVersion(" + serviceInterface.getSimpleName()
                                + ".class, " + version + ") implementation was found on the classpath. Either add one, or set "
                                + "henge.services." + name + ".versions." + version + ".mode=internal-rest with a matching "
                                + ".url pointing at the process that hosts it.");
                    }
                    checkRateLimits(implClass, rateLimits.keySet());
                    if (!declaresLeases(implClass)) {
                        bindingSpec = ServiceBindingSpec.embedded(serviceInterface, name, version, implClass);
                    } else {
                        LeasedImplementation leased =
                                leasedImplementation(properties, serviceInterface, name, version, implClass, providers);
                        bindingSpec = ServiceBindingSpec.leased(leased);
                        anyLeased = true;
                        leased.needs().forEach(need -> declaredLeaseNames.add(need.name()));
                        providersInUse.addAll(leased.resourceParameters().values());
                    }
                    embedded.add(HengeServiceDescriptor.of(name, version, serviceInterface, beanName));
                } else {
                    bindingSpec = ServiceBindingSpec.remote(serviceInterface, name, version);
                    remoteServices.add(qualifiedName);
                }

                RootBeanDefinition definition = new RootBeanDefinition(HengeServiceBindingFactoryBean.class);
                definition.getConstructorArgumentValues().addIndexedArgumentValue(0, bindingSpec);
                if (bindingSpec.leased() != null) {
                    definition.setDependsOn(LEASE_KEEPER_BEAN_NAME);
                }

                catalogEntries.add(new HengeTopologyCatalog.Entry(name, version, serviceInterface.getName(), isDefault, mode,
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
            throw new IllegalStateException("Unrecognized henge.services configuration -- nothing reads these, so the "
                    + "service(s) they meant to configure would silently keep their defaults:\n  "
                    + String.join("\n  ", unknownProperties));
        }


        List<String> unknownLeaseProperties = properties.unknownLeaseProperties(declaredLeaseNames);
        if (!unknownLeaseProperties.isEmpty()) {
            throw new IllegalStateException("Unrecognized henge.leases configuration -- nothing reads these:\n  "
                    + String.join("\n  ", unknownLeaseProperties));
        }

        // A name that matches no discovered interface (almost always a typo) would otherwise leave
        // every service in this process as an internal-rest proxy, silently hosting nothing.
        Set<String> unknownServeNames = new LinkedHashSet<>(serveSpec.names());
        unknownServeNames.removeAll(namesToInterfaces.keySet());
        if (!unknownServeNames.isEmpty()) {
            throw new IllegalStateException("henge.serve names " + unknownServeNames + " but no @HengeService with "
                    + (unknownServeNames.size() == 1 ? "that name was" : "those names were") + " found; discovered services: "
                    + namesToInterfaces.keySet());
        }

        // Spring instantiates a bean-factory post-processor before everything else, so this one is
        // given plain values only; it runs once every bean definition, ours and the application's, exists.
        registry.registerBeanDefinition(DATASTORE_INSTALLER_BEAN_NAME, BeanDefinitionBuilder
                .genericBeanDefinition(HengeDatastoreInstaller.class)
                .addConstructorArgValue(serviceBeanNames)
                .addConstructorArgValue(remoteServices)
                .getBeanDefinition());

        // Refuses a DataSource bean, whose pool opens on every process; the one that leases build is no bean.
        registry.registerBeanDefinition(DATASOURCE_GUARD_BEAN_NAME, BeanDefinitionBuilder
                .genericBeanDefinition(HengeDataSourceGuard.class)
                .setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
                .getBeanDefinition());

        // Finds @HengeScheduled methods on the application's beans; an infrastructure bean, like Spring's own
        // scheduling post-processor, so it is not offered for auto-proxying or injection.
        registry.registerBeanDefinition(SCHEDULED_JOBS_BEAN_NAME, BeanDefinitionBuilder
                .genericBeanDefinition(HengeScheduledJobs.class)
                .setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
                .getBeanDefinition());

        for (String lease : providersInUse) {
            // Built through the bean factory like an implementation, so it can take what it needs, but
            // never an autowire candidate: the resource it makes is what a service asks for.
            RootBeanDefinition provider = new RootBeanDefinition(providers.get(lease).providerClass());
            provider.setAutowireCandidate(false);
            registry.registerBeanDefinition(HengeLeaseKeeper.providerBeanName(lease), provider);
        }

        if (!rateLimits.isEmpty()) {
            // The datastore (constructor argument 0) is autowired; only the TTL is given.
            BeanDefinition subscriptions = BeanDefinitionBuilder.genericBeanDefinition(RateLimitSubscriptions.class).getBeanDefinition();
            subscriptions.getConstructorArgumentValues().addIndexedArgumentValue(1, RateLimitSubscriptions.DEFAULT_TTL);
            registry.registerBeanDefinition("hengeRateLimitSubscriptions", subscriptions);
        }
        rateLimits.forEach((name, limit) -> {
            // The datastore, the subscriptions and the metrics (arguments 2 to 4) are autowired; the qualifier is what
            // @RateLimited(name) at an injection point matches.
            RootBeanDefinition limiter = new RootBeanDefinition(RateLimiters.class);
            limiter.setFactoryMethodName("create");
            limiter.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            limiter.setTargetType(RateLimiter.class);
            limiter.getConstructorArgumentValues().addIndexedArgumentValue(0, name);
            limiter.getConstructorArgumentValues().addIndexedArgumentValue(1, limit);
            limiter.addQualifier(new AutowireCandidateQualifier(RateLimited.class, name));
            registry.registerBeanDefinition("hengeRateLimiter." + name, limiter);
        });

        if (anyLeased) {
            // The datastore (constructor argument 0) is autowired; only the TTL is given.
            BeanDefinition keeper = BeanDefinitionBuilder.genericBeanDefinition(HengeLeaseKeeper.class).getBeanDefinition();
            keeper.getConstructorArgumentValues().addIndexedArgumentValue(1, HengeLeaseKeeper.DEFAULT_TTL);
            registry.registerBeanDefinition(LEASE_KEEPER_BEAN_NAME, keeper);
        }

        // The datastore and the registry (arguments 0 and 1) are autowired.
        BeanDefinition advertiser = BeanDefinitionBuilder.genericBeanDefinition(HengeServiceAdvertiser.class).getBeanDefinition();
        advertiser.getConstructorArgumentValues().addIndexedArgumentValue(2, properties.getAdvertiseUrl(), String.class.getName());
        advertiser.getConstructorArgumentValues().addIndexedArgumentValue(3, HengeLeaseKeeper.DEFAULT_TTL);
        registry.registerBeanDefinition(ADVERTISER_BEAN_NAME, advertiser);

        registry.registerBeanDefinition(TOPOLOGY_CATALOG_BEAN_NAME, BeanDefinitionBuilder
                .genericBeanDefinition(HengeTopologyCatalog.class)
                .addConstructorArgValue(catalogEntries)
                .getBeanDefinition());

        BeanDefinition registryDefinition = BeanDefinitionBuilder.genericBeanDefinition(HengeServiceRegistry.class)
                .addConstructorArgValue(embedded)
                .getBeanDefinition();
        registryDefinition.setAttribute(IMPORTED_BY_ATTRIBUTE, importingClassMetadata.getClassName());
        registry.registerBeanDefinition(REGISTRY_BEAN_NAME, registryDefinition);

        // The trunk frontends open channels over, for a node that can host a channel method.
        boolean hostsChannels = embedded.stream()
                .anyMatch(descriptor -> descriptor.methods().values().stream().anyMatch(HengeServiceDescriptor::isChannelMethod));
        if (hostsChannels && ClassUtils.isPresent("org.springframework.web.socket.config.annotation.WebSocketConfigurer",
                getClass().getClassLoader())) {
            registry.registerBeanDefinition(TRUNK_BEAN_NAME, new RootBeanDefinition(HengeTrunkConfiguration.class));
        }
    }

    /** A {@link RunOnEveryNode} service reached over the network, or hosted only if a lease is granted, isn't on every node. */
    private static void refuseRemoteRunOnEveryNode(Class<?> serviceInterface, String qualifiedName, HengeMode mode,
            Class<?> implClass, ServeSpec serveSpec) {
        String what = serviceInterface.getSimpleName() + " is @RunOnEveryNode, so every process must host "
                + qualifiedName + ", but ";
        if (mode != HengeMode.EMBEDDED) {
            throw new IllegalStateException(what + "this process would reach it over the network: henge.serve is set "
                    + "and doesn't list it, or henge.services configures it internal-rest. List it in henge.serve and "
                    + "drop the internal-rest mode, or remove @RunOnEveryNode if it needn't be everywhere.");
        }
        if (implClass != null && declaresLeases(implClass)) {
            throw new IllegalStateException(what + "its implementation " + implClass.getSimpleName()
                    + " needs a @RequiresLease, and a process refused the lease would reach it remotely. Remove the "
                    + "lease from it, or remove @RunOnEveryNode.");
        }
    }

    /**
     * The {@code limit} highest of {@code versions}: an implementation older than that is left on the
     * classpath but not run. A version the configuration, {@code henge.serve} or the interface's
     * {@code defaultVersion()} names ({@code named}) can't be quietly dropped, so one outside the window
     * fails startup naming the property that widens it.
     */
    static Set<Integer> recentVersions(String name, Set<Integer> versions, Set<Integer> named, int limit) {
        Set<Integer> recent = new LinkedHashSet<>(versions.stream()
                .sorted(Comparator.reverseOrder())
                .limit(limit)
                .toList());
        for (int version : named) {
            if (!recent.contains(version)) {
                throw new IllegalStateException("Henge service '" + name + "' version " + version + " is named by its "
                        + "configuration, --henge.serve or defaultVersion(), but only the " + limit + " most recent versions "
                        + "run (" + recent + "). Raise henge.recent-versions, or drop the version.");
            }
        }
        return recent;
    }

    /** Every lease {@code implClass} declares, with whatever the configuration says about it; none if there is no implementation. */
    private static List<HengeTopologyCatalog.LeaseDeclaration> leaseDeclarations(
            HengeProperties properties, Class<?> implClass) {
        if (implClass == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Constructor<?> constructor : implClass.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                RequiresLease declared = parameter.getAnnotation(RequiresLease.class);
                if (declared != null) {
                    names.add(declared.value());
                }
            }
        }
        return names.stream()
                .map(lease -> new HengeTopologyCatalog.LeaseDeclaration(lease, properties.leaseAmount(lease), properties.leaseCapacity(lease)))
                .toList();
    }

    /** Whether any constructor of {@code implClass} takes a leased parameter. */
    /**
     * A service that asks for a limiter nobody configured would otherwise fail on Spring's generic "no
     * qualifying bean"; this says which properties make it. Other beans can take a limiter too, but only a
     * service's constructor is known here.
     */
    private static void checkRateLimits(Class<?> implClass, Set<String> configured) {
        for (Constructor<?> constructor : implClass.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                RateLimited rateLimited = parameter.getAnnotation(RateLimited.class);
                if (rateLimited != null && !configured.contains(rateLimited.value())) {
                    String prefix = "henge.rate-limits." + rateLimited.value();
                    throw new IllegalStateException(implClass.getName() + " takes @RateLimited(\"" + rateLimited.value()
                            + "\"), which isn't configured: set " + prefix + ".permits and " + prefix + ".period"
                            + (configured.isEmpty() ? "" : " (configured: " + configured + ")"));
                }
            }
        }
    }

    private static boolean declaresLeases(Class<?> implClass) {
        for (Constructor<?> constructor : implClass.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                if (parameter.isAnnotationPresent(RequiresLease.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A {@link ResourceProvider} found by scanning: the lease it builds the resource of, and the type of that resource. */
    private record ProviderDeclaration(String lease, Class<?> providerClass, Class<?> resourceType) {
    }

    /**
     * Everything a leased implementation needs, checked now so a mistake fails startup naming the
     * property or parameter to fix: each lease's capacity and amount are configured and the amount
     * fits the capacity, a {@code Lease} parameter says which lease it is, and a parameter that asks
     * for a resource has a provider whose resource fits it.
     */
    private LeasedImplementation leasedImplementation(HengeProperties properties, Class<?> serviceInterface,
            String name, int version, Class<?> implClass, Map<String, ProviderDeclaration> providers) {
        String who = name + "@" + version + " (" + implClass.getName() + ")";
        if (properties.getRemoteUrlTemplate() != null) {
            throw new IllegalStateException(who + " declares @RequiresLease, which can't be combined with "
                    + "henge.remote-url-template: the template assumes every process behind the name hosts the service, "
                    + "and a process that is refused a lease doesn't. Route to it with an explicit "
                    + "henge.services." + name + ".url instead.");
        }
        Constructor<?> constructor;
        try {
            constructor = BeanUtils.getResolvableConstructor(implClass);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(who + " declares @RequiresLease, so it needs one constructor Henge can pass its "
                    + "leases to: give it a single constructor, or mark one @Autowired.", e);
        }

        Map<String, LeaseNeed> needs = new LinkedHashMap<>();
        Map<Integer, String> leaseParameters = new LinkedHashMap<>();
        Map<Integer, String> resourceParameters = new LinkedHashMap<>();
        Parameter[] parameters = constructor.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            RequiresLease marked = parameter.getAnnotation(RequiresLease.class);
            if (marked == null) {
                if (parameter.getType() == Lease.class) {
                    throw new IllegalStateException(who + ": constructor parameter '" + parameter.getName() + "' is a Lease, so it "
                            + "must say which one with @RequiresLease(\"...\").");
                }
                continue;
            }
            String lease = marked.value();
            if (!ServiceNames.isValidServiceName(lease)) {
                throw new IllegalStateException(who + ": parameter '" + parameter.getName() + "' declares @RequiresLease(\"" + lease
                        + "\"), but a lease name must be lowercase kebab case ([a-z0-9]+ separated by single '-'): it is a "
                        + "henge.leases.<name> property key.");
            }
            if (!needs.containsKey(lease)) {
                needs.put(lease, leaseNeed(properties, who, lease));
            }
            if (parameter.getType() == Lease.class) {
                leaseParameters.put(i, lease);
                continue;
            }
            ProviderDeclaration provider = providers.get(lease);
            if (provider == null) {
                throw new IllegalStateException(who + ": parameter '" + parameter.getName() + "' asks for the resource of lease '"
                        + lease + "', but no @LeasedResource(\"" + lease + "\") provider was found. Add one, or take a Lease "
                        + "parameter instead and build the resource yourself.");
            }
            if (!ClassUtils.isAssignable(parameter.getType(), provider.resourceType())) {
                throw new IllegalStateException(who + ": parameter '" + parameter.getName() + "' is a "
                        + parameter.getType().getName() + ", but the provider of lease '" + lease + "' ("
                        + provider.providerClass().getName() + ") makes a " + provider.resourceType().getName() + ".");
            }
            resourceParameters.put(i, lease);
        }

        return new LeasedImplementation(serviceInterface, name, version, implClass, List.copyOf(needs.values()),
                leaseParameters, resourceParameters);
    }

    private static LeaseNeed leaseNeed(HengeProperties properties, String who, String lease) {
        Integer capacity = properties.leaseCapacity(lease);
        if (capacity == null) {
            throw new IllegalStateException(who + " declares @RequiresLease(\"" + lease + "\"), but henge.leases."
                    + lease + ".capacity isn't set: the lease has no cluster-wide capacity to share out.");
        }
        Integer amount = properties.leaseAmount(lease);
        if (amount == null) {
            throw new IllegalStateException(who + " declares @RequiresLease(\"" + lease + "\"), but henge.leases."
                    + lease + ".amount isn't set: how much of the lease does one node claim?");
        }
        if (amount > capacity) {
            throw new IllegalStateException("henge.leases." + lease + ".amount=" + amount
                    + " exceeds henge.leases." + lease + ".capacity=" + capacity + ": " + who + " could never be granted it.");
        }
        return new LeaseNeed(lease, amount, capacity);
    }

    /** Every {@code @LeasedResource} provider in the scanned packages, by the lease it builds the resource of. */
    private Map<String, ProviderDeclaration> discoverLeasedResources(Set<String> basePackages, ClassLoader classLoader) {
        Map<String, ProviderDeclaration> found = new LinkedHashMap<>();
        LeasedResourceScanner scanner = new LeasedResourceScanner(classLoader);
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                Class<?> providerClass = resolveClass(candidate.getBeanClassName(), classLoader);
                String lease = providerClass.getAnnotation(LeasedResource.class).value();
                if (!ResourceProvider.class.isAssignableFrom(providerClass)) {
                    throw new IllegalStateException(providerClass.getName() + " is annotated @LeasedResource(\"" + lease
                            + "\") but does not implement ResourceProvider.");
                }
                if (!ServiceNames.isValidServiceName(lease)) {
                    throw new IllegalStateException(providerClass.getName() + " is annotated @LeasedResource(\"" + lease
                            + "\"), but a lease name must be lowercase kebab case ([a-z0-9]+ separated by single '-'): it is a "
                            + "henge.leases.<name> property key.");
                }
                Class<?> resourceType = ResolvableType.forClass(providerClass).as(ResourceProvider.class).getGeneric(0).resolve();
                if (resourceType == null) {
                    throw new IllegalStateException(providerClass.getName() + " is a ResourceProvider whose resource type can't be "
                            + "told: implement ResourceProvider<SomeType> with a concrete type.");
                }
                ProviderDeclaration existing = found.put(lease, new ProviderDeclaration(lease, providerClass, resourceType));
                if (existing != null) {
                    throw new IllegalStateException("Two providers for lease '" + lease + "': " + existing.providerClass().getName()
                            + " and " + providerClass.getName() + " -- a lease has at most one @LeasedResource.");
                }
            }
        }
        return found;
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

                if (!serviceInterfaces.contains(serviceInterface) && serviceInterface.isAnnotationPresent(HengeService.class)) {
                    throw new IllegalStateException(implClass.getName() + " implements @HengeService " + serviceInterface.getName()
                            + ", but that interface's package isn't scanned -- add \"" + serviceInterface.getPackageName()
                            + "\" to @EnableHengeServices' basePackages (scanning: " + basePackages + ").");
                }
                if (!serviceInterfaces.contains(serviceInterface)) {
                    throw new IllegalStateException(implClass.getName() + " is annotated @ServiceVersion("
                            + serviceInterface.getName() + ".class, " + version + ") but " + serviceInterface.getName()
                            + " is not annotated @HengeService");
                }
                if (!serviceInterface.isAssignableFrom(implClass)) {
                    throw new IllegalStateException(implClass.getName() + " is annotated @ServiceVersion("
                            + serviceInterface.getName() + ".class, ...) but does not implement " + serviceInterface.getName());
                }

                // henge-processor rejects this at compile time; repeated for classes compiled without it.
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
                importingClassMetadata.getAnnotationAttributes(EnableHengeServices.class.getName());

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
     * The same rule {@code henge-processor} enforces at compile time (see {@link ServiceNames}),
     * repeated here for interfaces compiled without it: both names end up as dispatch-path segments,
     * and a bad one would only fail -- as a 404 -- once the service is split.
     */
    private static void validateNames(Class<?> serviceInterface, String name) {
        if (!ServiceNames.isValidServiceName(name)) {
            throw new IllegalStateException(ServiceNames.invalidServiceNameMessage(name, serviceInterface.getName()));
        }
        for (Method method : serviceInterface.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue; // not an operation; see HengeServiceDescriptor
            }
            String rpcName = HengeServiceDescriptor.rpcName(method);
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
            throw new IllegalStateException("Henge service discovery found '" + className + "' by classpath scanning "
                    + "but failed to load it (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "). This should "
                    + "never happen for a class the scanner itself just found; check for a classloader mismatch (e.g. "
                    + "Spring Boot DevTools' restart classloader) or a missing/incompatible dependency.", e);
        }
    }

    /**
     * By default {@link ClassPathScanningCandidateComponentProvider} only accepts concrete,
     * independent classes (since it's normally used to find beans to instantiate). We're
     * scanning for the interfaces themselves, so this override widens candidacy to independent
     * interfaces annotated with {@code @HengeService} — the same technique Spring Cloud
     * OpenFeign uses to find {@code @FeignClient} interfaces.
     */
    private static final class ServiceInterfaceScanner extends ClassPathScanningCandidateComponentProvider {

        ServiceInterfaceScanner(ClassLoader classLoader) {
            super(false);
            setResourceLoader(new DefaultResourceLoader(classLoader));
            addIncludeFilter(new AnnotationTypeFilter(HengeService.class));
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

    /** Finds {@link LeasedResource} providers: concrete classes, which is what a provider is. */
    private static final class LeasedResourceScanner extends ClassPathScanningCandidateComponentProvider {

        LeasedResourceScanner(ClassLoader classLoader) {
            super(false);
            setResourceLoader(new DefaultResourceLoader(classLoader));
            addIncludeFilter(new AnnotationTypeFilter(LeasedResource.class));
        }
    }
}
