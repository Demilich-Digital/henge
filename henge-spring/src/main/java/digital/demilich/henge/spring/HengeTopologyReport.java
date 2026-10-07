package digital.demilich.henge.spring;

import digital.demilich.henge.core.SystemEphemeralDatastore;
import digital.demilich.henge.core.SystemEphemeralDatastore.MemberId;
import digital.demilich.henge.spring.HengeTopologyCatalog.Entry;
import digital.demilich.henge.spring.HengeTopologyCatalog.LeaseDeclaration;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * What this process knows about its own topology, and what the shared datastore says about everyone
 * else's: the answer {@link HengeTopologyController} serves.
 *
 * <ul>
 *   <li>For every service version: how it was configured and why, whether it is hosted here, and if not
 *       where a call would go ({@link Route}).</li>
 *   <li>The dependency graph of this process: which beans (services or the application's own) inject
 *       which service version, read from the bean factory, so it is what was actually wired.</li>
 *   <li>The datastore as it is now, read fresh rather than from the routing table: who advertises each
 *       service version, and who holds each lease.</li>
 * </ul>
 *
 * A datastore that can't be read doesn't fail the report: the parts that need it come back empty and
 * {@link Store#error()} says why.
 */
class HengeTopologyReport {

    /** Where a service version is, as far as this process is concerned. */
    enum State {
        /** Its implementation is embedded here and {@code /_henge} serves it. */
        HOSTED,
        /** Configured {@code internal-rest}: called wherever it is. */
        REMOTE,
        /** Embedded by configuration, but its lease isn't this process's (refused at start, or given up since), so it is called remotely. */
        LEASE_REFUSED;

        /** As the JSON writes it: {@code hosted}, {@code remote}, {@code lease-refused}. */
        @JsonValue
        String wire() {
            return wireName(this);
        }
    }

    /** How a remote call finds its host: an explicit url, the template, or the advertisements. */
    enum RouteSource {
        CONFIGURED_URL, TEMPLATE, ADVERTISED, NONE;

        /** As the JSON writes it: {@code configured-url}, {@code template}, {@code advertised}, {@code none}. */
        @JsonValue
        String wire() {
            return wireName(this);
        }
    }

    private static String wireName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    record Route(RouteSource source, List<String> urls) {
    }

    record Advertiser(String node, String url, boolean self) {
    }

    record Lease(String name, Integer amount, Integer capacity) {
    }

    record Service(
            String name,
            int version,
            String id,
            String interfaceName,
            boolean defaultVersion,
            String implementation,
            String mode,
            HengeTopologyCatalog.ModeSource modeSource,
            State state,
            Route route,
            List<Lease> leases,
            List<Advertiser> advertisedBy) {
    }

    /** One of the application's own beans that injects a service version; {@code id} is the {@code bean:}-prefixed bean name. */
    record Consumer(String id, String type) {
    }

    record Dependency(String from, String to, boolean remote) {
    }

    /** One claim on a lease: {@code amount} is what it holds, whether or not it is being given up, which is {@code leaving}. */
    record Holder(String node, int amount, boolean leaving, boolean self) {
    }

    record LeaseStatus(String name, Integer capacity, int claimed, List<Holder> holders) {
    }

    record Node(String id, String advertiseUrl, String pathPrefix, List<String> serve, String remoteUrlTemplate, String storeType) {
    }

    record Store(boolean readable, String error) {
    }

    record Report(
            String generatedAt,
            Node node,
            Store store,
            List<Service> services,
            List<Consumer> consumers,
            List<Dependency> dependencies,
            List<LeaseStatus> leases) {
    }

    private final ApplicationContext applicationContext;
    private final HengeTopologyCatalog catalog;
    private final HengeServiceRegistry registry;
    private final SystemEphemeralDatastore datastore;
    private final HengeProperties properties;
    private final Environment environment;

    /** @param datastore {@code null} in a process that has none (it then reports nothing from the store) */
    HengeTopologyReport(ApplicationContext applicationContext, HengeTopologyCatalog catalog, HengeServiceRegistry registry,
            SystemEphemeralDatastore datastore, HengeProperties properties, Environment environment) {
        this.applicationContext = applicationContext;
        this.catalog = catalog;
        this.registry = registry;
        this.datastore = datastore;
        this.properties = properties;
        this.environment = environment;
    }

    Report report() {
        String error = null;
        Map<String, List<Advertiser>> advertisers = new LinkedHashMap<>();
        Map<String, List<Holder>> holders = new LinkedHashMap<>();
        Set<String> leaseNames = new LinkedHashSet<>();
        catalog.entries().forEach(entry -> entry.leases().forEach(lease -> leaseNames.add(lease.lease())));

        if (datastore != null) {
            try {
                for (Entry entry : catalog.entries()) {
                    advertisers.put(id(entry), advertisers(entry));
                }
                for (String lease : leaseNames) {
                    holders.put(lease, holders(lease));
                }
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                advertisers.clear();
                holders.clear();
            }
        }

        List<Service> services = new ArrayList<>();
        for (Entry entry : catalog.entries()) {
            services.add(service(entry, advertisers.getOrDefault(id(entry), List.of())));
        }
        services.sort(Comparator.comparing(Service::name).thenComparingInt(Service::version));

        Map<String, Service> byId = new LinkedHashMap<>();
        services.forEach(service -> byId.put(service.id(), service));
        List<Consumer> consumers = new ArrayList<>();
        List<Dependency> dependencies = dependencies(byId, consumers);

        List<LeaseStatus> leases = new ArrayList<>();
        for (String lease : new java.util.TreeSet<>(leaseNames)) {
            List<Holder> held = holders.getOrDefault(lease, List.of());
            leases.add(new LeaseStatus(lease, properties.leaseCapacity(lease), held.stream().mapToInt(Holder::amount).sum(), held));
        }

        String nodeId = datastore == null ? null : datastore.nodeId();
        Node node = new Node(nodeId, properties.getAdvertiseUrl(), properties.getServerPathPrefix(), properties.getServe(),
                properties.getRemoteUrlTemplate(), storeType());
        return new Report(Instant.now().toString(), node, new Store(datastore != null && error == null, error),
                services, consumers, dependencies, leases);
    }

    private static String id(Entry entry) {
        return entry.name() + "@" + entry.version();
    }

    private String storeType() {
        String type = environment.getProperty("henge.store.type");
        return type == null || type.isBlank() ? HengeDatastoreInstaller.IN_PROCESS : type.trim();
    }

    private Service service(Entry entry, List<Advertiser> advertisedBy) {
        boolean leased = !entry.leases().isEmpty();
        State state;
        if (entry.mode() == HengeMode.INTERNAL_REST) {
            state = State.REMOTE;
        } else if (leased && registry.find(entry.name(), entry.version()).isEmpty()) {
            state = State.LEASE_REFUSED;
        } else {
            state = State.HOSTED;
        }
        Route route = state == State.HOSTED ? null : route(entry, advertisedBy);
        List<Lease> leases = entry.leases().stream()
                .map(declared -> new Lease(declared.lease(), declared.amount(), declared.capacity()))
                .toList();
        return new Service(entry.name(), entry.version(), id(entry), entry.interfaceName(), entry.defaultVersion(),
                entry.implClass(), wireName(entry.mode()), entry.modeSource(), state, route, leases, advertisedBy);
    }

    /** The same order as a call's: an explicit url, else the template, else whoever advertises it. */
    private Route route(Entry entry, List<Advertiser> advertisedBy) {
        if (entry.configuredUrl() != null && !entry.configuredUrl().isBlank()) {
            return new Route(RouteSource.CONFIGURED_URL, List.of(entry.configuredUrl()));
        }
        String templated = InternalRestTransport.expandTemplate(properties.getRemoteUrlTemplate(), entry.name(), entry.version());
        if (templated != null && !templated.isBlank()) {
            return new Route(RouteSource.TEMPLATE, List.of(templated));
        }
        List<String> urls = advertisedBy.stream().map(Advertiser::url).filter(url -> url != null && !url.isBlank()).distinct().toList();
        return urls.isEmpty() ? new Route(RouteSource.NONE, List.of()) : new Route(RouteSource.ADVERTISED, urls);
    }

    private List<Advertiser> advertisers(Entry entry) {
        Map<MemberId, byte[]> members = datastore.read(ServiceAdvertisement.key(entry.name(), entry.version())).members();
        return members.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(MemberId::nodeId).thenComparing(MemberId::localName)))
                .map(member -> new Advertiser(member.getKey().nodeId(), ServiceAdvertisement.decode(member.getValue()).url(),
                        member.getKey().nodeId().equals(datastore.nodeId())))
                .toList();
    }

    private List<Holder> holders(String lease) {
        Map<MemberId, byte[]> members = datastore.read(HengeLeaseKeeper.key(lease)).members();
        return members.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(MemberId::nodeId).thenComparing(MemberId::localName)))
                .map(member -> {
                    int claimed = SystemEphemeralDatastore.claimedAmount(member.getValue());
                    return new Holder(member.getKey().nodeId(), Math.abs(claimed), claimed < 0,
                            member.getKey().nodeId().equals(datastore.nodeId()));
                })
                .toList();
    }

    /**
     * Who injects each service version, from the bean factory's own record of what it wired. A bean that
     * is a service (or the hidden {@code <bean>.impl} of a leased one) is an edge from that service;
     * anything else is one of the application's own beans, listed in {@code consumers}. Only what this
     * process wired is known: a service hosted elsewhere has dependencies of its own that aren't here.
     */
    private List<Dependency> dependencies(Map<String, Service> byId, List<Consumer> consumers) {
        ConfigurableListableBeanFactory beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        Map<String, String> serviceByBean = new LinkedHashMap<>();
        for (Entry entry : catalog.entries()) {
            serviceByBean.put(entry.beanName(), id(entry));
            serviceByBean.put(entry.beanName() + ".impl", id(entry));
        }
        Map<String, Consumer> otherBeans = new TreeMap<>();
        Set<Dependency> edges = new LinkedHashSet<>();
        for (Entry entry : catalog.entries()) {
            String to = id(entry);
            for (String dependent : beanFactory.getDependentBeans(entry.beanName())) {
                String fromService = serviceByBean.get(dependent);
                if (fromService != null) {
                    if (!fromService.equals(to)) {
                        edges.add(new Dependency(fromService, to, byId.get(to).state() != State.HOSTED));
                    }
                } else {
                    Class<?> type = beanFactory.getType(dependent, false);
                    otherBeans.putIfAbsent(dependent, new Consumer("bean:" + dependent, type == null ? null : type.getName()));
                    edges.add(new Dependency("bean:" + dependent, to, byId.get(to).state() != State.HOSTED));
                }
            }
        }
        consumers.addAll(otherBeans.values());
        return edges.stream()
                .sorted(Comparator.comparing(Dependency::from).thenComparing(Dependency::to))
                .toList();
    }
}
