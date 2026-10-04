# Design: self-orchestration

Status: phases 1-4 are built; phases 5-8 are design only.

Built: the datastore contract with its in-process and Redis adapters (phases 1 and 4), `@RequiresLease`
(phase 2), advertisement-based routing (phase 3, minus load-weighted choice and withdrawal when
overloaded: calls rotate over what's advertised), and retries of calls that provably never ran (failed
connection, "not served here"), with failover to the next advertised host. Cluster-wide rate limiters
(`tryAcquire`, exposed as `@RateLimited` beans configured under `henge.rate-limits`) are built too.

Not built: the built-in DHT, switchable proxies with a child context per service, eviction, and
self-organized role selection (phases 5-8). Those are gated on a simulation of the decision loop.

## Summary

Henge's premise is that one jar can run as a monolith or as split services, decided by deployment
config. This document takes that to its conclusion: a Henge cluster that decides its own topology.
Every node can host every service; nodes observe demand and resource pressure through shared,
evaporating signals and adjust what they host — like an ant colony, where workers adopt roles at
random, weighted by local stimulus, and the colony converges on what needs doing.

The end state is far away. The first deliverable is small and useful on its own: **don't construct
a service on a node that can't get a lease for the scarce resources it needs** (a database that
accepts 200 connections, shared by however many nodes happen to host the service that uses it).
Everything is ordered so each phase ships something usable without the later ones.

## Principles

- **Inconsistency doesn't matter — by construction, not by hope.** Nothing in this design needs
  consensus. Every piece of shared state is shaped so that divergent replicas converge (union,
  higher version wins, expiry) instead of multiplying. Rate limiting is already a temporally
  inconsistent model (it depends on local clocks); a lease cap is soft and configured with a margin.
  Anything that genuinely needs mutual exclusion (locks, compare-and-set, exactly-once) is out of
  scope — use a real consensus store directly, outside Henge.
- **Everything can be wiped at any time.** Shared state has no persistence guarantee. Every key
  has a TTL; a wipe is indistinguishable from early expiry, and every consumer re-asserts its state
  on a heartbeat anyway.
- **Eviction first.** The default topology is a replicated monolith: every service embedded on
  every node behind a load balancer. That is already optimal for fungible resources (CPU flows to
  wherever requests are; internal calls are method calls). Self-organization earns its keep only on
  *non-fungible* resources — connection caps, heap working sets, special hardware, credentials,
  failure isolation — so its job is to decide where a service should **not** run, not to scatter
  services for their own sake.
- **Monolith is the degenerate case, and behaves identically.** One node, in-process store, every
  lease granted (or a config error). Code that works as a monolith must not do anything a
  multi-node cluster would refuse.
- **Same jar, flag-selected role.** Whether a node stores shared state, which store backs it, and
  whether it self-organizes at all are deployment flags, consistent with how `--henge.serve`
  works today.

## Non-goals

- Strong consistency, locks, CAS, transactions spanning members or keys.
- Henge sizing, inspecting or owning connection pools. Henge hands the user the granted lease; the
  user is trusted to size the resource from it.
- Replacing the orchestrator's autoscaler. The orchestrator decides *how many* nodes; Henge decides
  *what each node does*.
- Kademlia's k-bucket routing in the first DHT (see Open questions).

## Part 1 — The shared store (`SystemEphemeralDatastore`)

### The one primitive: an expiring, single-writer-per-member map

```
key → { member → (value, version, expiresAt) }
```

- **A member has exactly one writer**: the node (process boot) that owns it. The API enforces this
  by construction — a node can only write members under its own node ID.
- **Merge rule**: per member, the higher version wins; expired members are dropped. That merge is
  commutative, associative and idempotent, so redundant writes, replays, retries and union reads are
  all harmless and need no quorum.
- **Versions** are `(bootId, sequence)`, assigned by the writer. A new boot is a new writer.
- **TTLs are mandatory and relative** ("30s", not a timestamp). The store computes deadlines on its
  own clock, so writer clock skew is irrelevant. The key itself also carries a TTL, refreshed by
  every write, so a key whose writers have all gone eventually vanishes.
