# Design: a store of sub-clusters

A store built into the Henge cluster itself, shaped by what Henge actually keeps in it. Its keys are few and
known: a family for each service version, lease, rate limiter and scheduled job, a few hundred to a few thousand in
all, fixed by the code and its configuration. Their load is anything but even. So each family gets **a sub-cluster
of its own**: a group of store nodes sized to that family's load. Which nodes serve which family is a small
directory that every store node holds, spread by gossip. Nothing is hashed onto a ring, and nothing routes.

**Status.** Not built, and not decided. It replaces the [infrastructure DHT](infrastructure-dht.md) as the
direction for a built-in store. That design is kept for its analysis, and much of it carries over: store nodes as
a role, gateways, the lifecycle, hard failures, the lineage epoch, the minority rule. [Decisions](#decisions) are
the ones that follow from the keyset; the rest are [open questions](#open-questions) with a recommendation.

## Why not a DHT

A DHT answers a question Henge doesn't have: where to put more keys than anyone can list. It hashes them onto
nodes because no directory could hold them. Henge's keys fit in a directory. The hard part is the opposite:
a handful of families carry most of the load, and that load grows with the cluster
([store capacity](store-capacity.md)). A DHT had to grow special cases for that: homes for hot keys, keys split
across homes, grouped routing once the store nodes' mesh got too big ([DHT scaling](dht-scaling.md)). Each was a
patch over hashing, for a keyset that never needed hashing.

Here the special cases are the design. Every family has a home, sized to its load, from the start.

## The keyset

| Family | Keys in it | Load grows with |
|---|---|---|
| `adv:<service>@<version>` | one | the service's hosts and callers |
| `call:<service>@<version>` | one | the service's callers |
| `lease:<name>` | one | its holders, at most its capacity |
| `rate:<name>` | its bucket, its membership, **one bucket per subject** | the limit, and the subjects |
| `cron:<job>` | **one per fire**, and the run | the job's schedule |

The directory lists families, never keys. A family's sub-cluster spreads the family's keys over its own members:
subjects and fires by a hash of the key, members of a large key by a hash of the member.

## Decisions

- **One sub-cluster per family.** A family is served by its own group of store nodes, at least *r* = 3 (agreed
  earlier: *k* copies). A store node may serve several families. A cold family shares its nodes with others, and
  a hot one has nodes of its own.
- **The directory is gossiped, and shaped like the store's own data.** Each store node declares which families it
  serves, in what role, and its load, as members it alone writes, renewed and expiring. The directory is the union
  of every store node's declarations, which is the [contract's own merge](../ephemeral-store.md#the-model-convergent-distributed-state).
  It travels by gossip among store nodes, not through the store it describes.
- **Sub-clusters are sized by self-selection,** per family, by the same rule as store nodes themselves: a node
  joins a family that is short, with a probability sized to the shortfall, and leaves one with room to spare.
- **Storing is a role, and clients go through a gateway** ([carried over](infrastructure-dht.md#store-nodes)). A
  client talks to one store node, which multiplexes all of the client's families onto its own connections to their
  sub-clusters.
- **Copies in different failure domains** within a sub-cluster ([carried over](infrastructure-dht.md#what-tolerates-them)).
- **Traffic stays in its zone except for copies.** A client's gateway is in its own zone, and so is the member a
  gateway reads from and writes its share to. Only replication, which is meant to reach other failure domains,
  crosses zones by design ([why](#where-it-breaks-infrastructure)).
- **No repair protocol.** Heartbeats are the repair.

## The directory

Each store node gossips a small record, versioned by the node and replaced whole when it changes:

```
node id (per boot), address, failure domain, load (CPU, connections)
families: [ (family, role, share) … ]
```

Merged, these are the directory: for each family, the store nodes serving it, their roles and their shares of its
keys. At 20 million processes it is about 70,000 records, a few megabytes. A change reaches every store node in
O(log *M*) rounds of gossip, and gossip is the only structure that spans the whole store. Failure detection (SWIM,
as before) runs on the same messages.

A family with no sub-cluster yet (a new service, a lease named for the first time) starts on *r* store nodes
picked by rendezvous hashing of its name. That is the only hashing in the design, and it decides only where a
family begins. Those nodes declare it, and from then on the directory says where it lives.

Clients don't hold the directory. A gateway answers for its clients from its own copy.

## Inside a sub-cluster

### Families that need atomicity

A lease, a bucket and a job's run are atomic within a copy, so their family is an **ordered list**: the first member
that membership counts as alive is the primary, and serializes `claim` and `tryAcquire`. The next two hold copies.
This is how homes worked in the DHT design, now the only way. A failed primary is replaced by the next in the list
as soon as membership declares it dead.

Subject buckets and fire keys are many keys, not one. They are spread over the sub-cluster's members by a hash of
the key, each key with its own primary and two copies among them, so a limiter with a million subjects spreads
them over its sub-cluster.

### Families of members

An advertisement or a caller registration is one key with many members, each written by its own node. Its
sub-cluster splits the members into **shares by the gateway they arrive through**: each gateway's writes for the
family go to one member of the sub-cluster, picked by a hash of the gateway, and are copied to *r* − 1 others.

- **`put`** goes to the gateway's member for the family. A gateway writes to one member per family, however many of
  its clients write.
- **`sample`** asks any member for a sample of its share. Gateways are unrelated to services, so a share is a
  random subset of the family's members, and a sample of it is a sample of the whole.
- **A client that moves to its backup gateway** writes into another share, and its old entry lapses within a TTL.
  Until then it appears in two shares: a count reads a little high, which for a limiter makes the shares smaller,
  the safe direction.
- **`count`**: each node knows its share's count, and the sub-cluster's members gossip theirs among themselves, so
  any of them answers the family's total, a moment behind.
- **`read`** (everyone, for the topology report) asks every share. It is rare.

A family of 10 million members on 500 nodes is 500 shares of about 20,000, and no node ever holds the whole key.

### Sizing a sub-cluster

Each member of a sub-cluster declares its load from this family. A family is **short** when its members' average
is above the high-water mark, and has **room** when it is below the low-water mark. As for store nodes:

- A store node with spare capacity joins a short family with probability *S* × (*u*/target − 1) / *candidates*, so
  the expected number that join is the shortfall. Joining pulls its share from the members it takes it from.
- A member of a family with room leaves with the mirror probability, after handing its share on. The floor is *r*.
- If no store node has room, the store as a whole is short, and a client takes the store role.

A cold family stays at *r* nodes shared with other cold families. A hot one grows its own, and its nodes give up
their other families as it does.

### The epoch

Per key, as in the DHT design: **the lineage of its copies**, carried by handoff and by replication, and new only
when a copy starts empty or a copy is promoted after a failure. A share that moves to a new member is handed off,
so a sub-cluster that grows or shrinks changes no epoch.

## The client's path

A client sends every request to its gateway, a store node **in its own zone**, chosen by a hash of its
deployment and then of itself, so that the clients of one deployment share a few gateways and those gateways use
few families. The gateway looks the family up in its directory, picks the member that holds the key (or, for a
sample, a member in its own zone), and forwards. That is two hops, client to gateway
and gateway to member. The gateway keeps a connection to one member of each family its clients use, chosen by a
hash of itself among the family's members in its zone, which is its share's member for writes and its sticky
choice for reads. A sub-cluster has a member in every zone once it has at least as many members as zones, which
the floor of 3 gives a three-zone cluster.

| Connections | Held |
|---|---|
| A client | 2: its gateway and the backup |
| A gateway | its clients (about 560), plus one per family its clients use (hundreds) |
| A sub-cluster member | the gateways that chose it, at most the number of gateways divided by the sub-cluster's size, plus its *r* − 1 replication partners |

No connection count grows with *N* except through more store nodes, which the role rule adds. Below a size where
every member can take every client's connection (a few thousand processes), clients skip the gateway and go
straight to the family's members, one hop.

## Failure, partition and lifecycle

All carried over from the DHT design, scoped to a sub-cluster:

- **The polite lifecycle** ([joining, draining, ordering with
  shutdown](infrastructure-dht.md#the-lifecycle)): a store node joining a family pulls its share, and one leaving
  hands it on. The store drains last in a process's shutdown.
- **Hard failures** ([as designed](infrastructure-dht.md#hard-failures)): timeouts and failover at every hop,
  hedged reads, copies restored by pull, failure domains, and a node declared dead rejoins before it answers. The
  scope of a failure is the families whose sub-clusters the node was in, not a hashed slice of everything.
- **The minority rule, per family.** A member that reaches fewer than half its sub-cluster's stable members treats
  that family as away. A split costs only the families whose sub-clusters it cuts, and a family's members can tell
  their own side's size.

## Scaling

Per process, the workload of [store capacity](store-capacity.md): 11 requests a second, 1.4 ms of store CPU at the
assumed JVM costs (50 µs to serve a request, 25 µs to forward one). Store nodes run at 40%:

| *N* | Store nodes | The busiest family's sub-cluster | A member's fan-in | Directory |
|---|---|---|---|---|
| 1,000 | 5 (the floor; no gateways) | 3, shared | ~1,000 | 5 records |
| 10,000 | ~36 | 3, shared | ~12 gateways | ~36 records |
| 100,000 | ~360 | 3, its own | ~120 gateways | ~360 records |
| 1 million | ~3,600 | ~25 | ~140 | ~3,600 records |
| 20 million | ~71,000 | ~500 | ~140 | ~71,000 records, a few MB |

- **The busiest family** (a service on 10 million hosts with 10 million callers) costs about 200 cores at 20
  million, replication included, spread over its sub-cluster at 40% each: about 500 nodes. Its members' fan-in
  stays at about the gateways divided by the sub-cluster's size, around 140, at every size past the first.
- **No mesh among store nodes.** A store node connects to its clients, to one member of each family its clients
  use, and to its replication partners. Gossip runs over short exchanges with a few random peers per round. There
  is nothing to group at 3 million, as the DHT needed.
- **Bytes** as the DHT with gateways: about 20 KB/s per process, flat per store node.
- **Cells** are still needed at 20 million, for the reason the DHT needed them: [no store across a
  WAN](partition-tolerance.md#beyond-one-cluster), and 20 million processes span many failure domains. Within a
  cell, nothing here has a wall below the cell's size.

## Where it breaks infrastructure

The store's own structure has no wall below the 20 million target: per process nothing grows, per store node the
connections are in the hundreds, and the directory is megabytes. What breaks first is the platform underneath it,
and the bill. In the order a growing cluster meets them:

| At about | What breaks | Whose limit | What carries it on |
|---|---|---|---|
| a few thousand | A family member's fan-in, while clients connect to members directly | the store's | Gateways |
| ~100,000 | **The money for cross-zone traffic**, if traffic ignores zones: ~$75,000 a month | the cloud's bill | Zone-local gateways and reads, about 7 times less |
| 150,000 | **One Kubernetes cluster**: 150,000 pods and 5,000 nodes are its supported limits | the orchestrator's | Several orchestrator clusters on one routable network |
| a few hundred thousand | **One cloud network's addresses**: a VPC CIDR block is at most a /16, five by default | the cloud's | Address quota increases, then IPv6 |
| several million | **Private IPv4**: all of RFC 1918 is 17.9 million addresses, and per-node pod ranges use well under half | the internet's | IPv6, or cells |
| tens of thousands of families used by one gateway's clients | A gateway's connections to families | the store's, on the developer axis | Gateways shared by deployment, as above |
| ~300 million (*M* ≈ 1 million) | Gossip: the directory reaches hundreds of megabytes, and a joining store node pulls all of it | the store's | Beyond the goal: a directory by cell |

### Cross-zone traffic

Copies go to different zones by design, which is what makes a zone's loss survivable. Everything else needn't.
If gateways and read members ignore zones, two of every three hops cross one, and a process's store traffic,
about 20 KB/s, is mostly cross-zone: about 37 GB a month, at $0.01 a GB each way. With zone-local gateways and
reads, only replication crosses, about 2 KB/s, or 5 GB a month:

| *N* | Zones ignored | Zone-local |
|---|---|---|
| 10,000 | ~$7,500 a month | ~$1,000 |
| 100,000 | ~$75,000 | ~$10,000 |
| 20 million | ~$15 million | ~$2 million |

Atomic families keep one primary, in one zone, so a lease's claims and a bucket's takes from other zones still
cross. Their volume is small: a lease's holders, and a bucket's limit.

### The orchestrator and the network

A Henge cluster assumes every process reaches every other on a flat, routable network. Kubernetes supports 150,000
pods in one cluster, so a larger Henge cluster runs on several orchestrator clusters that share a pod network,
which their networking has to provide. Seeds then come from each orchestrator cluster's DNS, and the gossiped
directory joins them into one store, since nothing in it depends on the orchestrator.

Addresses run out next. One cloud network starts at a few hundred thousand addresses, and private IPv4 as a whole
holds 17.9 million, fewer once nodes take their pod ranges. 20 million processes on one flat network need IPv6.
Without it, the cluster is cells, which it already has to be for its failure domains
([scaling](#scaling)). Henge's own traffic needs nothing from IPv4 that IPv6 doesn't give.

### What this means

The store isn't what limits a Henge cluster. Up to about 150,000 processes it runs inside one orchestrator cluster
and one cloud network, and costs about $0.10 a process a month in cross-zone traffic. Past that, the limits are
the platform's: several orchestrator clusters on one network, then IPv6, then cells. The store's structure goes on
working through each of them. The 20 million target is reachable by the store, and by the network only as IPv6
cells.

## Prior art

Load-based assignment of keys to servers is how large systems keep hot keys off a hash ring: Google's Slicer and
Meta's Shard Manager both move key ranges between servers by measured load. Both use a **central assigner**.
Gossiped cluster state, a record per node merged by version, is how Cassandra and Serf spread membership. This
design puts the two together without the assigner: every node reads the directory, and decides for itself what to
serve, by the response-threshold rule of [self-organization](self-orchestration.md#part-4-self-organization-long-term).

## Phasing

1. **Fixed sub-clusters**: the directory and its gossip, families started by rendezvous hashing, atomic families
   as ordered lists, member families by share, gateways, and the contract's tests on a simulated cluster. Hard
   failures from the start.
2. **The lifecycle**: joining a family with a pull, leaving with a handoff, draining at shutdown.
3. **Self-sizing**: families joining and leaving by load, and the store role taken and given back.
4. **The per-family minority rule.**
5. **Measurements**: the JVM's real cost per request, and the table above re-derived from it.

## Open questions

- **Share placement within a sub-cluster.** Rendezvous hashing over the sub-cluster's members, of the key for
  families of many keys (subjects, fires), and of the gateway for families of members. A member joining or leaving
  moves only its own shares. Recommended.
- **How many families a cold store node serves.** Recommended: no limit but load. A store node of a small cluster
  serves every family, and a node in a hot family's sub-cluster drops the others as it fills.
- **Gossip budget.** Records change on joins, leaves and load moves. Recommended: load gossiped in coarse steps
  (each 10% of a core), so the directory changes when a decision might, not on every sample.
- **The DHT documents.** Kept as the analysis that led here, marked superseded. Or folded into this document and
  removed, if one design document is clearer.
