# Design: self-orchestration

The design behind rungs 2 to 4 of Henge's [ladder](../philosophy.md#every-layer-is-opt-in): the fast
ephemeral store, the signals built on it, leases, and the self-organization they lead to. The guide shows
how to use what's built; this is why it's built that way, and the plan for the rest.

**Status.** Phases 1 to 4 (see [Phasing](#phasing)) are built: the store contract with its in-process
and Redis adapters, leases, cluster-wide rate limits, and advertisement-based routing with retries and
failover. Not built: load-weighted routing and withdrawal when overloaded, the built-in DHT, switchable
proxies with a child context per service, eviction, and self-organized role selection. Sections below say
which parts are built where it isn't obvious.

## Summary

Henge's premise is that one jar can run as a monolith or as split services, decided by deployment
configuration. This document takes that to its conclusion: a Henge cluster that decides its own
topology. Every node can host every service; nodes observe demand and resource pressure through shared,
evaporating signals and adjust what they host, like an ant colony, where workers adopt roles at random,
weighted by local stimulus, and the colony converges on what needs doing.

The end state is far away, and every step toward it ships something useful without the later ones. The
first was small: **don't construct a service on a node that can't get a lease for the scarce resources it
needs** (a database that accepts 200 connections, shared by however many nodes happen to host the service
that uses it).

## Principles

- **Inconsistency doesn't matter, by construction, not by hope.** Shared state is convergent: copies
  that diverge (a replica that missed writes, a store that restarted, a read that reached only some
  copies) settle back to one answer by merging, with no coordinator, and an incomplete view errs in a
  known direction: toward over-admitting, never toward refusing what should be allowed. Nothing needs
  consensus. Lease caps and rate limits are therefore soft, and configured with an intentional margin:
  the configured capacity underestimates the real one, so that an over-grant lands inside it. Anything that genuinely needs mutual exclusion (locks, compare-and-set,
  exactly-once) is out of scope: use a consensus store directly, outside Henge.
- **Everything can be wiped at any time.** Shared state has no persistence guarantee. Every key has a
  TTL; a wipe is indistinguishable from early expiry, and every consumer re-asserts its state on a
  heartbeat anyway.
- **Eviction first.** The default topology is a replicated monolith: every service embedded on every
  node, behind a load balancer. That is already optimal for fungible resources (CPU flows to wherever
  requests are; internal calls are method calls). Self-organization earns its keep only on
  *non-fungible* resources (connection caps, heap working sets, special hardware, credentials, failure
  isolation), so its job is to decide where a service should **not** run, not to scatter services for
  their own sake.
- **The monolith is the degenerate case, and behaves identically.** One node, in-process store, every
  lease granted (or a configuration error). Code that works as a monolith must not do anything a
  multi-node cluster would refuse.
- **Same jar, flag-selected role, every layer opt-in.** Which store backs a node, and whether it
  self-organizes at all, are deployment flags, as `henge.serve` is. A cluster can stop at any rung.

## Non-goals

- Strong consistency, locks, compare-and-set, transactions spanning members or keys.
- Henge sizing, inspecting or owning connection pools. Henge hands the user the granted lease; the user
  is trusted to size the resource from it.
- Replacing the orchestrator's autoscaler. The orchestrator decides *how many* nodes; Henge decides
  *what each node does*.

## Part 1: the fast ephemeral store

The store's contract (its operations, guarantees and the keys Henge uses) is in [The ephemeral
store](../ephemeral-store.md); the interface is `SystemEphemeralDatastore` in `henge-core`. This part is
the reasoning behind it.

### Convergent distributed state

Every key Henge uses has many writers: every node that hosts a service, holds a lease, or calls a rate
limit. What keeps that safe without consensus is the shape of the state, not agreement between writers:

```
key → { member → (value, expiresAt) }      members, written by put and claim, read by read
key → (level, at)                          a bucket, written by tryAcquire
```

- **Writers own their entries.** A member belongs to the node that wrote it: a node can only write
  members under its own node ID (`localName` lets it own several under one key). Writers never overwrite
  each other, so copies of a key merge by union.
- **A bucket merges by maximum.** A bucket's level is shared by every writer, but copies of it merge by
  taking the highest level, each leaked to now. A copy that missed some takes holds a lower level than
  the truth, never a higher one.
- **TTLs are mandatory and relative** ("30s", not a timestamp). The store computes deadlines on its own
  clock, so writers' clock skew is irrelevant. The key itself also carries a TTL, extended by every write,
  so a key whose writers have all gone eventually vanishes.
- **Reads return live members only**, plus an **epoch** identifying the storage that answered (Redis's
  `run_id`). An epoch change means "this data may have been wiped", which consumers use to tell "nobody
  is there" from "the store just restarted".

A store with one copy of each key (Redis) has nothing to merge; one that keeps several makes them
converge by these rules, and how is its own concern. Either way, each operation's behavior under an
incomplete view is known:

| Operation | Used for | Under an incomplete view |
|---|---|---|
| `put`, `read` | Advertisements; later, demand and pressure signals | A read sees fewer members than exist; writers re-assert on their heartbeat, and the next read fills in. |
| `tryAcquire` | Rate limits | The level read is correct or too low, so a limit lets a bounded few calls too many through, and never refuses one it should allow. |
| `claim` | Leases | The sum of the members read is correct or too low, so a lease can be over-granted by a bounded amount, and is never refused when there is room. |

### `tryAcquire`

Leaks the bucket for the time since it was last touched, then takes `amount` permits if they fit under
the capacity. Within one copy the check and the take are atomic; across copies, the maximum wins. The
bucket's state is `(level, at)` and nothing else. The constants (`RateLimit`: capacity, and permits per
period) are supplied by the caller on every call, so they cost no storage and no coordination, but every
caller of a key must agree on them; that's why they live in configuration (`henge.rate-limits.<name>`)
rather than at call sites. Levels are in units of 1/period of a permit, which makes the leak exact
integer arithmetic (each elapsed millisecond drains `permits` units), and `at` is the store's clock. A
drained bucket is the same as no bucket, so the key's TTL is its drain time, and nothing needs a renewal
or a sweeper. Each call is one store operation; there is no local counting.