- **Reads return live members only**, plus an **epoch** token identifying the storage that
  answered (a node boot ID set for the DHT, `run_id` for Redis). An epoch change means "this data
  may have been wiped", which consumers use to tell "nobody is there" from "the store just
  restarted".

Sketch (lives in `henge-core`, no Spring; the in-process adapter and the real Javadoc are in the code):

```java
public interface SystemEphemeralDatastore {
    /** This node's ID, stable for the life of the process. */
    String nodeId();

    /** Write (or renew) this node's member {@code localName} under {@code key}. */
    void put(String key, String localName, byte[] value, Duration ttl);

    /** Remove this node's member early (graceful deregistration). */
    void remove(String key, String localName);

    /** Live members of {@code key}, across all writers, plus the epoch of the answering storage. */
    Snapshot read(String key);

    record Snapshot(Map<MemberId, byte[]> members, Epoch epoch) {}

    /**
     * Atomically: if the sum of the live members' amounts under {@code key} plus {@code amount}
     * is at most {@code capacity}, write (or renew) this node's member and return true; else
     * write nothing and return false. A member that already exists counts as its own old
     * amount, not twice, so renewing never fails against itself.
     */
    boolean claim(String key, String localName, int amount, int capacity, Duration ttl);
}
```

`MemberId` is `(nodeId, localName)`; a claimed member's value is its amount (a 4-byte `int`). `localName` lets one node own several members under a key
(two services on one node each claiming the same lease, say) without breaking single-writer.

Everything else above the store is built from `put`/`read`: advertisements, demand and pressure
signals. `claim` (leases) and `tryAcquire` (rate limiters, below) are the only operations that must be
**atomic per key**. It is still not consensus: each node writes only its own member,
and atomicity is only ever required of whoever serializes operations on that one key (see the
adapters, and the DHT's single-owner placement for claim keys). Where that serialization is
briefly doubled or lost, the damage is bounded overshoot, which the lease capacity margin absorbs.

### Rate limiters: `tryAcquire`

A rate limiter is not built from `put`/`read` after all: a leaky bucket is one scalar that every node
draws on, and "is there room?" must be answered and applied at once, which is `claim`'s problem again.
So there is a second atomic-per-key operation, `tryAcquire(key, amount, RateLimit)`, under the same
terms as `claim` (serialized per key; where that briefly fails, the rate overshoots by a bounded amount).

The bucket's state is a scalar `(level, at)` and nothing else. The constants (`RateLimit`: capacity,
permits per period) are supplied by the caller on every call, so they cost no storage and no
coordination, but every caller of a key must agree on them. Levels are in units of 1/period of a permit,
which makes the leak exact integer arithmetic (each elapsed millisecond drains `permits` units), and
`at` is the store's clock. A drained bucket is the same as no bucket, so the key's TTL is its drain
time and nothing needs a lease, a renewal or a sweeper. Redis runs it as one `EVALSHA`.

### Why not "a subset of Redis"

The first sketch was a Redis command subset (`GET`/`SET NX`/`INCR`/`MULTI`). Each of those needs a
single owner per key to be meaningful, which a *redundant* DHT can't provide without consensus.
Replicated counters with independent increments don't converge — they drift toward k× the true
value. The single-writer map does converge, and it covers every use case on the table except one:
a capped claim (leases) can't be expressed as a merge, because "is there room?" depends on the
other members at the moment of writing. That one operation is `claim`, and rather than generalize
back toward a Redis subset it is kept as a single purpose-built atomic operation, served from a
single owner per key. Everything else stays on the converging map.

### Adapters

The contract is Henge-owned; each adapter owns its own topology and config.

| Adapter | Use | Mapping |
|---|---|---|
| **In-process** | Monolith; tests | Lazy expiry (an expired member is dropped when its key is next touched), one lock, so `claim` is atomic. |
| **Redis / Valkey** | Teams that already run Redis | Member = hash field with per-field TTL (`HSETEX`/`HGETALL`, Redis 7.4+; Valkey support to verify). Version check on write is a small Lua script; `claim` is another (sum live fields, compare, write), atomic because Redis is single-threaded. Older Redis: sorted set scored by expiry + value hash, Lua scripts, server `TIME`. Redis Cluster works as is: every operation is one script on one key, so the cluster routes it to the key's slot (a key's epoch is its shard's `run_id`); a failover or resharding can lose the data, which is the wipe the epoch reports. No persistence needed. |
| **Hazelcast** | Teams that already run it | Composite `(key, member)` entries, partition-aware on `key`, per-entry TTL; read is a single-partition query. `claim` is an entry processor, run serially on the key's partition. |
| **Built-in DHT** | Henge nodes are the store | See below. |

