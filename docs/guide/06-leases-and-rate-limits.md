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
  they're advertised. A refused process stays that way until it restarts. It doesn't take over when a
  holder goes away; that is a later rung.

All of a service's leases are granted, or none are. A provider is optional: a service can take a `Lease`
parameter instead (`@RequiresLease("inventory-db") Lease lease`) and build its own resource from
`lease.amount()`.

A refused service is found by url or advertisement, so leases can't be combined with
`remote-url-template`: a template assumes every process behind a name hosts the service, which a lease
is there to make untrue. In practice, leases come with a shared store, and advertisements.

Henge keeps the books; it never sees a connection. Keep the real resource inside the provider: a pool
built as an ordinary shared bean opens its connections on every process, granted or not, which is
the next section.

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
- A lost lease (the over-capacity case in [Soft limits](#soft-limits)) doesn't close the pool yet.

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

An over-granted lease is noticed at its next renewal, which fails, and the process logs a warning and
counts it (`henge.lease.renewals` with `outcome=over-capacity`). It doesn't give the lease up yet: its
services keep running on the resource until the process stops. Giving up a lost lease, closing the
resource and reaching the services remotely, is the next thing on the [roadmap](../scope.md#roadmap). Anything that
needs a hard guarantee, mutual exclusion or exactly-once, belongs in a system built on consensus, not in
the ephemeral store.
