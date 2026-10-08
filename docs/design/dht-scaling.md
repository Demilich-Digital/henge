# Design: DHT scaling

How the cost of a Henge cluster's store changes when the store is an [infrastructure DHT](infrastructure-dht.md)
instead of Redis, and where it runs out: on the store nodes, and on the network underneath them. It follows on
from [store capacity](store-capacity.md), and assumes the [hot-keys](hot-keys.md) design, so that no key's cost
grows faster than linearly.

**Status. Superseded** by the scaling section of [a store of sub-clusters](subcluster-store.md#scaling). Kept
for the analysis that led there: why not every process stores, what a DHT's connections do to the underlay, and
the walls a hashed store meets on the way to 20 million. The estimates use the measured per-process workload and
an assumed JVM cost per request (50 µs to serve, 25 µs to forward).

## The short answer

- **Not every process should hold the store.** Henge's keys scale with services, not processes: a few hundred of
  them. If every process were a store node, only about 3 × *K* of them would own anything. Every process would
  also be a possible peer of every other, and that fan-in breaks the network's connection tracking somewhere
  around 10,000 processes ([below](#why-not-every-process)).
- **Store nodes as a role, sized to the load, fix both.** About one process in 280 holds the store. Clients talk
  only to their gateway, so the cluster's connections fall from about 200 per process to about 2.
- **Hot keys get homes.** Past about 30,000 processes the busiest keys each outgrow a share of a store node and
  are given one of their own. One home carries a key to about 100,000 processes.
- **It goes to 20 million**, with two more steps: grouped routing among store nodes from about 3 million, and
  cells, since 20 million processes don't fit in one failure domain ([below](#toward-20-million-processes)).
- **The price is a second hop and about twice the bytes** of a one-hop store: about 160 kbit/s per process, and
  about 45 Mbit/s into each store node at any size, which a datacenter network doesn't notice.

## The workload

The two-tier cluster of store capacity: half the processes call 50 services, half host them, hold 5 leases and
belong to 10 limiters. Each process sends 11 requests a second to the store: 5.75 writes, 5.5 reads. Through a
DHT with *r* = 3 copies, each request costs the store:

| | Store CPU | Messages |
|---|---|---|
| The gateway forwards it | 25 µs | 2 |
| A write: the primary applies it and forwards it, two backups apply it | 3 × 50 µs | 3 |
| A read: one owner answers it | 50 µs | 1 |

That is about **1.4 ms of store CPU a second per client process**: 0.28 at gateways, 0.86 for writes, 0.28 for
reads.

## How big the store is

Store nodes run at a target of 40% of a core on store work, since they have given their other services up. So
there is one store node for about 280 clients, with a floor of *r* + 2 = 5:

| *N* | Store nodes *M* | Share of processes | Store CPU each |
|---|---|---|---|
| 1,000 | 5 (the floor) | 0.5% | ~28% |
| 10,000 | ~36 | 0.36% | 40% |
| 100,000 | ~360 | 0.36% | 40% |

The rest of the cluster carries no store at all. The membership list is *M* entries: 36 KB at 100,000 processes.
Gossip and replication run only among store nodes.

## Connections

| *N* | Redis: into one shard | Redis: all | DHT, every process stores: into the busiest owner | every process stores: all | **DHT, store nodes: into one** | **store nodes: all** |
|---|---|---|---|---|---|---|
| 1,000 | 1,000 | 1,000 | ~1,000 | ~200,000 | ~400 | ~2,000 |
| 10,000 | 10,000 | ~70,000 | ~10,000 | ~2 million | ~600 | ~21,000 |
| 100,000 | 100,000 | ~7 million | ~100,000 | ~20 million | ~920 | ~330,000 |

With store nodes:

- **A client holds two**: its gateway and the backup.
- **A store node holds about 2*N*/*M* + *M***: its clients, about 560, and every other store node. That is a
  number connection tracking is built for at any size.
- **In all, about 2*N* + *M*²**, a sixtieth of the one-hop store at 100,000, and a twentieth of Redis.

Redis's fan-in into each shard is *N*, as every process connects to every shard. Its `maxclients` default of
10,000 is reached by a 10,000-process cluster. The gateway is what removes that fan-in. Henge could give a Redis
deployment the same shape with a proxy tier, but in the DHT it is part of the design.

## Bytes

Each client's requests cross the network twice, client to gateway and gateway to owner, unless its gateway owns
the key. Writes are copied to two backups, and gossip runs among store nodes:

| | Per client process |
|---|---|
| To and from its gateway | 9 KB/s |
| Gateway to owner | about 9 KB/s |
| Replication to backups | 2 KB/s |
| **On the network** | **about 20 KB/s**, twice Redis's 9 |

| *N* | Whole fabric | Into one store node |
|---|---|---|
| 1,000 | 160 Mbit/s | 32 Mbit/s |
| 10,000 | 1.6 Gbit/s | 45 Mbit/s |
| 100,000 | 16 Gbit/s | 45 Mbit/s |

Spread over the fabric, that is under 200 kbit/s per process, and a store node's traffic stays flat as the cluster
grows, because *M* grows with it. The second hop doubles the bytes to save two orders of magnitude in
connections, which is the right trade on any datacenter network.

## Homes for hot keys

The busiest key in the example, a service called by every frontend and hosted on every backend, costs its primary:

| *N* | Primary: messages a second | Store CPU | Where it lives |
|---|---|---|---|
| 1,000 | ~170 | 0.9% | the hashed pool |
| 10,000 | ~1,700 | 8.5% | the hashed pool |
| 30,000 | ~5,000 | 25% | **given a home** at the mark |
| 100,000 | ~17,000 | ~85% | a home of its own, near a core |

Each backup carries about 40% of that: the forwarded writes and a third of the reads. In the example every
service's advertisement and registration key is as busy as the busiest. So at 100,000 processes about 100 keys have
homes, and with their backups that is most of the 360 store nodes. **At scale, the store converges on one home and
two backups for each hot key**, plus a small pool for everything cold. That is what the store's load needs, and
nothing more.

A home's fan-in is the store nodes' gateways, at most *M*. Its CPU is the ceiling: one key reaches a whole store
node at about 100,000 processes of this shape. Past that, the store splits the key's members over several homes.

### Split large keys inside the store

A key with many members is spread by the store over *P* homes (or *P* groups in the pool), by a hash of the member:
`key#p`, each with its own backups. A `put` goes to its member's group. A `sample` asks one group at random. A
`count` sums the groups' counts. *P* grows with the key's load.

Unlike the partitioning that [hot keys](hot-keys.md#advertisements-a-sample-sized-by-the-callers) rejected, *P*
is the store's own business, recorded with the key's home. Writers and readers never see it, and no rollout of an
application changes it. Only member keys are split: advertisements, caller registrations, limiter membership.
`claim` and `tryAcquire` stay on one primary, since their atomicity is the point. Their keys are small: a lease's
members are bounded by its capacity, and a bucket has one level. A bucket's load is about its limit, with the
[refusal window](hot-keys.md#the-bucket-refusing-locally). Each take is applied by the primary and copied to two
backups, so a bucket limited to about 5,000 a second fills a home.

## Why not every process

The first version of this design made every process a store node, with one hop from anyone to any owner. It
fails in two ways that grow with the cluster:

- **The work doesn't spread.** With a few hundred keys and three copies each, about 400 processes own anything,
  at any size, and the rest hold empty shares. The owners' load grows with *N*, and it lands on application
  processes, whose CPU is meant for their services.
- **Every process is a peer of every other.** A process connects to the owners of every key it uses, about 200
  of them, and an owner of a widely used key is connected to by nearly every process. At 100,000 processes that
  is 20 million flows in the fabric, and 100,000 into each busy owner. Host connection tracking (`nf_conntrack`,
  used by iptables networking and most network-policy engines) is commonly sized in the low hundreds of thousands.
  Cloud networks track connections per instance under an allowance that scales with instance size. Overlays and
  stateful firewalls keep per-flow state too. Past those limits new connections are dropped, which looks like
  the store being away.

Store nodes as a role answer both. The work goes to processes that have given their other work up, and
connections go through gateways.

## What it adds up to

| *N* | Redis | DHT, store nodes and homes |
|---|---|---|
| 1,000 | 1 shard; nothing to tune | 5 store nodes; ~2,000 connections |
| 10,000 | ~7 shards; raise `maxclients`; 10,000 connections into each | ~36 store nodes; ~600 connections each |
| 100,000 | ~70 shards; 100,000 connections into each | ~360 store nodes, most of them homes; ~920 connections each |
| 1 million | ~670 shards; a million connections into each | ~3,600 store nodes; ~4,100 connections each |
| 20 million | not one Redis Cluster | ~71,000 store nodes in cells, grouped routing; ~1,100 connections each |

The DHT carries the store on about 0.4% of the cluster's processes, which take the role as the load needs and
give it back when it doesn't. Its connections stay in the hundreds per store node at any size, and there is
nothing to size, shard or pay for separately. What it costs is a second hop on every request from a client
process, and twice the bytes of a one-hop store.

## Toward 20 million processes

The goal is a store that keeps scaling to 20 million processes. Per process, nothing above grows with *N*: a client
holds two connections and sends 11 requests a second, and a store node's traffic and connections to clients are
flat. What does grow is a hot key, which is per service, and the store nodes' mesh among themselves, which is
*M*². These are the walls, in the order a growing cluster meets them:

| At about | What runs out | What carries it on |
|---|---|---|
| 30,000 | A hot key outgrows a share of a store node | [Homes](#homes-for-hot-keys) |
| 100,000 | A hot key outgrows a whole store node | [Splitting the key](#split-large-keys-inside-the-store) over several homes |
| 3 million (*M* ≈ 10,000) | Store nodes' full mesh: every store node connected to every other reaches the underlay's connection limits | [Grouped routing among store nodes](#grouped-routing-among-store-nodes) |
| 20 million | One failure domain: no single datacenter's network is one fault domain at this size | [Cells](#cells) |

At 20 million, in the two-tier shape:

| | |
|---|---|
| Store nodes *M* | ~71,000, 0.36% of processes |
| Store CPU | ~28,000 cores in all, 1.4 ms a second per process |
| Membership list per store node | ~7 MB |
| Fabric | ~3.2 Tbit/s in all, 160 kbit/s per process, ~45 Mbit/s into each store node |
| The busiest service | 10 million hosts renewing, 10 million callers sampling: about 4 million messages a second, ~200 cores |
| Its key | split into ~800 groups of ~12,500 members, each group a home and two backups |

### Splitting at this size

A key with 10 million members and 800 groups changes two operations:

- **`count` becomes an estimate.** Summing 800 groups on every refresh is O(*P*) per read. Instead, a count reads one
  group at random and multiplies by *P*, which the hash spreads evenly enough at these sizes, to within a few
  percent. The sample sizing of [hot keys](hot-keys.md#advertisements-a-sample-sized-by-the-callers) needs only
  the ratio of hosts to callers, and a limiter's share errs by the same few percent, which the margin absorbs.
- **The writes are irreducible.** Every host renews its own advertisement each heartbeat. 10 million hosts are 1
  million writes a second for one service, whatever the layout. They spread over the groups, and stay linear in
  hosts. Lengthening the heartbeat for very large services halves them each time it doubles, at the cost of
  noticing a dead host later. Callers already fail over without the store's help, so that is a reasonable
  trade at this size.

### Grouped routing among store nodes

Full membership stays: 71,000 entries are 7 MB, and gossip spreads a change in O(log *M*) rounds. What can't stay
is a connection from every store node to every other: 71,000 each, 5 billion in all.

Store nodes are grouped by id prefix into about √*M* groups of √*M* (about 270 of 270). A store node holds a
connection to every node in its own group, and to one contact in every other group, about 540 in all. A request
for an owner in another group goes through this node's contact there, which is one more hop. With the gateway,
that is at most three hops from a client to an owner, at a fraction of a millisecond each inside a datacenter.

This is the step where the design starts to look like a structured peer-to-peer overlay, and it is deliberately the
last one. Below about 3 million processes, store nodes stay a full mesh, one hop apart.

### Cells

20 million processes don't fit in one failure domain. The largest datacenters hold hundreds of thousands to a
million machines, and 20 million processes span many of them. The cluster's own rules then apply:
[partitions are assumed global](partition-tolerance.md), and [a store is never stretched across a
WAN](partition-tolerance.md#beyond-one-cluster). One flat DHT across datacenters would be a store split by every
WAN fault.

So 20 million is **cells**: each a cluster of up to a few million processes in one failure domain, each with its
own DHT as designed here, joined by the [clusters-of-clusters](partition-tolerance.md#beyond-one-cluster) layer.
Within a cell, everything above holds. Across cells, that layer decides what is shared: a service's hosts in other
cells for failover, a capacity divided between cells.

If 20 million processes do live in one failure domain (tens of processes on each of the largest datacenter's
machines), the walls above are the whole list. One DHT carries them with grouped routing and split keys, and
nothing per process grows.

## Open questions

- **The JVM's cost** to handle and to forward a request (assumed 50 and 25 µs) has to be measured on the simulated
  cluster before the store-node count is trusted. Every figure for *M* scales with it.
- **The target load of a store node** (40% here). Higher means fewer store nodes, and less headroom for a store
  node lost or a key turning hot. Recommended: start at 40%, set by the simulation's oscillation tests.
- ***P* for split keys.** Recommended: split a home when it passes the mark that housed it, into two, so each split
  is a small step and the store never re-splits a key it just split.
- **Sticky reads.** Within the store, a gateway that always asks the same owner of a key, picked by a hash of
  itself, cuts each owner's read fan-in by *r* and costs nothing. Recommended with the first build.
