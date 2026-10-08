# Design: partition tolerance

What a Henge cluster does when its network splits. The assumed partition is **global**: every node loses the
store at once. That is what the networks a cluster like this is deployed in produce, with the nodes and their
store in one failure domain, so that what cuts one off cuts them all off. Henge's answer to it is "sit still and
heal", and that is right.

A **partial** partition, where some nodes lose the store and others don't, is the rare exception. This document
is mostly about it: what it costs, how it is repaired, and the small things that make it visible and cheaper.

**Global redundancy is not a bigger cluster.** Running across regions, or availability zones that partition
routinely, is *clusters of clusters*: a new layer above this one, with its own design. A cluster stays as it is,
with one store, one failure domain and one set of capacities. [Beyond one cluster](#beyond-one-cluster) says what
this layer hands to that one, and nothing more.

**Status.** Not built, and not decided. This is the analysis and a direction. [Decisions](#decisions) are the
ones the analysis forces; [open questions](#open-questions) come with a recommendation. The behavior that exists
today is in [failure modes](../failure-modes.md); the machinery this builds on is in
[self-orchestration](self-orchestration.md) and [lease healing](lease-healing.md).

## The assumption

Everything Henge keeps in the [ephemeral store](../ephemeral-store.md) is written as if the store were one
logical thing that a node either reaches or doesn't. The failure policy follows from that: a node that can't
reach it **sits still**, keeping its leases, its pool, its run claims and its routes, and fails honestly (`503`)
for what it can't answer. In a global partition that is right, because nobody else can have gained anything:
the node's claim hasn't been given to another, only left unrenewed, and when the store returns every holder
re-asserts. ([Lease healing](lease-healing.md#trust-less-and-wait-less) covers the one catch: an outage longer
than a lease TTL comes back as an empty store.)

In a partial partition it stops being right. The cut-off node's claim lapses, another node is granted the
capacity, and the cut-off node, sitting still, keeps using it. The overlap lasts **as long as the partition**,
not a lease TTL, and it ends only by a refused renewal, which needs the store to answer. Henge accepts this
because it is rare, and repairs it at the heal. It does not prevent it.

## The shapes of a partition

| Shape | What each side sees | Where it happens |
|---|---|---|
| **Global: every node cut from the store** | Every node sees the store away. | The store's host or its link, a site-wide network fault. **The assumed case.** |
| **A. Some nodes cut from the store** | Cut nodes see the store away. The rest see the cut nodes' entries lapse. | A bad switch, a security group, one rack. Rare. |
| **B. Nodes cut from each other, the store reaches both** | The store thinks every node is healthy and advertised. Callers can't reach some of them. | Asymmetric routes, a flapping link. Rare. |
| **C. Part of the store away** | Some keys fail or come back wiped; the others are fine. | A sharded store losing a node. Not a network partition: a partial outage, then a partial wipe, which [the contract](../ephemeral-store.md#the-model-convergent-distributed-state) already allows, since each key stands alone. |

The global case is [the store being away](../failure-modes.md#the-store-is-away), and needs nothing here. A is the
rare case that costs over-grant. B is a failure the store can't report. C is a partial outage, and the one place it
isn't yet tolerated like a wipe is [the guard](#the-guard-is-one-state-for-the-whole-store).

Two clusters that can't reach each other is not a shape of this list. Each is whole, with its own store, and what
they owe each other is the next layer's to define.

## What each primitive does under a partial partition

| Primitive | Under a partial partition |
|---|---|
| **Advertisements** (`adv:`) | **Safe.** The cut-off nodes' entries lapse, and the rest stop routing to them. Shape B is the exception: advertised, but not reachable. |
| **Rate limits** (`rate:`) | **Over by the cut-off nodes' shares.** Shape A: the nodes still reaching the store share the whole limit, and each of the *k* cut off adds its local share of 1/N, so the cluster lets through up to (1 + *k*/N) times the limit, under twice it. The shares only add up to the limit in a global partition. |
| **Leases** (`lease:`) | **Over-granted by what the cut-off nodes hold.** Their claims lapse, the rest of the cluster fills the capacity, and the cut-off nodes still use theirs: at most the capacity again, so up to twice it. Repaired at the heal, by a refused renewal and the give-up, not prevented. |
| **Scheduled jobs** (`cron:`) | **Run twice.** The cut-off node's run claim lapses, and a fire that comes during the partition gives the run to another node, while the first runs on. Jobs are required to be safe to run twice, so this is a duplicate run, not corruption. It lasts until the partition heals or the first run reaches its `maxRuntime`, an hour by default, whichever is sooner, if the job honors the interrupt. |
| **Channels** | **Safe.** Open channels never needed the store; a new one needs a known backend. |

The exclusion primitives (a lease's capacity, a job's single run) can't be both available and exact across a
partition. Henge chose availability and a bounded over-grant, and that choice is right, more so when the case it
gives up exactness for is rare. What is missing is that nothing shows the overlap while it happens.

## Decisions

- **Partitions are assumed global.** Sitting still is designed for that case and stays the policy. A partial
  partition is repaired, bounded by what the cut-off nodes hold, and made visible, but not designed around.
- **Keep availability over exactness.** A partition does not stop a side from serving. Anything that needs
  mutual exclusion or exactly-once is still [a system built on consensus](../guide/06-leases-and-rate-limits.md#soft-limits),
  not this.
- **One cluster, one store.** A store whose keys fail separately is a source of partial outages, handled as partial wipes. A store
  that splits itself is out of scope: it is extra unlikely, and the margin below the real limit covers it.
- **Global redundancy is clusters of clusters,** a new layer. Nothing in a cluster learns about other clusters to
  make it possible.
- **No new coordination.** A node acts on what it reads and what it did itself.
- **Behavior that gives up availability is opt-in.** Henge adds no deadline of its own to an outage, and that
  stays the default.

## Within one cluster

Three small changes, each useful alone.

### The guard is one state for the whole store

`GuardedDatastore` keeps one failure state per process, so that every use of the store shares a view of whether
it is reachable. On a store that fails as one, that is right. On one whose keys fail separately it isn't: a call
that fails on a dead key puts the guard into backoff, and every call to a healthy key fails fast with
`StoreUnavailableException` until the next probe gets through. The probes that land on healthy keys succeed
and end the outage, so the guard flaps. What should have been a partial outage, tolerated like a partial wipe,
reads as a store that is intermittently away for everything: advertisements and leases on healthy keys miss
beats, rate limiters degrade, and services that need a lease answer `503`.

**Fix.** Keep the guard's state per key, so that a key that fails backs off alone. It is per key and not per
whatever the store groups keys by on purpose: which keys fail together is the store's business, and the
contract says each key stands alone. It needs no store change, and on a store that fails as one, where every
key fails together, the behavior is as today. The test is a store whose keys
fail separately.

Two details keep it cheap:

- **State only for keys that are failing.** The keys are not a small fixed set: every fire of a job is a key of
  its own (`cron:<job>@<instant>`). A success removes the key's state, so the guard holds only what is failing.
- **Logged once per outage, not once per key.** A global outage is the common case, and it fails every key. The
  guard logs when the first key starts failing and when the last one recovers, naming how many failed.

The [outage-ended signal](lease-healing.md#trust-less-and-wait-less) becomes per key too, which is what the
poller wants: the lease it is about to read is the one that matters.

### A caller remembers a host it couldn't reach

A host that fails a call is not offered again until the next routing read, about 10 seconds, and then is offered
again, with a 2 second connect timeout, each time. For a host that is advertised and unreachable from this
caller (shape B), that is a slow failure every refresh, for every caller, for the length of the partition. The
store can't say it, because the store doesn't see the link.

**Fix.** A caller keeps its own memory of a host that failed to connect, and offers it again after a backoff that
doubles with each failure, to a cap, and resets on a success, as the lease poller does for a full lease. It
needs no store, no peers and no agreement: a node acting on what it saw itself. A host that is the only one is
still tried.

### Seeing the overlap

A gauge of the seconds since each lease was last renewed, per node. In a global outage it rises on every node
together, which is harmless. A node whose number rises past the TTL while the others' stay low is cut off alone,
and may be over-granting. That is the alert, and it is the only sign of shape A that doesn't wait for the heal.

## Not recommended: bounding how long a node sits still

A lease could opt into giving itself up after going unrenewed for some limit (`unrenewed-limit: 5m`), as if it
had been refused. That turns an overlap bounded by the partition into one bounded by the limit. Its price lands
on the assumed case: a node can't tell a global outage from being cut off alone, so a global outage past the
limit takes the service down on every node at once, to shorten a rare overlap that the margin already pays for.

So it is not built unless a deployment shows partial partitions that are long and frequent, and that deployment
is more likely a reason for the next layer. If it is built, it depends on
[lease healing's stuck 1](lease-healing.md#stuck-1-a-failed-eviction-is-never-retried): a give-up that runs with
the store away has to complete locally.

## Beyond one cluster

Clusters of clusters is its own design. What this one hands to it:

- **A store is never stretched across a WAN.** One store spanning regions is a store that splits, which a cluster
  rules out. Each cluster keeps its own store.
- **Exclusion stays inside a cluster.** A lease's capacity and a job's single run are checked against one store.
  A capacity that spans clusters is the next layer dividing it between them, each cluster holding its part as an
  ordinary capacity. Borrowing unused room between clusters is coordination, and belongs to that layer.
- **"Once per fire" means once per cluster.** A job meant to run once across clusters needs the next layer to
  say which cluster runs it.
- **A call to another cluster crosses the link most likely to fail,** and keeps the same rule as any call: retried
  only when it provably never ran.

## What the vulnerable periods become

| Today | With this |
|---|---|
| Part of the store away makes all of it look intermittently away. | The keys that fail back off alone; the rest carry on. |
| An unreachable advertised host costs a slow connect on every refresh. | One slow connect per backoff step, doubling. |
| A cut-off holder overlaps for the whole partition, up to one extra capacity, and nothing shows it. | The same overlap, now visible as one node's renewal age rising alone. |

## Testing

A partial partition can't be tested by turning the store off, which is the global case. It needs a node-by-node
reachability matrix.

- **A fault-injecting store wrapper** with a per-node switch (the guarded store's decorator shape), so a test can
  cut one node from the store and not the others, and heal it. It also fails keys separately.
- **A fault-injecting transport**, for shape B: node X can't reach node Y while the store can reach both.
- **A Docker run** with the cluster behind a proxy that can drop one pair of links (toxiproxy or `iptables`),
  repeating the measuring loop of [lease healing](lease-healing.md#measuring-it): the most claims ever held
  against the capacity, and for how long.
- **A simulation of the decision loop**, as [self-orchestration](self-orchestration.md#phasing) wants before role
  selection touches a real cluster, with partitions injected. The same harness.

## Phasing

Each phase is useful alone.

1. **The guard per key.** A defect against the assumption that keys fail separately, and the only item here that
   affects a store in normal operation (one sharded across servers, such as Redis Cluster).
2. **The renewal-age gauge** and **the caller's memory of an unreachable host.** Small, and they make shape A and
   shape B visible and cheaper.
3. **The harness**, when there is something partial-partition-specific to judge. Today the overlap is
   understood from the code, and the gauge shows it in production.

## What stays open

- **The overlap in shape A** is repaired, not prevented, and the margin below the real limit pays for it.
- **A store that splits itself** is out of scope, and covered only by the margin.
- **A hard guarantee** still belongs in a system built on consensus.

## Open questions

- **The built-in DHT breaks the assumption.** A store that lives in the nodes partitions *with* them: a cut
  inside the cluster is no longer global, it splits the store too, and each side is a cluster with a store
  that believes it is whole. That is shape D inside one cluster, the case the single-store model rules out.
  The built-in store takes this up ([the minority rule, per family](subcluster-store.md#failure-partition-and-lifecycle)):
  a store node that reaches fewer than half of a family's sub-cluster treats that family as away, which brings a
  split back to the bound of shape A, one family at a time.
- **Whether shape B deserves more than the caller's memory.** A host advertised and unreachable is invisible
  to the store; callers back off from it independently. Anything that pooled what callers saw would be new
  coordination, so the recommendation is no.
