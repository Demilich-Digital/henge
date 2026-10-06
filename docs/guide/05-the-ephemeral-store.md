# 5. The ephemeral store

**Rung 2: processes that know about each other.** You add a fast ephemeral store, Redis today, that
every process can reach. In return, processes find each other without addresses or DNS conventions, and
the cluster gets a shared place for the limits of [chapter 6](06-leases-and-rate-limits.md).

## What it is, and what it isn't

The fast ephemeral store is where Henge keeps what the cluster knows about itself: who hosts what, who
holds which lease, how full each rate limit's bucket is. It is built on a few rules, and they are what
make it safe to depend on:

- **Everything expires.** Every entry has a time to live and is renewed by whoever wrote it, on a
  heartbeat. A process that crashes simply stops renewing, and disappears on its own.
- **The whole store may be wiped at any time.** A wipe looks the same as everything expiring early, and
  every process re-asserts its state on its next heartbeat. There is no backup to restore, because
  there's nothing to restore.
- **It converges.** Every process writes its own entries and never overwrites another's, so copies that
  diverge merge back into one answer without anyone coordinating. Nothing needs consensus.

It is **not a cache**, and not optional: processes find each other and claim leases through it, so
it is on the critical path. It is cheap to lose and quick to rebuild, which is a different thing from
being something Henge can run without.

It is also **not** a place for your application's data. Orders, stock and customers go in a database. The
store holds only state that is cheap to lose and quick to rebuild, which is what lets it be fast, and lets
you run it without the care a database needs. The contract, and what kinds of system can implement it, is
in [The ephemeral store](../ephemeral-store.md).

Without configuration, every process has its own in-process store. That is why rungs 0 and 1 needed
nothing: the store is always there, it just isn't shared.

## Turning it on

Add `henge-redis` to the app and point every process at the same Redis (7.4 or later):

```kotlin
runtimeOnly(project(":henge-redis"))
```

```yaml
henge:
  store:
    type: redis
    redis:
      uri: redis://redis:6379      # or cluster-nodes: redis://node1:6379,redis://node2:6379
```

## Advertisements

Once a process has started, it **advertises** every service version it hosts, with its own address,
`henge.advertise.url`. The advertisement is renewed every 10 seconds and expires after 30; a process that
stops gracefully withdraws first.

Callers use them as the last resort for a url: after an explicit `url` and after
`remote-url-template` ([chapter 4](04-splitting.md#let-the-orchestrator-route)). So a process with
neither configured finds its services through the store. A caller reads the advertisements of a service
version at most every 10 seconds, however many calls it makes, and rotates its calls through every
process advertising it.

The shop, split as in chapter 4, but with no addresses between the processes; each says only where it
can be reached:

```bash
docker run -d --name henge-redis -p 6379:6379 redis:8
STORE="--henge.store.type=redis --henge.store.redis.uri=redis://localhost:6379"
JAR=examples/shop-app/build/libs/shop-app-0.1.0-SNAPSHOT.jar

java -jar $JAR --server.port=8082 --henge.serve=inventory-service \
  --henge.advertise.url=http://localhost:8082 $STORE

java -jar $JAR --server.port=8080 --henge.serve=order-service,notification-service \
  --henge.advertise.url=http://localhost:8080 $STORE
```

```bash
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customer": "ada", "items": [{"sku": "rope", "quantity": 2}]}'
```

Stop the inventory process and it withdraws its advertisements at once. Start it again, on any port, and
the storefront finds it within one refresh, with nothing reconfigured. Start a second inventory process
and calls rotate across both. (They would each have their own in-memory database here; set
`shop.inventory.jdbc-url` to a database they share.)

`henge.advertise.url=http://my-host:${server.port}` works, if the port is set elsewhere.

## Failover

The retries of [chapter 4](04-splitting.md#when-a-call-fails) become failover: a call that couldn't
connect, or was answered `404`, is retried on the **next advertised host**, not the same one. A host that
just failed isn't offered again until the advertisements are next read, unless it's the only one.

When the store itself is briefly unavailable or restarted, callers keep what they last read: an empty
answer from a store that was just restarted (and so hasn't heard from anyone yet) is ignored for one more
interval, and an empty answer from the same store is believed.

## What you have

Processes that find each other, fail over between each other, and come and go without anyone editing
configuration. The store is the thing that makes the next rung possible: limits that hold across the
whole cluster.
