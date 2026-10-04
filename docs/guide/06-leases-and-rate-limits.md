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
built as an ordinary shared bean (or by JPA, or Flyway) opens its connections on every process, granted
or not.

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
that isn't configured, a period over an hour: each fails startup, naming what to fix.

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