### `claim`

Writes this node's member only if the sum of everyone else's live amounts plus this one fits the
capacity. The node's own existing member doesn't count against it, so renewing never fails against
itself. A claimed member's value is its amount, so `read` shows who holds what.

The check and the write are atomic within a copy of the key. A claim's decision depends on everyone
else's entries, so a partial view of them (a replica that missed writes, two serializers during a
failover) under-counts, and grants a claim that a complete view would have refused. That is the same
direction as a bucket's error, and handled the same way: the configured capacity is an intentional
underestimate of the real limit, so a bounded over-grant lands inside the margin.

An over-grant is also *noticed*: the next renewal reads a sum over the capacity and fails. What happens
then is the gap described under [Behavior](#behavior): today the node keeps running on a lease it no
longer holds.

### Adapters

The contract is Henge's; each adapter owns its own topology and configuration (`henge.store.<type>.*`).

| Adapter | Status | Mapping |
|---|---|---|
| **In-process** | Built; the default | One map, one lock, so `claim` and `tryAcquire` are atomic. Expiry is lazy: an expired member is dropped when its key is next touched. |
| **Redis** | Built (`henge-redis`) | Member = hash field with its own TTL (`HPEXPIRE`, Redis 7.4+). Every operation is one Lua script on one key, so `claim` and `tryAcquire` are atomic where Redis serializes the key. Redis Cluster works as is: the cluster routes each script to its key's slot, and a key's epoch is its shard's `run_id`; a failover or resharding can lose data, which is the wipe the epoch reports. No persistence needed. Valkey support is unverified. |
| **Hazelcast** | Possible, not built | Composite `(key, member)` entries, partition-aware on `key`, per-entry TTL; a read is a single-partition query. `claim` is an entry processor, run serially on the key's partition. |
| **Built-in DHT** | Not built | See below. |

### A built-in DHT (not built)

Eventually, Henge nodes hold the shared state themselves, with no separate system to run: a distributed
hash table, built into the cluster. It is not a traditional peer-to-peer DHT. It assumes **private
network usage**: a trusted cluster of tens to low hundreds of nodes, on a network the operator controls,
with the same security model as `/_henge`. That removes most of what makes open peer-to-peer systems
hard (untrusted peers, churn at internet scale, routing across nodes that can't all see each other), and
leaves the problem the cluster actually has: surviving its own rolling deploys. Its design is to come.

## Part 2: signals built on the primitive

### Advertisements (built)

Key `adv:<service>@<version>`, one member per hosting node, valued with the node's address
(`henge.advertise.url`). Load metadata (capacity, current load) is planned, for weighted routing and
self-organization.

- A node advertises once fully started (lease decisions made, web server up), as the last lifecycle
  component to start, and withdraws first on a graceful stop, before the server stops answering. It
  renews every 10 seconds, a third of the 30-second TTL.
- Callers **cache** the member set per service version and refresh it at most every 10 seconds, so
  reads are periodic, never per call. Load on a key is (hosts × heartbeat rate) + (callers × refresh
  rate). Calls rotate over the cached set.
- **Last known good on wipe**: if a read comes back empty *and the epoch changed*, the caller keeps its
  previous set for one more interval. An empty read with an unchanged epoch is believed.
- **Failover**: a call that provably never ran (no connection, or a `404`) is retried on the next
  advertised host, and a host that failed isn't offered again until the next read, unless it's the only
  one.

### Withdrawing when overloaded (not built)

The cheapest self-organizing behavior there is: a node that's overloaded stops advertising, and traffic
flows to the others. Purely local, no coordination. Rules that keep it from backfiring:

- **Measure overload as concurrency or queueing**, not CPU: in-flight calls against a concurrency limit,
  or time spent queued. A node waiting on a saturated dependency is overloaded with idle CPU, and
  withdrawing doesn't help there, so the signal should distinguish "I'm the bottleneck" from "my
  dependency is" (see [Risks](#risks), metastable feedback).
- **Withdrawal is slow; rejection is fast.** Callers' cached sets lag by one refresh interval, so while
  withdrawn the dispatcher answers `503` with `Retry-After`, and a caller receiving that drops the
  endpoint from its local cache immediately.
- **Never withdraw as one of the last hosts.** If every host is overloaded and all withdraw, the service
  has zero hosts and an overload becomes an outage. A node withdraws only if the current advertisement
  set leaves enough others (the same "only leave if others have headroom" gate as eviction); otherwise it
  stays advertised and sheds with `503`s.
- **Hysteresis and jitter**: withdraw at a high watermark, return below a low one, with a minimum
  withdrawn time and randomized return, so withdraw → load drops → re-advertise → load floods back
  doesn't oscillate, and nodes don't return in lockstep with callers' refreshes.
- **Weighting is the gentle form**: callers choose endpoints by advertised load (power of two choices);
  full withdrawal is the extreme of that continuum.
- **It feeds role selection**: withdrawals raise caller-measured unmet demand, which is the stimulus for
  another node to adopt the service. Local shedding and cluster-wide adoption are one feedback loop.

Embedded callers on the same node are unaffected; they never route through advertisements.

### Demand and pressure signals (not built)

Same shape: each node publishes its own observation as its member, readers sum or compare, and the TTL
is the evaporation rate. **Demand is measured at the caller** (attempted calls, latency, failures, but
retries don't count as new demand), because a service nobody hosts produces no provider-side signal.

## Part 3: leases (built)

### Problem

A database accepts 200 connections. With every node able to host the service that uses it, the number of
pools, and so of connections, scales with the node count until the database refuses connections. The
cluster needs to cap how many nodes construct that service.

### Shape

A lease names one real resource. Its provider builds that resource once per node, sized from the grant;
services ask for the resource:

```java
@LeasedResource("inventory-db")
public class InventoryDatabase implements ResourceProvider<DataSource> {
    public DataSource open(Lease lease) {
        HikariConfig config = /* ... */;
        config.setMaximumPoolSize(lease.amount());       // size the resource FROM the lease
        return new HikariDataSource(config);
    }
}

@ServiceVersion(value = InventoryService.class, version = 1)
public class InventoryServiceImpl extends InventoryServiceSkeleton {
    public InventoryServiceImpl(@RequiresLease("inventory-db") DataSource database) { ... }
}
```

```yaml
henge:
  leases:
    inventory-db:
      capacity: 180          # cluster-wide; set below the real limit (200) as a margin
      amount: 20             # what one node claims, shared by everything on it that uses the lease
```

- **A lease name is a resource.** `henge.leases.<name>.capacity` names one real cluster resource, so the
  name is also its identity on a node: every version of every service that declares the lease shares one
  resource and one claim. A consumer that needs its own resource declares its own lease. Sharing is
  opt-in, by naming the same lease.
- **The amount belongs to the lease, not to a service.** `henge.leases.<name>.amount` is what one node
  claims. The resource is built once at that size; it can't depend on which consumers happen to be
  hosted, since sizing it from a changing mix would mean resizing a live pool. How the amount should
  change while several versions are live is the operator's to decide, through that one number.
- **A provider is optional, and a `Lease` parameter is the explicit way around one.** A provider is a
  class annotated `@LeasedResource("<lease>")` implementing `ResourceProvider<T>` (`T open(Lease)`, and a
  `close(T)` that by default closes an `AutoCloseable`), built through Spring like an implementation, at
  most one per lease. A constructor parameter marked `@RequiresLease("<lease>")` means one of two things,
  by its type:
  - `Lease`: the name and the per-node amount. The implementation builds its own resource, which is the
    user's choice to bypass the provider; several services doing that each build one, against one claim.
  - anything else: the provider's resource, which must be assignable to the parameter. A lease with no
    provider can't satisfy one, and startup says to take a `Lease` instead.

  Providers are found by scanning (static metadata, no instantiation); the resource type comes from the
  provider's generic parameter, and one that can't be resolved fails startup.
- **`@RequiresLease` is for constructor parameters only.** A consumer's leases are fully described by its
  parameters. The lease is named on every such parameter, never inferred from the type: two providers can
  produce one type, and an ordinary bean parameter must not silently become a leased resource.

### Behavior

- **At startup, and only then**, for each embedded service version that declares leases, the node
  acquires **every** lease it needs, in lease-name order. All granted: the implementation is constructed
  as usual. Any refused: what was acquired for it is released, and the service is reached remotely; its
  implementation, and so its resource, is never built on this node. Grants are all-or-nothing.
- **The decision is the binding's target.** The registrar runs before any bean exists, so it can't consult
  the store; a factory bean per leased service decides when it's created, and the service binding's
  target becomes either the implementation or the transport. Callers inject exactly as they would
  otherwise. The dispatcher's registry is computed after those decisions, so `/_henge` never serves a
  service this node declined to build.
- **One claim per node per lease**, under one member for the node, not one per service version. Every
  service version that holds the lease references it; the last reference going away (a service stopped,
  or failed to construct) closes the provider's resource, if there is one, and hands the claim back.
- **Held claims are renewed** on a heartbeat of a third of the TTL (30 seconds), and expire with the node,
  so a crash returns its capacity one TTL later.
- **A lost lease isn't given up yet.** A renewal that finds the cluster over capacity (an over-grant,
  noticed) writes nothing, so the node's claim lapses after its TTL, but its services keep running on the
  resource: the cluster then counts less than is really in use. The fix is **de-allocation**: a node that
  loses a lease stops hosting the services on it, closes the resource, and reaches them remotely
  instead. That needs a service that can switch from embedded to remote while running, so it waits on
  phase 5, and it is the first thing phase 5 is for.
- **A refusal is final for the process's life.** Taking up capacity freed later means switching a running
  service from remote to embedded, which needs the switchable proxies of phase 5 (see [Open
  questions](#open-questions)).
- **A service with several leases stays all-or-nothing**, and different leases are independent keys with
  independent capacities. A node that holds lease X for one service and is refused Y for another sends
  the second remote; X stays held by the first.
- **The static check is one node's claim**: a lease's amount must fit its capacity.

In monolith mode the in-process store always grants, unless a single claim exceeds the capacity, which is
a startup configuration error.

### Acquisition

A claim is one `claim` per lease: the store sums the live members, and either writes this node's member
and grants, or refuses. There are no intermediate states and no settle interval; with one copy of the key
(Redis), racing claimants are ordered by whoever Redis sees first, and the loser is refused. A rollback after a
partial grant leaves capacity briefly claimed by a node that then declined, and a claimant that lost a
race to it is refused for that moment. Claiming in a fixed order keeps two nodes from refusing each
other's multi-lease services in a cycle.

Planned refinements, not built:

- **A speculative read first**: if the live members already leave too little, give up without writing.
  A pure optimization, which keeps a full lease from turning every starting node into a write.
- **A grace period after a wipe**: a claimant that sees the epoch change waits one heartbeat before
  claiming, so existing holders re-assert first and a wipe doesn't look like free capacity.

An over-grant is still possible whenever the store's view is incomplete (a Redis failover, say). The
intentional margin absorbs it, and de-allocation, once built, corrects it.

### Decided constraints

- **Incompatible with `henge.remote-url-template`.** Template routing assumes every node behind a DNS
  name hosts the service; with leases, some don't. A process combining leased services with template
  routing fails at startup. There are two supported modes: orchestrator-managed routing (rung 1: the
  orchestrator owns placement, no leases) and advertisement-based routing (rungs 2 and 3: Henge owns
  placement).
- **User responsibilities, documented, not enforced:**
  - The leased resource belongs to the provider (or to a service that takes the `Lease`), never to a
    shared application bean. Shared beans, and startup-time users like JPA/Hibernate metadata, Flyway and
    Liquibase, open connections regardless of which services were built.
  - The resource is sized from `Lease.amount()`. Henge only does bookkeeping; it never sees a connection.
- **Noisy neighbors are the cost of sharing.** Services on one lease contend for the one pool; that is
  what sharing means, and naming the same lease is how a user chooses it.

The lease's resource will live in a Henge-managed context above the versions' once each version has a
child context (phase 5), and Spring's destroy ordering will do the teardown. That is the same shape the
isolated tier and eviction need, with one difference: the resource is shared between sibling contexts
instead of private to one. A binding leaving its local target is where its reference to the lease is
dropped, and a drained version releasing its share is what lets a deprecated version stop costing
capacity.

## Part 4: self-organization (long term)

### Mechanism

Eviction first: start from the replicated monolith; nodes stop hosting a service when hosting it here is
costly or harmful, and only if others can take it.

- **Role selection** follows the response-threshold model (Bonabeau, Theraulaz & Deneubourg): a node
  engages a task with probability sⁿ / (sⁿ + θⁿ) for stimulus s and threshold θ; thresholds that fall
  with practice produce specialization. Randomization is what prevents every node from reacting
  identically to the same signal.
- **Memory pressure**: evict a service at random (weighted by measured allocation;
  `ThreadMXBean.getThreadAllocatedBytes` around dispatch is cheap) until pressure clears, where:
  - pressure is **post-GC old-generation occupancy**, not RSS or instantaneous heap;
  - eviction happens only if other nodes **advertise headroom**. Otherwise pressure is cluster-wide, and
    the answer is more nodes (the autoscaler's job), not shuffling load onto nodes that are already full;
  - each step waits for a GC cycle to observe its effect (hysteresis);
  - evictions are **remembered** as an evaporating "X hurt me here" signal, so a node is slower to
    re-adopt X, and a service that hurts nodes everywhere is an alertable leak.
- **Floors and constraints**: a minimum number of hosts per service, and declared constraints for
  services that can't move freely (stateful, hardware, credentials).
- **Locality**: a node that calls X heavily may choose to keep X embedded, collapsing hot call-graph edges
  into method calls.
- **Shared-lease affinity**: a node's cost for a set of services is the number of *distinct* leases they
  declare, since consumers of one lease share a claim (see Part 3). Placing services that use the same
  lease together saves a whole claim, so a role-selection objective should prefer co-locating
  lease-sharing services, as it prefers keeping a hot call-graph edge in-process.
- **Version migration drains itself**: as callers move to v2, demand for v1 evaporates.

### What it requires from Henge

1. **A stable proxy at every injection point, embedded included**, whose target can switch between
   local, remote and draining, so callers never hold a direct reference into something that may be
   destroyed. Built as the *service binding*, below, because metrics, strict-mode round-tripping and
   switching all want the same interception point, and must not each add their own proxy.
2. **A child application context per service version** (not built), holding the resources the service
   owns. Closing it is real deallocation: Spring runs every bean's destroy logic in reverse dependency
   order (pools close, executors stop). This is the same mechanism the roadmap's isolated mode needs (see
   [Scope](../scope.md#roadmap)).
3. **Drain before evict** (not built): stop advertising, wait at least one TTL plus in-flight calls, then
   close.
4. Limits that remain: resources outside Spring's lifecycle (hand-started threads, static caches,
   `ThreadLocal`s, global JDBC driver registration) leak; class metadata stays loaded; activating a child
   context costs tens to hundreds of milliseconds plus warmup, so roles must not thrash.

### The service binding (built; retargeting and draining are not)

One `ServiceBinding` per `service@version`, and one JDK proxy over it, registered under the version's
bean name, as primary and qualifier. Every injection point holds that proxy: an embedded implementation,
a leased one and a remote one are all a binding that differs only in its target.

- **Target.** Either *local* (the implementation, always the hidden, non-autowire `<name>-<version>.impl`
  bean) or *remote* (the `ServiceTransport`). A lease refused at startup is a binding whose target is
  remote.
- **Two paths in.** A caller goes `proxy → interceptors → target`. The dispatcher goes through
  `invokeLocal`, which skips the caller-side interceptors (the call already went through them on the
  calling node). These are the only two places a call enters, so retargeting and draining will add their
  in-flight accounting there and nowhere else.
- **Interceptors** (`ServiceCallInterceptor` beans) run over the `ServiceInvocation`, in `@Order`:
  observation outermost, then (when built) strict round-tripping, then the target. Observation reads the
  current target's mode at call time, so its `mode` tag stays right after a switch.
- **Exceptions** from a local target are unwrapped, so a caller sees what the implementation threw.
- **Hosting follows the target.** A version is hosted here when its binding's target is local, so the
  advertiser, the topology report and the dispatcher's registry follow a switch with no further wiring.

Not built until eviction needs them: retargeting (a target that can change, so a mutable field where
there is a final one now), draining and its in-flight counting, and strict mode, each an addition to the
binding and not a new proxy. Retargeting away from local must destroy the implementation before it
releases the version's lease reference, since the implementation stands on the lease's resource.

Callers hold the interface, so an implementation is not something to inject by its concrete class: it is
the hidden bean `<name>-<version>.impl`. It is still the only bean of its class, so
`getBean(Impl.class)` finds it, but nothing autowires it, and `getBeansOfType` of the interface lists it
beside the proxy (a `List<Interface>` injection sees only the proxy).

## Risks

Ranked by severity. These are why the later phases are gated on simulation.

1. **Metastable feedback.** X slows → callers retry → measured demand for X rises → nodes move to X and
   drop Y → Y slows → cascade. Worse, if X is slow because *its* dependency is saturated, adding hosts
   makes it worse, and a demand signal can't tell the difference. Needs backpressure signals from the
   bottleneck, retries excluded from demand, and damping.
2. **No measurable objective.** The JVM has no per-service heap accounting and only approximate
   per-thread CPU. Allocation rate is a proxy, not retained size.
3. **Lost failure isolation.** Nodes hosting a shifting mix spread a misbehaving service's blast radius.
   Splitting must be able to isolate deliberately.
4. **Least privilege.** If any node can become any service, every node needs every credential.
   Credential-bearing services need hard pinning.
5. **Operability.** "Where is X and why did it move?" has no static answer. A freeze/pin switch, a logged
   reason for every role change, and topology history are requirements, not polish.
6. **Two control loops.** The autoscaler reacts to node CPU; role changes move node CPU. One loop must be
   clearly slower than the other.
7. **Partition heal.** Each side independently adopts roles; healing produces double coverage, then a
   mass drop.

The signaling cost itself is not a risk: (nodes × services) members per refresh is small at any plausible
scale.

## Phasing

Each phase is independently useful and testable, and maps onto the ladder's rungs.

1. **Store contract and in-process adapter** (built): the contract in `henge-core`, including `claim`
   and `tryAcquire`, and its wiring in `henge-spring`. *Rung 2's foundation; every node has a store.*
2. **Leases and rate limits** (built): the lease decision as the binding's target, the late dispatcher
   registry, providers, `Lease` injection, startup rejection of leases with a URL template;
   `@RateLimited` limiters over `tryAcquire`. Fully testable in monolith mode. *Rung 3.*
3. **Advertisements and advertisement-based routing** (built, except load-weighted endpoint choice and
   withdrawal when overloaded: calls rotate over what's advertised), with retries and failover. *Rung 2.*
4. **Redis adapter** (built): proves the contract against a store Henge doesn't control, and makes rungs 2
   and 3 cluster-wide.
5. **Switchable proxies and a child context per service**, and with them **de-allocation**: a node
   that loses a lease stops hosting the services on it and closes the resource. This is on the critical
   path to usability, since until it exists an over-granted lease is never given back. It also unblocks
   the roadmap's isolated mode. The stable proxy (the service binding) is built; retargeting, draining and
   the child contexts are not. *Rung 4 begins.*
6. **Eviction**: memory pressure and misbehavior, drain, remembered evictions, on the same mechanism.
7. **Built-in DHT**: a fast ephemeral store built into the cluster, for private networks. *No separate
   system to run.*
8. **Self-organized role selection**, preceded by a discrete-event simulation of the decision loop, with
   slow dependencies and partitions injected, before any of it touches a real cluster.

## Open questions

- **Heartbeat defaults** for leases: fixed, or derived from the adapter?
- **Taking up a lease after startup.** Today a refusal is final for the process's life; a periodic retry
  would let a node pick up capacity freed later, at the cost of switching a service from remote to
  embedded at runtime, which needs phase 5.
- **Valkey** hash-field TTL support, to verify.
- **Partial grants.** The API (`amount()`) leaves room; leases are all-or-nothing today.
