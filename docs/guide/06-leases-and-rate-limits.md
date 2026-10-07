# 6. Leases and rate limits

**Rung 3: limits that hold across the cluster.** Some resources don't grow with your cluster: a database
that accepts 200 connections, a payment provider that allows 10 calls a second, a customer who shouldn't
get 50 messages a minute however many processes are sending them. Every limit like that, enforced per
process, is wrong by a factor of the number of processes. Leases and rate limits enforce them once, for
the whole cluster, through the [ephemeral store](05-the-ephemeral-store.md).

Both are opt-in, and both work without a shared store: they then hold per process, which is right for a
single process and a fair default for a laptop.

## Leases

Inventory needs connections to its database. Every process that hosts inventory opens a pool, so the
database sees pools × processes connections, and the number of processes is exactly what you want to be
free to change. A **lease** caps it: a share of a cluster-wide capacity that a process must be granted
before it builds the service.

A lease has a **provider** that builds the resource, once per process, sized from what was granted:

```java
@LeasedResource("inventory-db")
public class InventoryDatabase implements ResourceProvider<DataSource> {

    public InventoryDatabase(@Value("${shop.inventory.jdbc-url:...}") String jdbcUrl) { ... }

    @Override
    public DataSource open(Lease lease) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(lease.amount());   // sized from the lease
        return new HikariDataSource(config);
    }
    // close() closes an AutoCloseable, which a pool is
}
```

and services that ask for it:

```java
public InventoryServiceImpl(@RequiresLease("inventory-db") DataSource database) { ... }
```

```yaml
henge:
  leases:
    inventory-db:
      capacity: 10     # cluster-wide; set it below the real limit, as a margin
      amount: 5        # what one process claims
```

A process claims a lease **once, at startup**, before it constructs any service that needs it:

- **Granted**: the provider builds the pool, every service on the process that asked for
  `inventory-db` gets the same one (here, both versions of inventory), and the claim is renewed on a
  heartbeat. It's handed back, and the pool closed, when the process stops; a process that crashes gives
  its share back after the lease's 30-second time to live.
