# The ephemeral store

The fast ephemeral store is a concept, not a product: a shared, fast, lossy map that every process in a
Henge cluster can reach, and that Henge uses for everything the cluster knows about itself. This page is
its contract: what Henge needs from a store, and so what kinds of system can be one. To turn one on, see
[chapter 5 of the guide](guide/05-the-ephemeral-store.md).

It is **not a cache**: Henge is not a system with a store bolted on to make it faster. The store is on the
critical path, and a process that can't reach it can't find its peers or claim a lease. What it holds is
cheap to lose because every process rebuilds it on a heartbeat, which is a different thing from being
optional. A process's in-memory copy of what it last read (its *routing table*) is the only cache-like
part, and it is deliberately kept serving through an outage: see [Fault tolerance](guide/05-the-ephemeral-store.md#fault-tolerance-when-the-store-is-away).

## The model: convergent distributed state

Every key has many writers, one per process that hosts a service, holds a lease, or calls a rate limit.
The state is shaped so that copies of a key that diverge settle back to one answer by merging, with no
coordinator:

```
key → { member → (value, expiresAt) }      members
key → (level, at)                          a bucket
```

- **Writers own their entries.** A member belongs to the process (node) that wrote it: a node can only
  write members under its own `nodeId()`, and a restarted process is a new node. Writers never overwrite
  each other, so copies of a key merge by union.
- **A bucket merges by maximum.** Its level is shared by every writer; copies merge by taking the highest
  level, each leaked to now. A copy that missed some takes is lower than the truth, never higher.
- **Every member expires.** Time to live is relative, and the deadline is computed on the store's own
  clock, so the writers' clocks don't matter. Writers renew what they want kept.
- **A read returns the live members of a key, across all writers**, and the key's **epoch**, which
  changes whenever its members may have been lost other than by expiry or removal: a restart, a failover
  to a copy that missed writes, a clear, the key moving to storage that didn't have it. That is how a
  reader tells "nobody is there" from "the store just lost them". An epoch may change with nothing lost;
  the reader then waits for nothing. It must change for **every** way the store loses data in operation,
  the ones an operator causes (a flush) and the ones the store does itself (eviction) included: over a
  cluster's life they will all happen.
- **Anything may be lost at any time.** A wipe must look like early expiry, and every consumer
  re-asserts its state on a heartbeat. A loss the epoch didn't report is early expiry with no warning, and
  consumers survive it the same way. The store is never a system of record, and holds no application
  data.
- **Each key stands alone.** A key may be unreachable or lost while others are fine. Nothing may assume
  which keys fail together.
- **A call that fails may have happened.** An operation that throws may or may not have taken effect, and
  whatever it wrote expires. `StoreUnavailableException` says the store couldn't be reached;
  `IllegalArgumentException` is only for the caller's mistake.

## The operations

The Java interface is `SystemEphemeralDatastore`, in `henge-core`:

| Operation | Does | Under an incomplete view |
|---|---|---|
| `nodeId()` | This process's identity, for the life of the process | |
| `put(key, localName, value, ttl)` | Writes or renews this node's member | |
| `remove(key, localName)` | Removes this node's member early, for a graceful exit | |
| `read(key)` | The live members, and the epoch | Sees fewer members than exist; writers re-assert on their heartbeat. |
| `count(key)` | How many members there are, and the epoch: what `read` would show, without the members. It may also count members that have lapsed and that the store hasn't reclaimed yet, so it is never fewer than the live members of the copy it reads, and may be more. That is the price of answering in constant time, which a store should; the default reads them all, and is exact. Every use of it has to be safe with a count that is too high. | Counts fewer members than exist. |
| `sample(key, limit)` | Up to `limit` live members, distinct and at random, never a lapsed one, how many there are in all (counted as `count` counts), and the epoch. Empty only when the key has no live members. A store should answer in time proportional to `limit`; the default reads them all. | Samples from, and counts, fewer members than exist. |
| `tryAcquire(key, amount, limit)` | Leaks a leaky bucket for the time since it was last touched, then takes `amount` permits if they fit. Atomic within a copy of the bucket. A refusal says how long until one permit fits, during which nothing fits for anyone. | Reads a level that is correct or too low: a limit lets a bounded few too many through, and never refuses a call it should allow, and a wait is too short, never too long. |
| `claim(key, localName, amount, capacity, ttl)` | Writes this node's member only if the sum of everyone else's amounts plus this one fits the capacity, the caller's own. Atomic within a copy of the key. A negative amount is a claim being given up: always granted, still counted against new claims and not against renewals. A refused renewal turns the claim into one being given up. | Reads a sum that is correct or too low: a lease can be over-granted by a bounded amount, and is never refused when there is room. |

Both limits err the same way, toward over-admitting, and both are handled the same way: the configured
capacity is an intentional underestimate of the real limit, so a bounded over-grant lands inside the
margin. An over-granted lease is noticed at its next renewal, which reads a sum over the capacity and
fails.

A bucket's keyspace is separate from the members'. Implementations must be thread-safe.

## What Henge keeps there

| Key | Members | Used for |
|---|---|---|
| `adv:<service>@<version>` | One per process hosting that version, valued with its address | [Advertisements](guide/05-the-ephemeral-store.md#advertisements) |
| `lease:<name>` | One per process holding the lease, valued with its amount | [Leases](guide/06-leases-and-rate-limits.md#leases) |
| `rate:<name>`, `rate:<name>:<subject>` | A bucket, not members | [Rate limits](guide/06-leases-and-rate-limits.md#rate-limits) |
| `cron:<job>@<instant>` | The one process that won the fire, capacity 1, kept ten minutes and never released | [Scheduled jobs](guide/09-scheduled-jobs.md) |
| `cron:<job>:running`, or `cron:<job>:running@<instant>` for a job that overlaps | The one run in progress, capacity 1, renewed while it executes | [Scheduled jobs](guide/09-scheduled-jobs.md#a-run-that-outlasts-its-interval) |

## What fits

- **In-process** (the default): a map in the process. It is a correct store for a cluster of one, which
  is why a monolith works with no configuration, and every limit holds per process. It is for a process that
  is the whole cluster, development and tests: a process that reaches any service over the network refuses
  to start on the default, and has to name a store, or name `in-process` to say its processes share nothing.
- **Redis**, 7.4 or later (`henge-redis`): hash-field TTLs give each member its own expiry, and every
  operation is one Lua script on one key, and Redis serializes each key, so there is one copy and nothing
  to merge. On Redis Cluster each key lives in one slot, so it shards with no cross-node coordination. The
  epoch is the `run_id` of the server holding the key and a token kept in the key itself
  ([design](design/lease-healing.md#every-redis-loss-changes-the-epoch)): a restart or failover changes the
  first, and a flush or an eviction under `maxmemory` the second, while members lapsing changes neither. Set
  `maxmemory-policy noeviction` all the same, so a full Redis fails as an outage instead of shedding claims
  that are in use: the token makes eviction seen, not harmless. Every process sharing a Redis must be on a
  version with the token, since earlier ones can't read a key that has it. How big a Redis a cluster needs, and why memory is
  the wrong thing to size by, is in [store capacity](design/store-capacity.md).
- **A private DHT** (planned): the store built into the cluster itself, with no separate system to run,
  designed for a trusted private network rather than open peer-to-peer use. See [the design
  doc](design/self-orchestration.md#a-built-in-dht-not-built).

Anything else that can expire members individually, read a key's live members, merge copies by the rules
above, make `claim` and `tryAcquire` atomic within a copy, and change a key's epoch on every way it can lose
it can be one.

## Writing an adapter

Implement `SystemEphemeralDatastore`, and either:

- **select it by configuration**: implement `SystemEphemeralDatastoreProvider` (its `type()` is the value
  of `henge.store.type` that selects it, and its own settings live under `henge.store.<type>.*`), and
  register it with `java.util.ServiceLoader` in
  `META-INF/services/digital.demilich.henge.core.SystemEphemeralDatastoreProvider`. That's how
  `henge-redis` does it. A store that is `AutoCloseable` is closed when the application stops; or
- **define it as a bean** of type `SystemEphemeralDatastore`. Setting `henge.store.type` as well fails
  startup.

Test it by extending `EphemeralDatastoreContract`, from `henge-core`'s test fixtures
(`testImplementation(testFixtures("digital.demilich.henge:henge-core"))`): the contract's tests, given the
store and a way to let time pass. `henge-redis`'s own tests are a template for the rest, the same behaviors
against a real store.
