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

Without configuration, every process has its own in-process store. That is why rung 0, a monolith, needs
nothing: the store is always there, it just isn't shared, and there is no one to share it with. A process
that hosts only part of the services is not alone, and **refuses to start** on it: name a store, or name
`henge.store.type=in-process` to say its processes share nothing ([chapter 4](04-splitting.md#a-split-needs-a-store)).

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

When the store itself is unavailable or restarted, callers keep what they last read; see
[Fault tolerance](#fault-tolerance-when-the-store-is-away).

## Fault tolerance: when the store is away

Most systems that depend on a critical shared store stop when it does. Henge doesn't. The store is on the
critical path, and Henge treats its being away as a **mode to ride out**, not an error to die of: a
restart or a replacement of Redis is expected, and brief, and the cluster should barely notice it.

A process with the in-process store (one node, or processes that share nothing) never has this problem: its store
is its own memory and can't be away. Everything below is about a **shared** store such as Redis.

### The approach

Every part of Henge that uses the store follows the same three rules while it can't be reached:

1. **Keep using the last answer you had.** What a process last read about the cluster is still the best
   thing it knows, so it keeps acting on it, however old.
2. **Where there is no last answer, be conservative.** Take less than the full share, or refuse, and
   never guess in the direction that could do damage.
3. **Fail honestly, and heal on the first contact.** What can't be answered is a `503` (`StoreUnavailableException`),
   not a made-up answer. When the store answers again, every process re-asserts its state on its next
   heartbeat, and everything converges. Nothing is restored, because there was nothing to restore.

Nothing in this needs a coordinator, an election, or a decision about how long an outage is too long.
Henge adds no deadline of its own: if the store stays away, the cluster slowly degrades on its own terms
(below), and the first thing to notice is your monitoring.

### What each part does

| Part | While the store can't be reached | Why that is safe |
|---|---|---|
| **Finding services** (advertisements) | Calls keep going to the hosts last read. A host that fails is skipped until the next successful read, unless it's the only one. An entry lives one refresh interval: once the store is reached again, one nobody has asked about since is let go, and one that can't be replaced because the store is away is kept. A service with no entry, never read or let go, can't be found: the call fails with `StoreUnavailableException`. | A host that was advertised a moment ago is almost certainly still there, and the failover of [chapter 4](04-splitting.md#when-a-call-fails) handles the one that isn't. |
| **Advertising** | Heartbeats keep trying; nothing else changes. Once the store is back, they are re-written within 10 seconds, wiped or not. | An entry that lapses in a store that can't be read harms no one. |
| **Leases** | Sit still. A process keeps its leases and the resources (the connection pool) it opened on them, and keeps trying to renew. After a restart or wipe it re-claims on the first heartbeat. If another process claimed the capacity first, it logs a warning and keeps running; nothing is evicted. | Capacities are an underestimate of the real limit ([soft limits](06-leases-and-rate-limits.md#soft-limits)), and it is better for a few extra pool connections to exist for a moment than for services to be torn down in the middle of an outage. |
| **Rate limits** | Each process draws on a bucket of its own in memory, sized to **1/N** of the limit, for the *N* processes that share it. They add up to the limit, so the cluster as a whole still holds to it, only coarser. See [chapter 6](06-leases-and-rate-limits.md#when-the-store-is-away). | Never more than the limit; at worst a little less. |
| **Starting a process** | It starts anyway, **alive and not ready**: every request but the health checks is a `503`, and readiness is refused until it has reached the store once. See [Operating](07-operating.md#starting-without-the-store). | A restart wouldn't bring the store back, so a crashing node would be replaced over and over. A node that is alive and not ready is left alone by an orchestrator, and sent nothing. |
| **A call that needs the store and has no answer** | `StoreUnavailableException`, `503` over `internal-rest`. With the Boot starter, a request that fails this way is a `503` at your own edge too, unless you handle the exception. Henge never retries it. | Henge only repeats a call that provably never ran. A `503` here says the *cluster* is in trouble, which another try won't fix. |

### Restarting Redis

A restart of Redis is an outage followed by a wipe. For the seconds it takes:

- Calls carry on, to the hosts last read. Leases and their resources stay where they are.
- Rate limits answer from their local share.
- The first operation to find the store back ends the outage. Until then, operations fail at once rather
  than each waiting out a connection timeout, and one at a time is let through to find out (see
  `henge.store.backoff.*` in the [configuration reference](../reference/configuration.md#the-ephemeral-store)).
  The start and the end of an outage are each logged once, not once per call.
- The wiped store is empty, so a read of it may briefly show no hosts at all. A caller that saw hosts
  before, and sees none from a store with a *new epoch*, keeps what it had for one more interval, by
  which time everyone has heartbeated. An empty answer from the *same* store is believed at once.
- Every process writes its advertisements, leases and limiter membership again within one heartbeat.

### What this doesn't give you

It is a mode to ride out, not a way to run without the store. During a long outage:

- **Nothing new is learned.** A process that starts, a host that stops, a service version that is
  retired: other processes don't hear of it, so their routes go stale. Failover covers the host that
  died; it can't cover one that hasn't been found.
- **Shares are frozen.** A rate limit's share is a fraction of the number of nodes counted before the
  outage. Nodes that join during it are not counted, and together can let a little more through than the
  limit; nodes that leave leave their share unused.
- **Leases can't change hands.** A service that needs one and was refused it, or couldn't ask, waits.
- **It is soft.** The limits and leases are over-granted, if at all, by a bounded amount; that is what
  the margin of [chapter 6](06-leases-and-rate-limits.md#soft-limits) is for.

Run Redis so that it comes back quickly, and watch `henge.store.operations` (`outcome=error`) and
`henge.rate-limit.degraded` for the outage you didn't cause.

## What you have

Processes that find each other, fail over between each other, and come and go without anyone editing
configuration. The store is the thing that makes the next rung possible: limits that hold across the
whole cluster.