Every adapter satisfies the contract trivially except the DHT, which is the only one where
replicas can actually disagree — and the merge rule is what makes that fine for `put`/`read`. For
`claim`, the DHT does what Redis does: one owner per key serializes it.

### The built-in DHT

Goal: Henge nodes hold the shared state themselves, with no extra infrastructure, and survive the
most common event in the cluster's life — **a rolling deploy that kills storage nodes while new
nodes are trying to read bootstrap state from them**.

- **Membership**: seeded from the orchestrator (a headless Kubernetes `Service` returns every ready
  pod, old binaries and new). Bootstrapping depends on DNS, never on the DHT itself. Nodes exchange
  membership (gossip) and keep a **full membership view** — a trusted cluster of tens to low
  hundreds of nodes can afford it, which makes every operation one hop.
- **Node identity**: a random ID per process boot. A restarted node is a new node at a new
  position; there's never a "same position, empty memory" node, and the ID doubles as the epoch.
- **Placement**: XOR distance between `hash(key)` and node IDs, take the k closest live nodes.
  (Rendezvous hashing balances load better with full membership; XOR keeps the door open to
  Kademlia routing later. Placement sits behind a `closestK(key, view)` function, so the choice is
  cheap to revisit.)
- **Redundant writes**: every `put` goes to the k closest nodes in the writer's view.
- **Union reads**: a read asks several of the k closest and merges.
- **Single-owner claims**: `claim` goes to exactly one node, the closest live node to `hash(key)`
  in the writer's view, which applies it serially (the DHT's version of "Redis is
  single-threaded"). So the DHT has two placement modes: k closest with merge for ordinary keys,
  k = 1 for claim keys. Making that safe without redundancy rests on three things:
  - **Loss is fine.** Holders re-assert on heartbeat, so a lost owner's state rebuilds within one
    heartbeat. A node that takes ownership of a claim key (new key owner, or its first boot)
    refuses claims for one heartbeat interval first, so a wipe doesn't look like free capacity.
  - **Graceful handoff carries claim state** to the next-closest node on SIGTERM, which keeps
    ordinary rolling deploys from ever hitting the grace period.
  - **Doubled ownership is bounded.** During churn two nodes with different views can both
    believe they own the key, each granting up to the full capacity, so the worst-case overshoot
    is a multiple of capacity, not just the margin. A claimant that sees its view change
    mid-claim, or can't reach the owner, **fails closed** (refused) rather than guessing. The
    capacity margin must still be sized for this case, and that is documented.
- **Trusting writes and reads outside responsibility**: a node accepts a write for a key it doesn't
  think it's responsible for (the writer's view may differ during churn) and answers reads from
  whatever it holds. Misplaced data simply ages out. This is safe only because the cluster is
  trusted (same security model as `/_henge`: network isolation) and the merge rule makes extra
  copies harmless.
- **Graceful handoff**: on SIGTERM (Spring graceful shutdown), a node removes its own members and
  pushes the data it holds to the next-closest nodes before exiting. That makes the normal rollout
  path lossless; redundancy only has to cover crashes.
- **Rollout constraints** (documented, and checked where possible):
  - `k` > the orchestrator's max simultaneously-unavailable pods.
  - Writers' heartbeat interval < the gap between pod kills, and ≤ TTL / 3.
- **Wire protocol versioning**: during a rollout, old and new binaries speak the DHT protocol to
  each other. Henge's own internal protocol needs the same discipline it imposes on users' services
  — the message format is versioned from day one, and a node tolerates the previous version.
- **Transport**: open (see Open questions) — likely HTTP under the existing dispatcher prefix, so
  it inherits the security model, Spring Security chain and optional shared secret.