- **Refused**: the services that needed it aren't built here, the pool is never opened, and they're
  reached remotely instead, like any `internal-rest` service: at their configured `url`, or wherever
  they're advertised. A refused process keeps looking: every 30 seconds or so (see
  [Taking up a lease later](#taking-up-a-lease-later)) it checks whether the lease has room, and
  if it does it claims it and hosts the services after all.

All of a service's leases are granted, or none are. A provider is optional: a service can take a `Lease`
parameter instead (`@RequiresLease("inventory-db") Lease lease`) and build its own resource from
`lease.amount()`.

A refused service is found by url or advertisement, so leases can't be combined with
`remote-url-template`: a template assumes every process behind a name hosts the service, which a lease
is there to make untrue. In practice, leases come with a shared store, and advertisements.

Henge keeps the books; it never sees a connection. Keep the real resource inside the provider: a pool
built as an ordinary shared bean opens its connections on every process, granted or not, which is
the next section.

### Taking up a lease later

A process that was refused a lease keeps a list of what it would host if it had one, and looks at the
lease every `henge.lease-poll.interval` (30 seconds), give or take half, so that processes refused
together at a deploy don't look together. If the lease has room, it claims it, builds the implementation
and starts answering and advertising it. The proxy that callers already hold switches from the network
to the implementation, so nothing has to be reinjected. A process whose leases were all granted never
looks, and costs the store nothing.

What keeps several refused processes from getting in each other's way:

- **A look is a read, then the claim.** The process reads every lease the service needs, and claims only if
  they all show room, straight away, with nothing in between. A process that claimed one lease of a
  set and was then refused the next would hold the first for a moment, and turn away a process that
  would have fitted. Reading first means a set that can't be completed is never started on.
- **Looks are spread out.** The jitter is tens of seconds; the read and the claim are two round trips to the
  store. Two processes only collide if their looks land within that gap, and the one that loses the race
  is refused like any other and tries again later, at a longer interval.
- **A full lease is asked about less and less.** Each refusal doubles the wait, up to
  `henge.lease-poll.max-interval` (5 minutes). The cost to a cluster of refused processes is a read every
  few minutes each.

If the store is restarted or wiped, a refused process can claim a lease before its holders have renewed
theirs, and one of the holders then finds its claim gone and the lease full. Waiting a lease TTL after the
store's epoch changes narrows that window, and the next section is what happens when it doesn't.

### Giving a lease up

The store checks a claim against the capacity of the node that makes it, and each node is configured with its
own, so a rollout that changes `henge.leases.<lease>.capacity` leaves nodes disagreeing for a while. A renewal is
checked like any claim, and one the store refuses means this process sees the rest of the cluster holding too much
for its claim to fit: it lost the claim (it lapsed, or the store was wiped and another process claimed the capacity
first), or newer processes are configured with a larger capacity. Either way it should not carry on, and it makes
room for the processes that are not refused, which are the new ones in a rollout, or is about to be replaced
by one. The store turns its claim into one being given up in the same step that refused it, and the process keeps
writing it as that, from its heartbeat, until it has finished; a store that was wiped gets it written again.
A claim being given up counts against a new claim, since the pool is open until the process has closed it, and
counts for nothing against a renewal. So of several processes refused together, only the first leaves: the
others' renewals no longer have to make room for it, and succeed.

For each service on the lease the process does what a retirement does, in the order a caller can follow without a
failed call: it stops advertising the service, waits `henge.lease-evict.grace` (10 seconds, the callers'
refresh interval), switches the service to the network, gives the calls already running
`henge.lease-evict.drain-timeout` (30 seconds) to finish, destroys the implementation, which closes the pool,
and hands the claim back. It then waits to be hosted again like a process that was refused, which it will be once the
lease has room under its own capacity. Unlike a retirement, which is final.

Between the refusal and closing the pool, about 40 seconds at the defaults, the process is still using a
resource that is no longer its to use; that is the overlap the margin below the real limit is for. The grace and drain
can be shortened. A store that is away is not a refusal: holders sit still.

In a Docker test of three shop replicas, a fourth started with a capacity of 15 against their 10: its claim was
granted, and of the two holders that believed in 10 only the first to be refused gave the lease up. The other kept
its claim and kept serving, the cluster was never down to fewer than two holders, and every request in the test was
answered. Wiping the store three times, in the same cluster, never held more than two claims.

A lease handed to a service that was retired on purpose is not taken up again. Nothing asks a holder to
give a lease up: a process that has one keeps it, and a refused one only gets it when its holder goes away.

## A DataSource, from lease to close

**Spring's own DataSource doesn't fit a lease.** Spring Boot builds a pool from `spring.datasource.*`
as an ordinary bean, and JPA, Flyway and schema initialization connect to it while the context starts,
before any lease could be asked for. Every process opens its pool, granted or not, and the database sees
pools times processes connections. Henge can't stop that after the fact, so it **refuses to start** with a
`DataSource` bean in the context, naming the bean and where it came from:

```
This application has a DataSource bean, 'dataSource' (from ...DataSourceConfiguration$Hikari), and a bean's
pool is opened on every process that runs the application, before a lease could be asked for ...
```

Two ways out. Take the pool out of Spring's hands, as below; or, if every process opening its own pool is
what you want (an embedded database, a database that takes any number of connections), say so with
`@HengeAcknowledgeThisOpensAPoolOnEveryNode`, on your `@Bean` method or, for the pool Boot builds, on your
application class. It's the same bargain as `@HengeAcknowledgeThisRunsOnEveryNode` for `@Scheduled`: the
dangerous thing is possible, and the consequence has to be typed where the code is.

**1. Switch Spring's out.** Exclude the auto-configuration, and drop `spring.datasource.*`:

```yaml
spring:
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

**2. Allocate in the provider.** `open` runs once, on a process that was granted the lease, and nowhere
else. Build the pool there, sized from `lease.amount()`, and anything that has to touch the database
before a service uses it (a schema, a migration) goes there too, so it runs only where the pool exists:

```java
@LeasedResource("inventory-db")
public class InventoryDatabase implements ResourceProvider<DataSource> {

    private final String jdbcUrl;
    // credentials, and the rest of the configuration, come in the constructor like any bean's

    @Override
    public DataSource open(Lease lease) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(lease.amount());   // this node's share, never more
        config.setMinimumIdle(0);                    // don't hold connections the node isn't using
        HikariDataSource pool = new HikariDataSource(config);
        try {
            Flyway.configure().dataSource(pool).load().migrate();
        } catch (RuntimeException e) {
            pool.close();                            // open() failed: nobody else will close it
            throw e;
        }
        return pool;
    }
}
```

If `open` throws, startup fails, and what it had built is its own to clean up: Henge has no resource to
close yet. The pool should also fail fast on a database that is away (Hikari's `initializationFailTimeout`),
so a bad URL is an error at startup, not a hang.

**3. Receive it in the service.** `@RequiresLease("inventory-db") DataSource database` in the constructor,
as above. Build what you need from it there (a `JdbcTemplate`, a `JdbcClient`, an `EntityManagerFactory`).
Those are yours, not beans, so nothing else in the application can reach the pool, which is the point.

**4. Release it.** Nothing to write for the usual case. When the last service on the node that holds the
lease is destroyed, in the order the context shuts down, Henge calls `close(resource)` on the provider and
only then deletes the claim, so the capacity isn't offered to another process while this one still holds
connections. `close` defaults to `AutoCloseable.close()`, which is right for Hikari. Override it when the
resource needs more than that:

```java
@Override
public void close(DataSource pool) {
    ((HikariDataSource) pool).close();   // waits for borrowed connections to come back
}
```

Rules for the end of the lease:

- A service must not outlive the resource: don't hand the `DataSource` to a thread that keeps running
  after the service is destroyed (a pool of workers you started, say). Stop those in the service's own
  `@PreDestroy`, which runs first.
- A failure in `close` is logged and the claim is still deleted; the process is going away anyway.
- A process that crashes never calls `close`; its connections die with it, and its claim lapses with the
  lease's 30-second time to live.
- A [lost lease](#giving-a-lease-up) closes the pool, once the calls running on it have finished or the drain times out.

## Rate limits

Notifications must not flood a customer: a burst of three messages, then one every ten seconds. A rate
limit is a leaky bucket in the store, configured by name:

```yaml
henge:
  rate-limits:
    customer-notifications:
      permits: 1       # drain 1 permit...
      period: 10s      # ...every 10 seconds: the sustained rate
      capacity: 3      # the burst a quiet bucket absorbs; defaults to permits
