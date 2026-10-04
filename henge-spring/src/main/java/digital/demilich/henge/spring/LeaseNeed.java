package digital.demilich.henge.spring;

/** One lease a service implementation declares: its name, what one instance claims, and the cluster-wide capacity. */
record LeaseNeed(String name, int amount, int capacity) {
}