## Part 2 — Signals built on the primitive

### Advertisements

Key `adv:<service>@<version>`, one member per hosting node, value = address plus **load metadata**
(capacity, current load) — cheap to include now, needed by self-organization later.

- Hosts heartbeat at ≤ TTL/3; graceful shutdown removes the member first, then drains.
- Callers **cache** the member set and refresh on an interval — reads are periodic, never per call.
  Load on the key is (instances × heartbeat rate) + (callers × refresh rate).
- **Last known good on wipe**: if a read comes back empty *and the epoch changed*, the caller keeps
  its previous set until the next heartbeat round. An empty read with an unchanged epoch is
  believed.

### Withdrawing when overloaded

The cheapest self-organizing behavior there is: a node that's overloaded stops advertising, and
traffic flows to the others. Purely local, no coordination. Rules that keep it from backfiring:

- **Measure overload as concurrency or queueing**, not CPU: in-flight calls against a concurrency
  limit, or time spent queued. A node waiting on a saturated dependency is overloaded with idle CPU
  — and withdrawing doesn't help there, so the signal should distinguish "I'm the bottleneck" from
  "my dependency is" (see Risks, metastable feedback).
- **Withdrawal is slow; rejection is fast.** Callers' cached sets lag by one refresh interval, so
  while withdrawn the dispatcher answers `503` with `Retry-After`, and a caller receiving that
  drops the endpoint from its local cache immediately.
- **Never withdraw as one of the last hosts.** If every host is overloaded and all withdraw, the
  service has zero hosts and an overload becomes an outage. A node withdraws only if the current
  advertisement set leaves enough others (the same "only leave if others have headroom" gate as
  eviction); otherwise it stays advertised and sheds with `503`s.
- **Hysteresis and jitter**: withdraw at a high watermark, return below a low one, with a minimum
  withdrawn time and randomized return, so withdraw → load drops → re-advertise → load floods back
  doesn't oscillate, and nodes don't return in lockstep with callers' refreshes.
- **Weighting is the gentle form**: callers choose endpoints by advertised load (power of two
  choices); full withdrawal is the extreme of that continuum.
- **It feeds role selection**: withdrawals raise caller-measured unmet demand, which is the
  stimulus for another node to adopt the service. Local shedding and cluster-wide adoption are one
  feedback loop.

Embedded callers on the same node are unaffected (they never route through advertisements).

### Rate limiters

User-facing, not internal-only: `henge.rate-limits.<name>` (`permits` per `period`, `capacity`) is the
declaration, and `@RateLimited("<name>")` injects the `RateLimiter` over bucket `rate:<name>` into any
bean; `tryAcquire(subject)` uses `rate:<name>:<subject>`. Configuration rather than code holds the
constants because every caller of a key must agree on them (see `tryAcquire` above). Each call is one
store operation; there is no local counting.

An earlier sketch counted per node in windows (`rl:<name>:<window>`, member = node, summed by readers,
flushed periodically) to avoid a round trip per request. It was replaced by the bucket because "is
there room?" has to be answered and applied at once (see `tryAcquire` above): lagging per-node counts
overshoot by flush interval × request rate in normal operation, not only when serialization fails.

### Demand and pressure signals (later phases)