```

and injected by name, into a service or any other bean:

```java
public NotificationServiceImpl(@RateLimited("customer-notifications") RateLimiter perCustomer) { ... }

@Override
public boolean notify(String customer, String text) {
    if (!perCustomer.tryAcquire(customer)) {
        return false;      // throttled; what that means is the caller's decision
    }
    // ... send
    return true;
}
```

`tryAcquire(customer)` gives every customer their own bucket under the same limit; `tryAcquire()` draws
on one bucket for the whole limit. A bucket that has drained is forgotten, so idle customers cost
nothing. Each call is one operation on the store, and the process keeps no state of its own.

A refusal throws nothing. If it should reach a remote caller as an exception, throw one, with an
`@ErrorStatus` (`429` reads naturally); it won't be retried. Every process that configures a limit draws
on the same bucket, so every process must configure it the same way.

### When the store is away

A rate limit that can't reach the store degrades instead of failing. Every process that builds a limiter
registers itself under the limiter's key and counts the others on each heartbeat, so it always knows
about how many nodes, *N*, share the limit. Without the store it draws on a bucket of its own in
memory, sized to *1/N* of the limit: the sustained rate spread over *N* times the period, and a burst
of *1/N*, never less than one. The nodes' shares add up to the limit, so the cluster as a whole still
holds to it, only coarser. When the store answers again the limiter is back on the shared bucket at
once. `henge.rate-limit.degraded` counts the answers given this way.

A process that has never reached the store doesn't start, so *N* is always known. Shares are soft, like
everything here: a node that joins during the outage can't count itself, and a limit of fewer permits
than nodes can't be divided below one permit each, so either can let a little too much through.

## The cluster, deciding

Two identical processes, each configured to host everything, sharing Redis, with room in the database
for one pool:

```bash
JAR=examples/shop-app/build/libs/shop-app-0.1.0-SNAPSHOT.jar
STORE="--henge.store.type=redis --henge.store.redis.uri=redis://localhost:6379"

java -jar $JAR --server.port=8080 --henge.advertise.url=http://localhost:8080 \
  --henge.leases.inventory-db.capacity=5 $STORE
java -jar $JAR --server.port=8081 --henge.advertise.url=http://localhost:8081 \
  --henge.leases.inventory-db.capacity=5 $STORE
```

The first is granted `inventory-db` and builds inventory. The second is refused, logs
`Lease 'inventory-db' is full; inventory-service@1 is reached remotely from this process`, never opens a
connection, and sends its inventory calls to the first, which it finds by advertisement. An order placed
on either process holds stock in the one database. A customer who orders through both processes gets
three messages in all, not three from each.

Nobody assigned inventory to the first process. The configuration said what the database can take, and
the processes worked out the rest. This is the smallest version of where Henge is going: see
[Philosophy](../philosophy.md).

## Mistakes fail startup

A lease asked for with no configuration, an amount larger than the capacity (no process could ever be
granted it), two providers for one lease, a resource type that doesn't fit the parameter, a rate limit
that isn't configured, a period over an hour, a `DataSource` bean: each fails startup, naming what to fix.

## Soft limits

Both limits are soft, and both err in the same direction: toward letting a little too much through,
never toward refusing what they should allow. A rate limit read from an incomplete view of the store sees
its bucket as correct or emptier than it is, and a lease claimed from one counts fewer holders than there
are (in a Redis failover, say). So set every capacity as an intentional underestimate of the real limit:
the margin is what an over-grant lands in.

A lease can be over-granted when the store's view is incomplete: a claim is made against fewer holders than
there are. The next renewal of a holder notices, and [the lease is given up](#giving-a-lease-up).
Anything that
needs a hard guarantee, mutual exclusion or exactly-once, belongs in a system built on consensus, not in
the ephemeral store.
