# The ephemeral store

The fast ephemeral store is a concept, not a product: a shared, fast, lossy map that every process in a
Henge cluster can reach, and that Henge uses for everything the cluster knows about itself. This page is
its contract: what Henge needs from a store, and so what kinds of system can be one. To turn one on, see
[chapter 5 of the guide](guide/05-the-ephemeral-store.md).

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
- **A read returns the live members of a key, across all writers**, and an **epoch**: an identifier of
  the storage that answered. A different epoch from one read to the next means the data may have been
  wiped, which is how a reader tells "nobody is there" from "the store just restarted".
- **Anything may be lost at any time.** A wipe must look like early expiry, and every consumer
  re-asserts its state on a heartbeat. The store is never a system of record, and holds no application
  data.

## The operations

The Java interface is `SystemEphemeralDatastore`, in `henge-core`:

| Operation | Does | Under an incomplete view |
|---|---|---|
| `nodeId()` | This process's identity, for the life of the process | |
| `put(key, localName, value, ttl)` | Writes or renews this node's member | |
| `remove(key, localName)` | Removes this node's member early, for a graceful exit | |
| `read(key)` | The live members, and the epoch | Sees fewer members than exist; writers re-assert on their heartbeat. |
| `tryAcquire(key, amount, limit)` | Leaks a leaky bucket for the time since it was last touched, then takes `amount` permits if they fit. Atomic within a copy of the bucket. | Reads a level that is correct or too low: a limit lets a bounded few too many through, and never refuses a call it should allow. |
| `claim(key, localName, amount, capacity, ttl)` | Writes this node's member only if the sum of everyone else's amounts plus this one fits the capacity. Atomic within a copy of the key. | Reads a sum that is correct or too low: a lease can be over-granted by a bounded amount, and is never refused when there is room. |

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

## What fits

- **In-process** (the default): a map in the process. It is a correct store for a cluster of one, which
  is why everything works with no configuration, and every limit holds per process.
- **Redis**, 7.4 or later (`henge-redis`): hash-field TTLs give each member its own expiry, and every
  operation is one Lua script on one key, and Redis serializes each key, so there is one copy and nothing
  to merge. On Redis Cluster each key lives in one slot, so it shards with no cross-node coordination. The
  epoch is the server's `run_id`.
- **A private DHT** (planned): the store built into the cluster itself, with no separate system to run,
  designed for a trusted private network rather than open peer-to-peer use. See [the design
  doc](design/self-orchestration.md#a-built-in-dht-not-built).

Anything else that can expire members individually, read a key's live members, merge copies by the rules
above, and make `claim` and `tryAcquire` atomic within a copy can be one.

## Writing an adapter

Implement `SystemEphemeralDatastore`, and either:

- **select it by configuration**: implement `SystemEphemeralDatastoreProvider` (its `type()` is the value
  of `henge.store.type` that selects it, and its own settings live under `henge.store.<type>.*`), and
  register it with `java.util.ServiceLoader` in
  `META-INF/services/digital.demilich.henge.core.SystemEphemeralDatastoreProvider`. That's how
  `henge-redis` does it. A store that is `AutoCloseable` is closed when the application stops; or
- **define it as a bean** of type `SystemEphemeralDatastore`. Setting `henge.store.type` as well fails
  startup.

`henge-redis`'s tests are a good template for testing one: the same behaviors, against a real store.