Same shape: each node publishes its own observation as its member, readers sum or compare, TTL is
the evaporation rate. **Demand is measured at the caller** (attempted calls, latency, failures — but
retries don't count as new demand), because a service nobody hosts produces no provider-side signal.

## Part 3 — Leases (first deliverable)

### Problem

A database accepts 200 connections. With every node able to host the service that uses it, the
number of pools — and so connections — scales with node count until the database refuses
connections. The cluster needs to cap how many nodes construct that service.

### Shape

```java
@ServiceVersion(value = OrderService.class, version = 1)
public class OrderServiceImpl implements OrderService {
    public OrderServiceImpl(@RequiresLease("orders-db") Lease ordersDb /* , ... */) {
        HikariConfig config = /* ... */;
        config.setMaximumPoolSize(ordersDb.amount());   // size the resource FROM the lease
        this.dataSource = new HikariDataSource(config);
    }
}
```

This is the form where the implementation builds its own resource. A lease can also have a provider that
builds the resource once per node for everyone who shares it (see "Versions, and sharing a resource
between them"); then the constructor asks for the resource instead of the `Lease`.

```yaml
henge:
  leases:
    orders-db:
      capacity: 180          # cluster-wide; set below the real limit (200) as a margin
      amount: 20             # what one node claims, shared by every service that declares the lease
```

### Behavior

- At construction time, for each embedded candidate that declares leases, Henge acquires **every**
  lease. All granted → construct the implementation as today. Any refused → release what was
  acquired and fall back to `internal-rest`: the implementation (and so its pool) is never
  constructed on this node. Grants are all-or-nothing.
- The decision lives in a factory bean registered for each leased service (the registrar runs
  before any bean exists, so it can't consult a store). The factory either builds the real
  implementation through the bean factory (normal DI applies) or returns the remote proxy. Callers
  inject exactly as today.
- The dispatcher's registry of embedded services is computed **after** those decisions, so
  `/_henge` never claims a service this node declined to build.
- Held leases are renewed by heartbeat and expire with the node, returning capacity one TTL after a
  crash. Once constructed, a service keeps its lease for the life of the process (no eviction in
  this phase).

### Acquisition: speculative read, then one atomic claim

1. **Speculative read** of `lease:<resource>`. If the live members already leave less than this
   claim's amount, give up without writing anything. This is a pure optimization: it keeps a full
   lease from turning every starting node into a write.
2. **`claim`** (see the store contract): the key's serializer sums the live members, and either
   writes this node's member and grants, or refuses. There are no intermediate states and no
   settle interval; acquisition costs one round trip per lease. Racing claimants are ordered by
   whoever the serializer sees first, and the loser is simply refused.
3. A multi-lease service acquires each lease in turn and, on any refusal, `remove`s the ones it
   already holds (all-or-nothing, as above). A rollback leaves capacity briefly claimed by a node
   that then declined; callers that lost a race to it are refused for that moment. Claim in a
   fixed order (by lease name) so two services never deadlock-refuse each other.

After the key's serializer changes (the lease key may have been wiped), the new owner refuses
claims for one heartbeat interval so existing holders re-assert first (see the DHT's claim
ownership). Other adapters surface this through the epoch: a claimant that sees an epoch change
waits one heartbeat before claiming.

Overshoot is still possible when two nodes briefly both act as the serializer (DHT churn); that is
what the capacity margin is for, and the claim path fails closed under an unstable view. In
monolith mode the in-process store always grants, unless a single claim exceeds capacity — a
startup config error.

### Decided constraints

- **Incompatible with `--henge.remote-url-template`.** URL-template routing assumes every node
  behind the DNS name hosts the service; with leases, some don't. A process combining leased
  services with URL-template routing fails at startup. Two supported modes: orchestrator-managed
  routing (k8s owns placement; no leases) or advertisement-based routing (Henge owns placement).
- **User responsibilities, documented, not enforced:**
  - The leased resource belongs to the service: it's created by the service (or something that
    sees the `Lease`), not a shared application bean. Shared beans — and startup-time users like
    JPA/Hibernate metadata, Flyway, Liquibase — open connections regardless of which services were
    built.
  - The resource is sized from `Lease.amount()`. Henge only does bookkeeping; it never sees a real
    connection.

### Versions, and sharing a resource between them (built)

Leases used to be claimed per service version, so two versions of one service that used the same
database each claimed a lease and each opened its own pool: the cluster's claim, and the database's
real load, grew with the number of versions a node hosted. Now a lease is a claim and a resource per
node, shared by everything on the node that uses it.

**The claim.** The keeper writes one member per lease for the node. Every service that declares the
lease joins it, and the last one letting go (stopped, or failed to construct) hands it back, so a lease
is never kept for consumers that didn't start. Because different leases are different keys with
independent capacities, the order a node claims them in can't change what it ends up hosting, so there
is no claim ordering or triage between versions or services.

**The resource.** Claim dedup alone would only move the bookkeeping: if v1 and v2 share a claim and each
still builds a pool of that size, the database sees twice the lease. So the resource itself is one
object, built once per node and owned by Henge rather than by an implementation constructor.

- **A lease name is a resource.** `henge.leases.<name>.capacity` already names one real cluster
  resource, so the name is also the identity on a node: every version of every service that declares
  the lease shares one resource and one claim. A consumer that needs its own resource declares its own
  lease name. Sharing is opt-in by naming the same lease.
- **The amount belongs to the lease, not to a service.** `henge.leases.<name>.amount` is what one
  node claims, next to `capacity`; `henge.services.<name>.leases.<lease>` is gone. The resource is
  built once at that size, and it can't depend on which consumers happen to be hosted, since sizing it
  from a changing mix would mean resizing a live pool. How the amount should change when several
  versions are live is the operator's to consider, and the one number they set.
- **Providers are optional, and a `Lease` parameter is the explicit way around one.** A lease may have
  a provider: a class annotated `@LeasedResource("<lease>")` that implements `ResourceProvider<T>`
  (`T open(Lease)`, and a `close(T)` that by default closes an `AutoCloseable`), built by Henge through
  Spring DI like an implementation, one per lease name. A constructor parameter marked
  `@RequiresLease("<lease>")` then has one of two meanings by its type:
  - type `Lease`: the name and the configured per-node amount. The implementation builds its own
    resource, which is the user's choice to bypass the provider, and the N-copies hazard is theirs;
  - any other type: the provider's resource, which must be assignable from `T`. A lease with no provider
    can't satisfy one, and startup says to take a `Lease` instead.

  Providers are found by scanning (static metadata, no instantiation); the resource type comes from
  the provider's generic parameter, and one that can't be resolved fails startup.
- **`@RequiresLease` is for constructor parameters only.** The class-level form is dropped: a
  consumer's leases are fully described by its parameters, so it was redundant, and only a lease with
  no parameter (a gate with no resource) would have used it. The annotation names the lease on every
  such parameter. It can't be inferred from the type: two providers can produce one type, and an
  ordinary bean parameter must not silently become a leased resource.
- **One claim per node per lease**, written when the first consumer is hosted, under one member for the
  node (not `service@version`).
- **Liveness is a reference count over declared edges, version → lease.** A resource is live while at
  least one hosted version (a binding whose target is `Local`) references it. The last reference going
  away closes the resource and releases the claim. These are declared edges, not inferred bean wiring;
  a general dependency graph is only needed if a resource can depend on another, which this does not
  allow.
- **A service with several leases stays all-or-nothing.** A node that holds lease X for one service and
  is refused lease Y for another sends the second remote; X stays held by the first.
- **The resource lives in a Henge-managed context above the versions' (not yet).** Today the keeper
  holds the resource and closes it through its provider when the claim is handed back. Once each version
  has a child context, the resource sits in a context above them, and Spring's destroy ordering does
  the teardown (pools close, executors stop). That is the same shape the isolated tier and eviction
  need; the one difference is that the resource is shared between sibling contexts instead of private
  to one.
- **The static check shrinks to one node's claim.** All consumers of a lease share a claim, so there is
  no sum over services to check: the lease's amount just has to fit its capacity.
- **What the refcount counts.** Every hosted version that holds the lease, whichever form it uses. The
  claim is released when the last one goes, and the provider's resource, if there is one, is closed
  then; a resource an implementation built itself is its own to close.
- **Noisy neighbours are the cost of sharing.** Two services on one lease contend for the one pool;
  that is what sharing means, and naming the same lease is how a user chooses it.

This composes with the switchable proxies of phase 6: a binding leaving `Local` is where its reference
is dropped, and a drained version releasing its share is what lets a deprecated version stop costing
capacity.

## Part 4 — Self-organization (long term)

### Mechanism

Eviction first: start from the replicated monolith; nodes stop hosting a service when hosting it
here is costly or harmful, and only if others can take it.

- **Role selection** follows the response-threshold model (Bonabeau, Theraulaz & Deneubourg): a
  node engages a task with probability sⁿ / (sⁿ + θⁿ) for stimulus s and threshold θ; thresholds
  that fall with practice produce specialization. Randomization is what prevents every node from
  reacting identically to the same signal.
- **Memory pressure**: evict a service at random (weighted by measured allocation —
  `ThreadMXBean.getThreadAllocatedBytes` around dispatch is cheap) until pressure clears, where:
  - pressure is **post-GC old-generation occupancy**, not RSS or instantaneous heap;
  - eviction happens only if other nodes **advertise headroom** — otherwise pressure is cluster-wide
    and the answer is more nodes (the autoscaler's job), not shuffling load into nodes that are
    already full;
  - each step waits for a GC cycle to observe its effect (hysteresis);
  - evictions are **remembered** as an evaporating "X hurt me here" signal, so a node is slower to
    re-adopt X — and a service that hurts nodes everywhere is an alertable leak.
- **Floors and constraints**: a minimum number of hosts per service, and declared constraints for
  services that can't move freely (stateful, hardware, credentials).
- **Locality**: a node that calls X heavily may choose to keep X embedded, collapsing hot call-graph
  edges into method calls.
- **Shared-lease affinity**: a node's cost for a set of services is the number of *distinct* leases
  they declare, since consumers of one lease share a claim (see Part 3). Placing services that use the
  same lease together saves a whole claim, so a role-selection objective should prefer co-location of
  lease-sharing services, in the same way it prefers keeping a hot call-graph edge in-process.
- **Version migration drains itself**: as callers move to v2, demand for v1 evaporates.

### What it requires from Henge (not built today)

Today an embedded service is a raw singleton injected directly into its callers, its resources are
ordinary beans in the shared context, and everything is constructed eagerly. Eviction needs:

1. **A stable proxy at every injection point, embedded included**, whose target can switch between
   local, remote and draining. Callers can't hold direct references into something that may be
   destroyed. Built as the *service binding*, described below, because metrics, strict-mode
   round-tripping and this switching all want the same interception point and must not each add
   their own proxy.
2. **A child application context per service version**, holding the resources the service owns.
   Closing it is real deallocation: Spring runs every bean's destroy logic in reverse dependency
   order (pools close, executors stop). This is the same mechanism the roadmap's "isolated" (docs/scope.md)
   strict-mode tier needs.
3. **Drain before evict**: stop advertising, wait ≥ one TTL plus in-flight calls, then close.
4. Limits that remain: resources outside Spring's lifecycle (hand-started threads, static caches,
   `ThreadLocal`s, global JDBC driver registration) leak; class metadata stays loaded; activating a
   child context costs tens to hundreds of milliseconds plus warmup, so roles must not thrash.

### The service binding (built; retargeting and draining are not)

One `ServiceBinding` per `service@version`, and one JDK proxy over it registered under the version's
bean name, as primary and qualifier exactly as before. Every injection point holds that proxy:
an embedded implementation, a leased one, and a remote one are all a binding that differs only in
its target.

- **Target.** Either *local* (the implementation, which is now always the hidden, non-autowire
  `<name>.impl` bean, the shape leased services already had) or *remote* (the `ServiceTransport`). A
  lease refused at startup is just a binding whose target is remote.
- **Two paths in.** A caller goes `proxy → interceptors → target`. The dispatcher goes through
  `invokeLocal`, which skips the caller-side interceptors (the call already went through them on the
  calling node). Between them these are the only two places a call enters, so retargeting and
  draining add their in-flight accounting there and nowhere else.
- **Interceptors** run over the existing `ServiceInvocation`, in a fixed order: observation
  (metrics and spans) outermost, then strict round-tripping, then the target. Observation reads the
  current target's mode at call time, so its `mode` tag stays right after a switch.
- **Exceptions** from a local target are unwrapped, so a caller sees what the implementation threw.
- **Hosting follows the target.** A version is hosted here when its binding's target is local, so
  the advertiser, the topology report and the dispatcher's registry follow a switch with no further
  wiring.

Built: the binding, its proxy, the hidden implementation bean for every embedded service, the
interceptor chain, and the dispatcher going through `invokeLocal`. Not built until eviction needs
them: retargeting (a target that can change, so a mutable field where there is a final one now),
draining and its in-flight counting, and strict mode, each an addition to the binding and not a new
proxy. Retargeting away from local must destroy the implementation before it releases the version's
lease holding, since the implementation stands on the lease's resource.

Callers hold the interface, so an implementation is no longer something to inject by its concrete
class: it is the hidden bean `<name>-<version>.impl`. It is still the only bean of its class, so
`getBean(Impl.class)` finds it, but nothing autowires it, and `getBeansOfType` of the interface lists
it beside the proxy (a `List<Interface>` injection sees only the proxy).

## Risks

Ranked by severity. These are the reasons the later phases are gated on simulation.

1. **Metastable feedback.** X slows → callers retry → measured demand for X rises → nodes move to X
   and drop Y → Y slows → cascade. Worse, if X is slow because *its* dependency is saturated, adding
   hosts makes it worse, and a demand signal can't tell the difference. Needs backpressure signals
   from the bottleneck, retries excluded from demand, and damping.
2. **No measurable objective.** The JVM has no per-service heap accounting and only approximate
   per-thread CPU. Allocation rate is a proxy, not retained size.
3. **Lost failure isolation.** Nodes hosting a shifting mix spread a misbehaving service's blast
   radius. Splitting must be able to isolate deliberately.
4. **Least privilege.** If any node can become any service, every node needs every credential.
   Credential-bearing services need hard pinning.
5. **Operability.** "Where is X and why did it move?" has no static answer. A freeze/pin switch, a
   logged reason for every role change, and topology history are requirements, not polish.
6. **Two control loops.** The autoscaler reacts to node CPU; role changes move node CPU. One loop
   must be clearly slower than the other.
7. **Partition heal.** Each side independently adopts roles; healing produces double coverage then
   a mass drop.

The signaling cost itself is not a risk: (nodes × services) members per refresh is small at any
plausible scale.

## Phasing

Each phase is independently useful and testable.

1. **Store contract + in-process adapter** (built) (`henge-core` contract including `claim`,
   `henge-spring` wiring).
2. **`@RequiresLease`** (built) — factory-bean decision, late dispatcher registry, `Lease` injection,
   startup rejection of leases + URL template. Fully testable in monolith mode.
3. **Advertisements + advertisement-based routing** — the "Not in v1: service discovery" item,
   resolved — including load-weighted endpoint choice and withdrawal when overloaded. Built, except
   those last two (calls rotate over what's advertised), plus retries with failover.
4. **Redis / Valkey adapter** (built) — proves the contract against a store Henge doesn't control.
5. **Built-in DHT** — membership, XOR placement, redundancy, handoff, versioned protocol.
6. **Switchable proxies + child context per service** — also unblocks the roadmap's isolated tier.
   The stable proxy (the service binding, above) is built; retargeting, draining and the child
   contexts are not.
7. **Eviction** — memory pressure and misbehavior, drain, remembered evictions.
8. **Self-organized role selection** — preceded by a discrete-event simulation of the decision loop
   with injected slow dependencies and partitions, before any of it touches a real cluster.

## Open questions

- **Membership model for the DHT**: full membership with one-hop routing (proposed), or Kademlia
  k-buckets from the start? Decides whether XOR or rendezvous placement is preferable.
- **DHT transport**: HTTP under the dispatcher prefix (inherits security, simplest) vs a dedicated
  binary protocol.
- **Declaring a node a storage member vs client-only for the DHT.** Store selection itself is
  `henge.store.type` (`in-process` default, or an adapter found with `ServiceLoader`) with each
  adapter's settings under `henge.store.<type>.*`; the DHT still needs a way to say whether a node
  holds data.
- **Heartbeat defaults** for leases, and the post-ownership-change grace period; whether they
  derive from the adapter.
- **Is a refusal retried?** v1 treats it as permanent for the process's life; a periodic retry
  would let a node pick up capacity freed later, at the cost of switching a service from remote to
  embedded at runtime, which needs the switchable proxies of phase 6.
- **How the DHT sizes the claim-path margin**: a fixed documented multiple, or derived from
  observed view instability.
- **Valkey hash-field TTL support** — verify before choosing the Redis mapping.
- **Does `Lease` support partial grants later?** The API (`amount()`) leaves room; v1 is
  all-or-nothing.
