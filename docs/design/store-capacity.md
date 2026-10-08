# Design: store capacity

How much a Henge cluster asks of its [ephemeral store](../ephemeral-store.md) as it grows, where the limits are,
and what a store that runs out of memory means. Henge's keys do not scale with users or requests. They scale with
**services**, which grow with what developers write, and with **nodes**, which grow with how far the cluster is
scaled out. Nodes are the unbounded factor, so this is a model in nodes.

**Status.** The numbers are measured. [Memory pressure](#memory-pressure) and
[lifting the read ceiling](#lifting-the-read-ceiling) are designs, not built.

## The variables

| Symbol | Is | Typical range |
|---|---|---|
| *N* | Henge processes (nodes) in the cluster | the unbounded one |
| *S* | Service versions advertised. A version is live alongside the next only during a rollout | tens to low hundreds |
| *H*<sub>s</sub> | Nodes hosting service version *s* | 1 to *N* |
| *C*<sub>s</sub> | Nodes that call *s* over the network. A node that hosts *s* calls it in-process and never reads its advertisement | 0 to *N* |
| *L*, *R*, *J* | Leases, rate limiters, scheduled jobs | a handful to tens each |
| *n*<sub>r</sub> | Nodes that built rate limiter *r*: those hosting a service that draws on the shared upstream resource the limiter guards. Each counts the others, to size its share | the hosts of those services, at most *N* − 1 |
| *m* | Members one node writes: the services it hosts, plus its leases, plus its limiters | tens |
| *T* | The heartbeat and advertisement refresh, 10 s by default | |

"O(*N*²)" below means Σ<sub>s</sub> *C*<sub>s</sub> · *H*<sub>s</sub>, which approaches *N*² only when most nodes call a
service that most other nodes host.

## What a cluster stores

| Key | Count | Members |
|---|---|---|
| `adv:<service>@<version>` | *S* | *H*<sub>s</sub> |
| `lease:<name>` | *L* | the holders, at most the nodes that fit the capacity |
| `rate:<name>` (membership) | *R* | *n*<sub>r</sub>, empty values |
| `rate:<name>`, `rate:<name>:<subject>` (buckets) | *R* plus **one per active subject** | a bucket, kept only until it drains |
| `cron:<job>@<instant>` | *J* × fires in 10 minutes | 1 |
| `cron:<job>:running` | *J* | 1 |

The key count is *S* + *L* + 2*R* + a few per job, which is hundreds, whatever *N* is. Members are what grow:
about *N* · *m* in all.

**The one per-user term** is the subject bucket: a limiter keyed by subject (an API key, a user) keeps one bucket
per subject that has drawn on it within its drain time. That scales with active users, and it is the only thing
in the store that does. It is bounded by the drain time, and a limiter that isn't keyed by subject has none.

## Measurements

Redis 8, one instance in Docker on loopback, a Ryzen 7700X. Layout as `henge-redis` writes it: a hash per key,
one field per member named `<uuid>/<localName>`, per-field TTLs. Cloud vCPUs are commonly slower than this desktop
core: read the throughput figures as a best case, and halve them to plan.

| | Measured |
|---|---|
| Advertisement member (URL value) | ~90 B in a hash of up to 512 members (listpack), ~130 B above |
| Lease or limiter member (4-byte or empty value) | ~60 B, ~100 B above 512 |
| A key, with its name and expiry | ~220 B |
| A write (`PUT`: field, its TTL, the key's TTL) | ~100,000 per second |
| A `tryAcquire` on one bucket, admitting or refusing | ~100,000 per second |
| A read (`READ`, which includes `INFO server` for the epoch) | ~13 µs, plus ~0.33 µs per member returned. ~4.5 µs of the fixed part is `INFO` |
| The same read for 1,000 members | ~2,900 per second, ~340 µs each |

Bytes on the wire are about 85 per advertisement member returned (field, value and framing).

## How it grows

Take *m* = 65 (50 service versions hosted, 5 leases, 10 limiters per node), and a cluster split into two tiers of
*N*/2: frontends that call 50 services, each hosted on every backend. That is the shape where reads are worst
short of every node calling everything remotely. Memory and writes use *m* = 65 for every node, which
overstates the frontends. The limiter column is a separate extreme: one node calls a service that every other
node hosts, and the service draws on a limited upstream, so *n*<sub>r</sub> = *N* − 1. A node subscribes only when
it hosts something that shares an upstream resource enforcing a limit, so real limiters sit well inside it.

| *N* | Memory | Heartbeat writes | Advertisement reads, CPU | Advertisement reads, network | One limiter's membership reads |
|---|---|---|---|---|---|
| 100 | under 1 MB | 650/s | under 1% of a core | 1 MB/s | under 0.1% |
| 500 | 3 MB | 3,300/s | 12% | 27 MB/s | 1% |
| 1,000 | 7 MB | 6,500/s | 45% | 106 MB/s (~850 Mbit/s) | 3.5% |
| 2,000 | 13 MB | 13,000/s | **170%: saturated** | 425 MB/s | 14% |
| 5,000 | 33 MB | 33,000/s | | | **84%** |
| 10,000 | 70 MB | 65,000/s | | | |

Where each column comes from:

- **Memory** is about *N* · *m* · 100 B, plus the keys, which are negligible. **It never binds.** The smallest
  managed Redis tier, around half a gigabyte, holds tens of thousands of nodes. Subject buckets are the only way to
  fill a Henge Redis, at roughly 100 to 150 B per active subject.
- **Writes** are *N* · *m* / *T*: every node renews every member once a beat. They are spread over all the keys, so
  Redis Cluster spreads them over its shards. A single Redis reaches its limit somewhere past 5,000 nodes.
- **Advertisement reads** cost Σ<sub>s</sub> *C*<sub>s</sub> · (13 µs + 0.33 µs · *H*<sub>s</sub>) per *T*, and as much
  network as members returned. This is the O(*N*²) term, and **the first limit a cluster meets**: around 1,000
  nodes in the two-tier shape, on CPU and network at once. Redis Cluster spreads different services over different
  shards, but a single service's key lives on one shard. A service hosted on 5,000 nodes and called by 5,000 more
  needs most of a shard by itself.
- **Limiter membership** is *n*<sub>r</sub> · (a write + a read of *n*<sub>r</sub> members) per *T*, per limiter: a
  read of every subscriber, done by every subscriber, only to count them. It is quadratic in a single key, so
  sharding doesn't help it at all. It binds a few thousand nodes into one limiter.

A replicated monolith, where every node hosts everything, makes almost no advertisement reads, since nothing is
called remotely. Its cost is the writes and the limiter membership.

## What this means for sizing

- **Up to a few hundred nodes:** any Redis. A few megabytes, a few percent of a core. Burstable instance classes
  are fine.
- **Around a thousand:** a dedicated, non-burstable instance. The routing reads are a steady load on CPU and
  network, and a burstable class that runs out of credits behaves like a store that is slowly going away.
- **Past a couple of thousand:** Redis Cluster for the writes and for advertisements spread over many services,
  and the read changes below for any one service that is widely hosted and widely called, and for any limiter with
  thousands of subscribers.

Memory is the wrong thing to size by. Size by CPU and network, and give memory headroom generously, since it costs
nothing at these sizes and it is what keeps a Redis from evicting.

## Sharding

The expected path is that a cluster shards its store when it reaches a read limit: Redis Cluster, which
`henge-redis` supports as it stands. Sharding spreads **keys** over servers, and each key lives wholly on one.
So it scales Henge as far as the load is spread over many keys, and stops at the cost of the hottest single key.

**Replicas don't add read capacity.** Every read goes to a key's primary, and has to: each replica has its own
`run_id`, so reads spread over replicas would see the epoch change on nearly every read, and the
[epoch token](lease-healing.md#every-redis-loss-changes-the-epoch) makes a read a write, which a replica refuses.
Adding shards is the only lever on the Redis side.

In the two-tier shape above, with each shard kept to half a core:

| *N* | Routing reads, all shards | Hottest key | Shards needed, evenly spread | With slots placed by hash |
|---|---|---|---|---|
| 1,000 | 0.45 cores | 0.01 cores | 1 | 1 to 2 |
| 2,000 | 1.7 | 0.03 | 4 | about 8 |
| 3,000 | 3.8 | 0.08 | 8 | about 16 |
| 5,000 | 10.5 | 0.21 | 21 | close to one per key |
| 7,400 | 23 | **0.46** | 46: about one shard per service key, each near its budget | the ceiling |

How to read it:

- **Up to the number of hot keys, sharding is linear.** Total read cost grows as *N*², and shards are added to
  match. Writes, leases, jobs and buckets spread the same way and stay far below the reads.
- **Hash placement is lumpy.** There are only tens of hot keys, one per widely called service, and Redis Cluster
  places them by a hash of the name. Fifty keys over twenty shards puts five or six on the busiest shard where two
  or three would be even, so plan on about twice the even count. The alternative is moving hot slots by hand,
  which Redis Cluster allows.
- **Then it stops.** Once each hot key has a shard to itself, more shards add nothing. The cost of one key,
  *C*<sub>s</sub> · (13 µs + 0.33 µs · *H*<sub>s</sub>) per *T*, sets the ceiling: about **3,700 callers × 3,700
  hosts** for one service on this desktop, and about **2,600 × 2,600** at cloud speed. The network of that one key,
  around a gigabit, arrives at the same point.
- **A rate limiter is one key from the start.** Its membership costs *n*<sub>r</sub> · (23 µs + 0.33 µs ·
  *n*<sub>r</sub>) per *T* on one shard, the same shape as one service. A limiter built on 2,400 to 3,600 nodes is
  at the ceiling whatever the shard count. In the two-tier shape it is built where the limited service is hosted,
  half the nodes, so it arrives with the routing ceiling: around 5,000 to 7,400 nodes.

So sharding carries a cluster from about a thousand nodes to a few thousand, with shards added roughly as *N*²
until there is one per hot key. Past that, only a change in how Henge lays out or reads its keys helps
([below](#lifting-the-read-ceiling)).

**Sharding also makes the per-key guard necessary.** With *k* shards, one fails over *k* times as often as a
single Redis restarts, and today each failover makes the process-wide guard treat the whole store as away
([the guard is one state for the whole store](partition-tolerance.md#the-guard-is-one-state-for-the-whole-store)).
That fix has to land before a cluster shards, not after.

## Which hot keys need atomicity

Any way of splitting a hot key has to keep what the key is relied on for. Some keys are only read and written by
their own members. Others are checked and written atomically, `claim` or `tryAcquire`, and a split must not lose
that.

| Key | Atomic operation | What makes it hot | Hot in practice |
|---|---|---|---|
| `adv:` | none: puts, and reads merged by union | callers × hosts, every refresh | **yes**, quadratic in nodes |
| `rate:` membership | none | subscribers², every beat | **yes**, quadratic in subscribers |
| `lease:` | `claim`: the sum of every member, checked and written at once | holders and pollers, every beat | no: holders are bounded by the capacity |
| `cron:` | `claim`, capacity 1 | one claim per fire, plus the run's renewals | no |
| `rate:` bucket | `tryAcquire`: leak, check and take at once | **every call through the limiter, refused or not** | **yes, by traffic**, not nodes |

The two keys that are hot by node count need no atomicity, so their reads can be changed freely
([below](#lifting-the-read-ceiling)). The keys that do need it are not hot by node count. The exception is the bucket.

**The bucket is hot by demand.** A `tryAcquire` costs about 10 µs whether it admits or refuses: one key takes about
100,000 per second on this desktop, so plan on half that. The load is what callers ask for, not the limit. A
limit of 1,000 per second on an upstream, under 60,000 attempts per second, saturates the bucket's shard with the
59,000 refusals. Sharding doesn't help, since it is one key.

The bucket is relieved by refusing locally, for as long as the bucket says nothing can fit, stretched to a
node's fair share under overload. That keeps the bucket one atomic key and brings its store load down to about the
limit itself ([hot keys](hot-keys.md#the-bucket-refusing-locally)). Dividing the capacity over several buckets was
the alternative, and it is kept for a lease that ever gets hot.

## Memory pressure

A Redis that evicts loses live state. Every Henge key is renewed, so eviction is not one loss but a continuous
one: claims vanish, holders re-create them on the next beat, pollers take the room in between, and holders give
leases up. The cluster churns instead of settling. Given the growth above, **a dedicated Henge Redis has no reason
to fill up**. One that evicts is shared with something else that filled it, has subject buckets past what anyone
planned for, or has a `maxmemory` set far too low. Each is a fault to fix, not a load to absorb. So evicting is
treated as a fatal state of the store: the store is away.

What a client can read without `CONFIG`, which managed services often block:

| Source | Says |
|---|---|
| `INFO memory`: `maxmemory`, `maxmemory_policy` | Whether this server can evict at all. `maxmemory:0` has no limit, so it ends in the OS killing Redis, which is a restart. Any policy but `noeviction` with a limit can lose keys silently. Managed defaults are often `volatile-lru`, and every Henge key has a TTL. |
| `INFO memory`: `used_memory` | How close it is to `maxmemory`. |
| `INFO stats`: `evicted_keys` | A counter since start. Any increase means data was lost. It counts every key on the server, so a shared Redis shows other applications' evictions too, which is cause for alarm in itself. |
| `INFO stats`: `current_eviction_exceeded_time` | Above zero while the server is over its limit and evicting now (Redis 7 and later). |
| A write's error, `OOM command not allowed` | The full state under `noeviction`. |

The design, all inside `henge-redis`:

- **Sample each server on a timer, not on each operation.** `INFO` is a third of a small read's cost. A sample
  every few seconds per server (each primary, in Redis Cluster) is enough.
- **A server that is evicting is unreachable.** While a sample shows `evicted_keys` rising, or
  `current_eviction_exceeded_time` above zero, operations on keys it holds throw `StoreUnavailableException`.
  Upstream that is an outage: holders sit still, pollers claim nothing, and the outage-ended wait of
  [lease healing](lease-healing.md#trust-less-and-wait-less) covers whatever lapsed meanwhile. A store shedding
  live state can't answer honestly, and the contract already says what to do with one that can't.
- **An OOM error is an outage** that is logged as the store being full, not as it being unreachable.
- **At connect, warn about a policy that can evict** and expose it as a metric. It is not refused: it is legitimate
  on managed services, and with the above it is safe, only noisier than `noeviction`.
- **Gauges** of `used_memory / maxmemory` and of evictions per server, so the alert comes before the store is full.

## Lifting the read ceiling

[Hot keys](hot-keys.md) is the design. It makes each of the three hot keys cost at most linearly, so that adding
shards carries the rest:

- **The rate bucket** refuses locally, by the window the bucket returns.
- **Limiter membership** counts its members (`count`, O(1)) instead of reading them.
- **Advertisements** are read as a random sample sized by the number of callers (`sample`), so a key costs
  *C* + *H* per refresh instead of *C* × *H*.

Two options were weighed and not taken:

- **Partitioning an advertisement into *P* keys** needs every host and caller of a service to agree on *P*, and a
  rollout that changes it splits them. A sample has nothing to agree on.
- **Reading only what changed** (a membership version) lowers the average, but not the ceiling: a rolling deploy
  changes membership every second, and every read is a full one again.

Separately, Redis-specific tuning: **taking `INFO` out of the read**, by reading the `run_id` once per
connection and again on reconnect, removes about a third of a small read. It has to be checked against the
[epoch token](lease-healing.md#every-redis-loss-changes-the-epoch), which keeps `run_id` for failovers.

## Growth with the hot-keys design

With the [hot-keys](hot-keys.md) mechanisms, no key's cost grows faster than linearly, so the cluster's cost is a
footprint per node times *N*. This section works that footprint out, and compares the three resources it uses:
memory, bandwidth and the store's CPU.

### What each operation costs

Measured the same way as above: one Redis 8 core, scripts shaped as the design specifies (`sample` and `count`
don't exist yet), bytes from the server's own network counters.

| Operation | Time | In | Out |
|---|---|---|---|
| `sample`, *k* = 32 from 10,000 members | 31 µs | 101 B | 2,876 B |
| `sample`, *k* = 128 | 80 µs | 102 B | 11,325 B |
| `count` | 12 µs | 93 B | 59 B |
| `put`, an advertisement | 10 µs | 194 B | 4 B |
| `put`, a caller's registration | 10 µs | 161 B | 4 B |

A sample costs about 15 µs plus 0.5 µs and 87 bytes per member returned. Members stored in the large encoding
cost about 134 B for an advertisement and 103 B for a caller. Small keys are about a third less.

### A node's footprint

The same two-tier cluster: frontends call 50 services, backends host 50 and hold 5 leases and 10 limiters.
Callers and hosts are equal in number, so every sample is at `k_min` = 32. Each node, every refresh (10 s):

| | Frontend | Backend | Average per node |
|---|---|---|---|
| Operations | 50 registrations, 50 samples, 50 counts | 50 advertisements, 5 claims, 10 memberships, 10 counts | 11 a second |
| Store CPU | 2,650 µs | 770 µs | **170 µs a second** |
| Bytes in | 18 KB | 13 KB | 1.5 KB a second |
| Bytes out | 147 KB | 1 KB | 7.4 KB a second |
| Memory held | 5 KB (caller members) | 8 KB (advertisements, claims, memberships) | **6.7 KB** |

**A node moves about 9 KB a second and holds about 7 KB.** Every second the store sends out more than a node's
whole footprint, almost all of it samples: 32 hosts × 87 bytes for each service a frontend calls. The sample size
is the lever on bandwidth. Halving `k_min` nearly halves it, and lengthening the refresh does the same.

### The cluster

| *N* | Memory | Operations | Store CPU | Bandwidth |
|---|---|---|---|---|
| 1,000 | 7 MB | 11,000/s | 0.17 cores | 9 MB/s (70 Mbit/s) |
| 10,000 | 67 MB | 112,000/s | 1.7 cores | 89 MB/s (0.7 Gbit/s) |
| 100,000 | 670 MB | 1.1 million/s | 17 cores | 890 MB/s (7 Gbit/s) |

Everything is linear, and spread over every service's two keys and the leases and limiters. That is a few
hundred keys, so sharding has plenty to spread. The hottest key in the 10,000-node cluster, a service's
advertisement, costs about 2% of a core. A single key reaches half a core only past about 100,000 callers of one
service (about half that at cloud speed). Rate buckets are apart from all of this: with the refusal window, a
bucket's load is about its limit, whatever *N*.

### Which resource runs out first

Per shard, holding *n* nodes' share of the load:

| Resource | Per node | A shard's budget | Nodes per shard |
|---|---|---|---|
| CPU (scripts run on one core) | 170 µs/s | half a core | **~2,900 desktop, ~1,500 cloud** |
| Bandwidth | 9 KB/s (~70 kbit/s) | the instance's *sustained* network rate, often a few hundred Mbit/s on small instances, well under the burst figure advertised | ~5,000 per 350 Mbit/s |
| Memory | 6.7 KB | the smallest managed size, about half a gigabyte | ~75,000 |

**CPU binds first, then bandwidth, then memory: at cloud speed, about 1 : 3 : 50.** A shard at its CPU budget holds 10 to 20 MB
of Henge state. So a Henge Redis is bought for its core and its network, and any memory size it comes with is
generous. The two cheap cuts are the ones above: a smaller sample for bandwidth, and taking `INFO` out of the
scripts for CPU, which is 4.5 µs of every operation's 10 to 30.

**Connections.** Every node connects to every shard, so each shard holds *N* client connections. Redis's
`maxclients` defaults to 10,000, which a 10,000-node cluster reaches; raise it with the cluster. The
[DHT scaling](dht-scaling.md#connections) doc compares this fan-in with a DHT's, where gateways remove it.

Shards needed are then about *N* / 1,500 at cloud speed, with each shard as small as an instance with a dedicated
core and a steady network allows. Memory headroom costs nothing at these sizes, and it is what keeps a Redis from
evicting ([memory pressure](#memory-pressure)).

## Reproducing the numbers

Memory: fill one key with *n* members in the store's layout from a Lua script, then `MEMORY USAGE`. Per key: the
`used_memory` difference after 10,000 one-member keys. Throughput: `redis-benchmark` running `EVALSHA` of the
store's own `READ` and `PUT` scripts, against keys of 1, 10, 100 and 1,000 members. Run them on the instance class
you plan to use, since that is what the halving above is guessing at.
