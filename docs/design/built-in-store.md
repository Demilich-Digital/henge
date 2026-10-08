# Design: the built-in store

A store built into the Henge cluster itself, with no separate system to run, honoring [the
contract](../ephemeral-store.md) for a trusted private network. It is shaped by what Henge keeps in it. The keys are
few and known: a family for each service, lease, rate limiter and scheduled job, fixed by the code. Their load is
anything but even. So nothing is hashed onto a ring: a small **directory**, gossiped among the nodes that store,
says which nodes serve each family, and each family is served by a **sub-cluster** sized to its load. A hash table
answers a question Henge doesn't have, where to put more keys than anyone can list, and had to grow special cases
for the one it does, keys far hotter than the rest; here those cases are the design.

It was worked out from first principles, one decision at a time: what the store owes its callers, how a process
comes to serve it, and what happens when processes disagree about who serves what.

**Status.** Designed, not built. [Decisions](#decisions) are agreed,
[deferred](#deferred) is real but beyond this version, and [open questions](#open-questions) are unanswered.

## Identity

Every process picks a random UUID at boot, its `nodeId()`, and keeps it for its life. A restarted process is a new
node. The id is a node's only identity: it names a membership member, the writer of a member, and is what
placement hashes. An **address** is only how to reach a node now. It travels beside the id, may change, and may be
wrong from where a dialer stands, so nothing is hashed on it. A process needn't know its own address at all.

- **A hello carries the id.** Dialing any address answers with the id of whoever is there, so ids are learned by
  contact, never derived from addresses.
- **Ids are for equality only; order and placement use a hash of the id.** Some UUID versions (1, 6, 7) lead with a
  time, so ordering by the id itself would order by boot time. Wherever nodes are ranked, compared or placed, the
  input is a hash of the id: rendezvous scores hash(key, id), and founding takes the smallest hash(id).
- **A new id has no history.** A node declared dead that comes back as a new process is a new member, with nothing
  to resume, which is part of what a [lineage epoch](#the-directory-and-the-epoch) needs.

## What the store owes

The [contract](../ephemeral-store.md) has three kinds of state. What matters about each is where its truth lives
when the store forgets it:

| State | Merges by | Its truth lives in | If the store loses it |
|---|---|---|---|
| Members (`adv`, `call`, a cron run) | union | the writers, who re-put on a heartbeat | back within a heartbeat |
| Claims (`lease`, a cron fire) | union, admitted against a sum | the holders, who renew | back at renewal; until then capacity looks free, and the store over-grants |
| Buckets (`rate`) | maximum | nowhere else | the level resets: one full burst admitted |

The store is never a system of record: its whole state is a function of who has written in the last TTL. So:

> **The store needs no durability. It owes availability, convergence, and honesty about loss.**

- **Convergence**: a reader eventually reaches a copy of every live writer's entry.
- **Honesty about loss**: the epoch changes whenever convergence is broken other than by expiry.

Buckets are the one state no heartbeat rebuilds, so they carry an error term of their own: a lost bucket costs one
burst, bounded by the bucket's size.

## Two classes of operation

- **Mergeable**: `put`, `remove`, `read`, `sample`, `count`. Any two copies combine into the right answer, so where
  the data lives is only a question of performance. Any placement is correct as long as readers eventually cover
  every writer.
- **Atomic**: `claim`, `tryAcquire`. The answer depends on seeing every other take first, so these need one point
  that serializes takes. This is the only place in the store where coordination matters.

If *k* copies of a key each act as its serialization point at once, each grants up to the capacity alone: about
(*k* − 1) × capacity too much. For a lease, the copies' union is seen at the next renewal, which reads a sum over
capacity and fails, so the error lasts as long as the disagreement plus one renewal. For a bucket, *k* primaries
admit up to *k* times the rate, and the merge by maximum keeps the highest level.

So the design is two problems:

1. **Mergeable data**: place it for load, under one rule: every reader eventually reaches every writer.
2. **Atomic data**: keep *k* at 1 almost always, and bound how long it can exceed 1. Consensus would make *k* exactly
   1; the contract only asks that *k* > 1 be rare and short, and the design lives in that gap.

## The directory and the epoch

**The directory** is the union of one record per store node, written by that node alone, versioned by it, and
replaced whole when it changes: the contract's own merge, applied to the store's description of itself. A record
holds the node's id and address, its boot time, its build, its load in coarse steps, and the families it serves,
with its role in each (a member, `joining`, or gone). Records travel by gossip among store nodes, never through the
store they describe, and failure detection rides on the same messages. Merged, they say for each family which
nodes serve it, from which each key's ranking, primary and copies are computed. A client doesn't hold the
directory; its gateway does.

**A key's epoch is the lineage of its copies.** Replication carries it from a primary to its copies, and a
joining member pulling a key takes it with the key. It is new only when a key starts with no copy to inherit
from, established for the first time or after every copy was lost, or when a copy that may have missed writes is
promoted: a key replicated asynchronously whose primary failed. A node that leaves the cluster and comes back is a
new node, with no lineage to carry.

## Decisions

- **Storing is a role a process takes, not a kind of process.** No process starts as a store node. Each decides for
  itself, at some point, to promote itself, and later to give the role back ([promotion](#promotion)).
- **Storing is a share of a process, not a dedication.** A small pool stores on every process, alongside its
  services. A process gives up services for the store only when the store's load calls for it, so a dedicated store
  node is something scale produces, never a starting point.
- **Every process is a candidate.** No deployment opts out of storing: processes stay as fungible as possible.
- **The first process is a store of one**: it finds no store, founds the membership alone, and keeps min(*r*,
  members) copies, one. The in-process store is this case: the built-in store with no peers.
- **Identity is a per-boot UUID, and ranking uses its hash** ([identity](#identity)).
- **Two tiers of membership, which is what gateways are for.** SWIM runs only among the store-role holders: about 71,000 at 20 million processes, a
  comfortable size for it, where full membership in every process would be about a gigabyte each. Every other
  process's liveness is its members in the store, renewing or lapsing, as with Redis today. A process that isn't a
  store node has no failure detector of its own.
- <a id="promotion"></a>**Promotion in steady state** is the [sizing loop](#the-sizing-loop) one level up. Every
  gateway response carries a small *store pressure* field, from the gossiped directory: the store nodes, their
  load, those joining and draining, and the floor. From it each process computes the store's wanted size, and
  promotes itself if it ranks among those wanted. In a pool no larger than the floor every process is wanted and
  stores, so small clusters need no case of their own. No new messages.
- **Founding, at cold start**, as a gossip for the minimum. A process that finds no store dials a few random seed
  addresses a round. Each hello carries the answerer's id, the smallest hash(id) it has heard of, and whether it
  knows a store-role holder. One that does is joined by referral. Otherwise the minimum spreads like a rumor, in
  O(log *N*) rounds, and a process whose own hash is still the smallest it has heard of after a few quiet rounds
  founds the membership; the rest join it once it answers as a store-role holder. A slight disagreement makes a
  couple of founders, who already see each other and merge.
- **Founding waits for the expected size, *K*.** An island can't tell from inside that it is one; it can only be
  told how big the cluster should be, as Consul's `bootstrap_expect` and Elasticsearch's
  `cluster.initial_master_nodes` tell theirs. No process founds until it has heard from *K* distinct ids. *K* is the
  size of a founding, not of the cluster: a large cluster grows from a small founding, and never cold-starts at full
  size, which an orchestrator couldn't schedule anyway. So *K* is tens at most, and a set of ids in the hello counts
  it.
  - Processes that share a seed list converge on one founder whatever *K* is, a mass restart included, since the
    smallest hash spreads over the list they share. *K* guards the rest: founding before peers appear, and seed lists
    that don't overlap, such as a seed Service per deployment. No such group reaches a *K* larger than itself, so
    each waits, saying "saw 12 of an expected 40", rather than founding an island that would never meet the others.
  - A missing *K* would show only at a cold start, the worst time to learn of it, so it is required, with no
    default, whenever seeds are set.
- **Ready while founding, and only then.** A Henge process isn't ready without a store, and Kubernetes leaves
  unready pods out of a Service's DNS, so at a cold start nobody would see anybody. A process that finds no store
  anywhere it can reach reports ready while it founds, so that it is listed, and answers every request but health
  checks with a `503`, as it does today before it reaches a store. The lie is scoped exactly: a process that learns
  of a store-role holder by referral is in a warm cluster, and stays honestly unready until it joins, even when that
  store is away. Otherwise a rolling update would take a pod that says ready and serves `503`s as leave to stop a
  working one. So no `publishNotReadyAddresses` is needed. A seed name that is load-balanced rather than headless
  shows as one address answering with different ids, and is refused.
- **Families are established before anything writes to them.** Every family a process uses is declared by its code
  and configuration: its service versions, leases, rate limiters and jobs. A process isn't ready until each of them
  is in the directory, found or established, so no family is ever created on the request path, and no write lands
  in a family that two founders placed differently. Establishing one is founding again, on a smaller scale: propose
  it, let the directory gossip a few quiet rounds, and the smallest hash wins. That wait happens once in a family's
  life, before readiness, where a rolling deploy already waits. Keys made at run time, a subject's bucket or a
  job's fire, are keys inside an established family, placed by hash within its sub-cluster, and never in the
  directory. A family whose sub-cluster is lost whole is established again by the next process that needs it, with
  a new epoch. Leases and fires, which a process claims without any traffic, are covered by the same rule, so a cold
  start needs no grace period for atomic operations.
- **A key's primary is computed, not recorded.** A family is the unit of placement, and a key the unit of
  leadership: a lease has one primary, a limiter one per subject. A key's copies are its sub-cluster's members
  ranked by hash(key, id); the first live one is the primary, the next *r* − 1 hold
  copies. Everyone with the same view computes the same primary, and there is no recorded list to keep consistent.
  - A node leads about 1/*n* of each sub-cluster's keys, across many families, at no cost but CPU, since each key
    stands alone.
  - A dead node's keys each promote their own next member, so its load scatters over the sub-cluster rather than
    falling on one backup.
  - A node's failure, or stall, is correlated over every key it leads, which makes the stalled primary weigh more.
  - A joining member ranks first for about 1/*n* of the keys, and takes them over only after pulling them from the
    current primary: a handoff, with no epoch change.
- **A node sheds families as one gets hotter.** A node in a hot family's sub-cluster gives up its other families as
  its load from that one grows, so a hot family's members come to lead only its keys.
- **Promotion is passive and self-selected, at every level.** No family recruits. A process promotes itself to
  the store from the pressure its gateway reports; a store node joins a family it ranks among the wanted joiners
  of, from the directory, and leaves the same way. A hot family draws spare store nodes, which makes the store
  short, which draws processes: one controller, run at two levels, with no level aware of the other
  ([the sizing loop](#the-sizing-loop)).
- **Claims are replicated synchronously and fenced by their copies; buckets are replicated asynchronously**
  ([the stalled primary](#the-stalled-primary)).
- **A service's family is the service, and its versions are keys in it**: `adv:Foo` holds `Foo@3` and `Foo@4`, as a
  limiter's family holds its subjects. At most two versions are active at once, the one in place and the one
  rolling out, so a rollout moves load between two keys on the same sub-cluster: the directory doesn't change,
  nothing migrates, and the family is sized by the service's load, which a rollout leaves about level. An old
  version's key empties and lapses like any other.
- **A gateway is a tree of proxies, to keep connections few.** It is a store-role holder acting for a process that
  doesn't hold the role, not a role of its own: only a store node has the directory and a place in the membership,
  which a client lacks. A client holds two connections, its gateway and a backup; a gateway holds connections to
  the sub-clusters of the families its clients use, a few nodes each. Gateways exist only once there are clients, so a pool no larger than the
  floor has none.
  - **A client always goes through its gateway**, with no direct path at small sizes: one path, and no client
    holds a slice of the directory to go stale.
  - **A client's gateway and backup are the top two store nodes by rendezvous hash(client id, node id)**,
    learned by referral. Not by deployment: a gateway's clients are then a random sample of the cluster, which
    [shares](#shares-the-next-scaling-bottleneck) will rely on. A gateway's clients use most families, so it holds
    connections to most sub-clusters, low thousands at most.
  - **A gateway refers, looks up and forwards, and reports store pressure**, and keeps nothing a store node can't
    rebuild.
  - **Failing a gateway over is local and always safe**, for atomic operations too: a gateway serializes nothing,
    and the key's primary is the same through any gateway. A gateway never retries an atomic operation itself; the
    client sees `StoreUnavailableException` and decides.
- **A rollout adds keys and removes them; it never renames one.** A changed name is a new key and a dropped one.
- **A family lives as long as some running code declares it, and nothing counts declarations.** A node never
  hosts a family its own code says doesn't exist, so as the processes that declare a removed family roll away, it
  loses its hosts and goes extinct. A process still using a family that has gone extinct, as an exiting process
  may, treats it as that key's store being away, the per-key behavior that already exists: no alarm, no broken
  liveness.
- **The keyset is a property of the build.** Every family's name is in code: `@RateLimited`, `@LeasedResource`,
  `@RequiresLease` and `@HengeScheduled` name it, and configuration holds only its numbers. A process instantiates
  only what its deployment enables, so the annotation processor also emits a **keyset manifest**, every family the
  build declares, and "a family this build says doesn't exist" is a lookup in it. A cluster runs one build at
  steady state, and two during a rollout or a rollback: the one in place and the one replacing it, which Henge's
  API versioning exists to make safe. Each node hosts only the families its own build declares, so:
  - **A family only the old build declares** is hosted by old nodes alone, and goes extinct as they roll away.
  - **A family only the new build declares** is hosted by new nodes alone. Early in a rollout there may be one, so
    such a family keeps min(*r*, its eligible hosts) copies, and gains the rest as new nodes arrive, which the bias
    to young nodes already sends to it. Losing that lone copy is a loss like any other: a new epoch, established
    again.
  - **A family both declare** is hosted by both, and moves to the new nodes with the rest of the store.
  - **A rollback is the same rollout with the roles swapped**: the families the aborted build added go extinct with
    its nodes. Two builds talk to each other only through the versioned wire protocol.
- **Churn favors young nodes.** A rollout replaces every store-role holder, since storing is a share of ordinary
  processes. So:
  - **A departure is a gap from its first instant.** A store node announces on `SIGTERM`, before anything else,
    that it is gone, and shortfalls count it as gone, so the store is short at the start of the grace period and
    processes promote during it ([leaving](#leaving-is-failing)).
  - **Joins weight the youngest nodes first**, by the boot time each directory record carries. A key handed to
    a node next in line to be replaced would move again a minute later; weighted to the young, it moves about once
    a rollout, onto a node that stays. This orders by time on purpose, and doesn't depend on builds, so a scale-up
    or an unplanned replacement gets it too. New nodes are idle as well, so the weight by spare capacity agrees.
    It is one of the two weights in the [sizing loop's](#the-sizing-loop) ranking.
  - **The orchestrator limits churn by completed shutdowns.** A decent rollout waits for a node to finish
    shutting down, or paces itself by successful shutdowns, so the grace period, which covers the services'
    teardown through the store, is what paces the store's turnover.
- **Only a node that holds a key, in the role it is asked to play, answers for it.** A gateway routes by its own
  copy of the directory, which gossip keeps a little behind, so a request can reach a node that left the family,
  handed the key to a newer member, or is still pulling it. To such a node, "nothing here" is true and is the worst
  answer: a service with no hosts to a reader, a free lease to a claim, which would make it a second primary, and a
  loss with no epoch change, which the contract forbids. A fresh epoch would be honest, but would make every reader
  settle a TTL whenever gossip lags. So:
  - Every request carries its family and key. A node that isn't in the family, doesn't hold the key, or,
    for an atomic operation, isn't its primary, answers **"not mine"** with its own versioned view of the family's
    members. The gateway merges that into its directory, recomputes and retries, so a wrong guess also speeds the
    gossip.
  - Redirects are bounded, at two. Past that, views genuinely disagree, and the gateway reports that key's store as
    away: fail fast and sit still rather than guess.
  - A member still pulling a family's keys, declared `joining`, takes writes and redirects reads until its pull is done.
  - The same holds for writes, a `put` included: there is no accepting a write a node isn't responsible for, since
    every node can compute what it is responsible for. A stale directory costs a hop, never a wrong answer.
- **A promotion keeps the epoch of keys replicated synchronously, and changes it for keys replicated
  asynchronously.** Claim keys (leases, fires, runs) are written only by `claim`, renewals and releases included, and
  a claim is answered only once every copy has it. So a copy promoted after a failure has every answered claim, and
  perhaps an unanswered one, which "a call that fails may have happened" covers: nothing is lost, and the epoch
  stands. A copy's deadlines start when a write reaches it, a little after the primary's, so its entries outlive
  the primary's by the replication delay: a sum that reads high, the side that refuses. A `joining` member is not a
  copy until its pull is done. Buckets and members, replicated asynchronously, may have missed writes, and their
  epochs change on a promotion. A key whose every copy is lost starts a new lineage either way.
  - **What it buys:** a primary's death costs leases only the seconds of detection, not a TTL of settling, as a
    Redis failover does today.
  - **What it promises:** the fence is exact. Every write to a claim key waits on every copy, an invariant the
    contract tests check by killing a primary just after an acknowledged claim and finding the claim, under the
    same epoch, on its successor.
- <a id="leaving-is-failing"></a>**Every departure is a failure; there is no polite path.** A random kill is always
  possible, so the design survives one, and a second path for leaving politely would only be more to keep correct.
  A node leaving in a rollout is a node failing, with one shortcut: on `SIGTERM` it announces its own death, in the
  same message as its departure, so membership needs a round of gossip rather than a suspicion timeout. Killed
  without warning, it takes the same path, slower. What that costs:
  - **Claim keys, nothing.** They are fenced, so a promoted copy is complete and the epoch stands: leases never
    notice a rollout.
  - **Member and bucket keys change epoch** with every store node a rollout replaces, and their readers distrust a
    smaller answer for a TTL: a caller keeps its host list for 30 seconds, as its routing table already does, and a
    limiter keeps the larger count and admits a little less.
  - **Until the death is known**, atomic operations on the node's keys wait: a round of gossip when announced,
    seconds when not, well inside a lease's TTL. A limiter whose key is away falls back to its local share.
- **No graceful total shutdown.** A cluster that stops has nothing to persist; its next start is a cold start.
- **Short of death, requests survive on their own.** Membership declares deaths; everything less than one, a slow
  node, a lost packet, an overload, the requests themselves get through:
  - **Deadlines, not timeouts per hop.** A request carries the deadline its client set, and every hop honors it,
    as gRPC propagates them. A hop that can't finish in time gives up at once, rather than do work nobody waits
    for.
  - **Mergeable reads are hedged.** A gateway sends a read to one copy, and if it hasn't answered by about that
    copy's 95th percentile, to a second, and takes the first answer: about 5% more reads, and no slow tail.
  - **Atomic operations are neither hedged nor retried.** They go only to the primary; a slow one makes slow
    claims on its keys, and a deadline missed is `StoreUnavailableException`, for the caller to decide. The fence
    keeps that safe.
  - **Overload is refused, not queued.** A store node bounds its queue and answers "busy" past it: a gateway sends
    a read to another copy, and an atomic operation becomes `StoreUnavailableException`. A queue turns overload into
    a stall, which looks to everyone like a stalled primary; a refusal keeps it short and seen, and the sizing loop
    sees the load.
- <a id="the-majority-rule"></a>**A node that reaches fewer than half of the last stable membership treats the
  store as away.** A store built into the nodes splits with them: a cut inside the cluster gives each side a
  membership that believes it is whole. Without this rule, each side would declare the other dead, a primary would
  recompute its copies from the members it still reaches and grant again, and the other side would promote its
  own: two primaries for the length of the cut. With it, only a side holding a majority promotes or recomputes
  copies, and a minority side's primaries can't replace the copies they lost, so the fence holds across a cut. The
  minority sits still and answers `503`, as the store failure policy has it. The last stable membership is the one
  before the current wave of deaths, so ordinary churn moves it on.
  - **A distributed deploy has at least three nodes**, so that a majority exists and one loss never stops the
    store: the floor of *r* = 3 again. A seeded cluster founded with *K* below 3 is warned of, not refused: with
    two, losing either is a minority. Losing half the store-role holders at once stops the store, the usual price
    of a majority.
- **Bootstrap is by referral, from any process.** Like a peer-to-peer network's bootstrap, but to the subset of processes holding
  the store role rather than to everyone. A newcomer resolves the seed DNS name, asks any process it finds for its
  store nodes (every client knows its gateway and backup), and that store node refers it to its proper gateway. No
  separate DNS name for the store is needed, so the path is the same whether roles are fixed or self-selected.
- **The failure detector is SWIM without indirect probing, with Lifeguard**, run among the store-role holders
  ([below](#the-failure-detector)).
- **Unreachable is dead, locally; dead for everyone needs the group.** An observer that can't reach a store node
  stops using it at once, for its own traffic. A store node leaves the directory only when membership declares it
  dead.
- **Mergeable operations fail over locally; atomic operations fail over only globally.** A backup refuses `claim`
  and `tryAcquire` as "not primary" while the primary is alive in its own view. A gateway that can't reach a
  primary reports that key's store as away until membership declares the primary dead, a few seconds.

## The failure detector

SWIM (Das, Gupta and Motivala, DSN 2002) keeps a full member list at each member for a constant cost per member,
whatever the group's size.

- **Probing.** Each protocol period, a member pings one other, in a shuffled round-robin order. No ack within the
  timeout, and the target is **suspect**.
- **Suspicion and refutation.** A suspicion spreads; a member that hears it is suspected raises its *incarnation*,
  a number only it may raise, and announces it is alive. The higher incarnation wins everywhere. A suspicion nobody
  refutes within the suspicion timeout becomes **dead**.
- **Dissemination** rides on the pings and acks already being sent, and reaches every member in O(log *M*) periods.
- **Lifeguard** (HashiCorp, 2017): a member that is itself slow, as a JVM in a collection is, stretches its own
  timeouts, and a suspicion expires sooner as independent members confirm it.

SWIM's indirect probe, asking *k* others to ping a target before suspecting it, is left out. It separates a dead
target from a bad path between two members, which matters only under a partial partition, and partitions are
[assumed global](partition-tolerance.md). Suspicion and refutation already keep one observer from killing a healthy
member: the target hears the suspicion through anyone else and refutes it. A member that nobody hears refute is
unreachable from the group, and dead is the right answer. What leaving it out costs is refutation traffic under a
partial partition, an observer re-suspecting a member that keeps refuting, not wrong answers.

With atomic operations failing over only globally, *k* > 1 has one remaining cause: membership declares a primary
dead while it is still running and serving. That is the **stalled primary**, and its copies fence it
([below](#the-stalled-primary)).

## The stalled primary

A primary, P, pauses (a long collection, a frozen VM) past the suspicion timeout. Membership declares it dead, and
the next in rank, Q, serves its keys. Then P resumes, unaware: it works through requests queued before the pause,
and answers gateways that haven't yet heard of its death. Two nodes grant the same lease.

A timeout can't stop this. A primary that serves only while it has heard from the group recently, by its own
clock, can check, pause, and grant after its successor has taken over. Only what is written to can fence a
write. Here that is the copies:

> **A claim is answered only once all of its key's copies have acknowledged it. A copy that has taken over as
> primary, or follows a newer lineage, rejects the old primary's writes.**

A key's copies are the next *r* − 1 in its rank, and its successor is the first live one among them, so every node
that can take a key over already holds a copy of it. P's write reaches Q either before Q takes over, and Q's state
has the grant, or after, and Q rejects it: P's claim fails, and P learns it was deposed. If every copy is lost, the
member that takes over starts a new lineage, an honest loss with a new epoch, and P, hearing from no copies, grants
nothing. No leader lease, clock or token is needed.

- **What it costs:** a claim waits on its copies, a round trip, at the rate of
  heartbeats. A dead copy blocks claims on its keys until membership declares it dead, the wait atomic operations
  already have.
- **Buckets are not fenced.** `tryAcquire` is on the path of every limited call, and the contract already allows a
  bucket that reads low: copies merge by maximum. Buckets replicate asynchronously, and a stalled primary's takes,
  if lost, over-admit by the takes of its stall.
- **So no failover over-grants a lease.** The only over-grant left is the contract's own, the store losing data so
  that capacity looks free until renewal, which the epoch change and [settling after an
  outage](lease-healing.md) already cover.
- **P comes back** as a member that ranks first for its keys again, and so takes them back by the join path: it
  pulls from Q, then serves. A handoff, with no epoch change.

## The sizing loop

Each family's sub-cluster, and the store as a whole, is sized by a control loop designed as one, rather than by
rules that happen to form one. Self-organization is organic control loops; this is the same thing, built on
purpose, with its stability shown rather than hoped for.

**The plant is static.** A family's per-member utilization is *u* = *L*/*n*, its total load *L* (in nodes) over the
*n* nodes holding copies of its keys. Reads are mergeable, so gateways spread them over the copies, and writes, all
through each key's primary, are a small part of the load while keys have no [shares](#shares-the-next-scaling-bottleneck).
So the size wanted is computed, not found by trial: *n*\* = max(*r*, ⌈*L*/*u*_target⌉). What is left to control is
tracking *n*\* through two delays, load known by gossip and joins that take a pull to finish.

- **Sensor.** Each member reports its load from the family, smoothed by a moving average over about a decision
  period, in coarse steps (a tenth of a core) so the directory changes only when a decision might. *L* is their sum.
- **Setpoint, with a deadband sized for whole nodes.** Grow when *L*/*n* > *u*_high. Shrink only when
  *L*/(*n* − 1) < *u*_high − margin, so that taking a node away can't make the family short again: going from 3 to
  4 nodes moves each by 25%, and no limit cycle between two sizes is possible.
- **Predictor: what is in flight counts.** The error is *e* = *n*\* − (active + joining − leaving), with joining and
  leaving declared the moment they are decided. The time to pull copies never enters the loop: a join counts when
  it is declared. This is a Smith predictor, acting on the predicted state of a plant whose delay is known. The
  delay left is the gossip of the declarations.
- **Controller: an integrator of gain *k* = ½.** Each period asks for *k*·*e* joins, or leaves.
- **Timing.** The decision period *T* is at least one propagation of the directory, so each decision sees the last
  one's declarations, and the delay *d* left is at most a period.
- **Asymmetry.** Grow fast, at most max(1, *n*/2) joins a period. Shrink slowly, to the largest *n*\* of a
  stabilization window (five minutes, say), as Kubernetes' horizontal autoscaler does: a dip doesn't shed nodes
  needed again a minute later, and every move costs a pull.

**Stability.** With in-flight work counted, the loop is *n*[*t*+1] = *n*[*t*] + *k*·*e*[*t* − *d*]. With *d* = 0 it is
stable for 0 < *k* < 2 and never overshoots for *k* ≤ 1. With *d* = 1, *z*² − *z* + *k* = 0, it is stable for
0 < *k* < 1 and never overshoots for *k* ≤ ¼. At *k* = ½ it is monotone while the timing holds, and still stable,
with a damped overshoot, when gossip runs a whole period late: the margin is stated, and measurement checks the
numbers rather than whether there is a loop.

**The actuator is a ranking.** For *k*·*e* joins, each candidate ranks every candidate for the family by weighted
rendezvous hashing of hash(family, id), weighted by spare capacity and by youth, and joins if it is among the top
⌈*k*·*e*⌉ in its own view; leaving ranks the members the same way, least needed first. Every node decides for
itself, from what all share, so it is still self-selection, with no coordinator. A probabilistic response threshold
would ask each to join with probability *k*·*e*/candidates, and get a binomial count whose spread, √(*k*·*e*), is as
large as a small error: noise fed back into the loop. The ranking gets almost exactly the count asked for, off only
where views differ, and by little.

**Two loops in cascade.** Promotion to the store is the same controller a level up: *M*\* = max(floor,
⌈*L*_store/*u*_target⌉), from the pressure gateways report. Two loops drawing on the same spare nodes could fight,
so the outer runs several times slower than the inner, a period of 3*T*: the families settle onto the pool there is
before the pool decides whether to grow.

## Deferred

Real, and correct, but beyond this version of the store. Kept so that nothing in this version rules them out.

### Zones

This version treats every process as being in one zone.

- **A zone is two things clouds bundle**: a failure domain, what fails together, and a cost domain, where traffic is
  free and fast and crossing it is billed. One label, `henge.zone`, would carry both; where they differ the failure
  meaning wins, since copies must span failure domains and locality is only a preference.
- **Placement it would change**: a key's copies the first *r* members of its rank in distinct zones; a client's
  gateway among the store nodes of its zone; a share's primary in its gateway's zone; reads from a member in the
  reader's zone. Only replication, the acknowledgements claims wait on, and claims from other zones would cross.
- **Losing a zone** would lose at most one copy of each key.
- **Learning one's zone**: a property, filled from the orchestrator's topology label or the cloud's metadata. Unset
  means one zone, an honest loss of resilience rather than of correctness, warned of when members disagree.
- **`sample` and zones**: drawing from everything a member holds, primaries and copies, would give a cluster-wide
  sample at in-zone cost; drawing from primaries alone would be zone-aware routing.

### Shares: the next scaling bottleneck

This version gives each key one primary and as many copies as its load needs. A member key's reads are mergeable,
so any copy answers them, and a hot key's reads scale by adding copies. Its writes don't: every member renewal
goes through the key's primary, and only splitting the key spreads them. So the first wall this version meets is
**one key's write rate outgrowing one node**:

- A host renews its advertisement every 10 seconds (a third of its 30-second TTL), 0.1 writes a second.
- At the assumed 50 µs a request and a 40% target load, a node takes about 8,000 writes a second.
- So **about 80,000 hosts of one service version** fill its key's primary. Limiter membership is the same
  arithmetic, with the processes calling the limiter as the writers.

That is past what one orchestrator cluster holds (150,000 pods) for any service that isn't most of the cluster.
Beyond it, a key's members are split into **shares**:

- Each share is a slice of the key's members with its own primary and copies, placed by rendezvous within the
  family's sub-cluster. Members are assigned by the hash of the gateway they are written through, so a gateway
  writes one member per family; gateways chosen by the client's hash make each share a random subset of the key,
  and a sample of a share a sample of the whole.
- **The epoch of a sharded key** must change when any share loses data, since `count` sums them all: a vector of
  the shares' lineages, or a hash of it.
- **A count gossiped among a key's shares lags**, and reads low while a key grows, so a limiter over-admits by as
  much as the lag allows; it has to be bounded.
- **A client that changes gateways** writes into another share, and appears in two until its old entry lapses: a
  count that reads high, the safe side.

## Phasing

Each phase ends with a store that is correct at some size, never with half a mechanism.

1. **Every process stores.** All processes are members, so there are no clients and no gateways, and every
   family has *r* copies placed by rendezvous: a complete store for clusters up to the low thousands. Identity and
   the hello, seeds, founding with *K*, ready while founding; SWIM with Lifeguard and no indirect probe, the gossiped
   directory, "not mine"; the keyset manifest, families established before readiness; computed primaries, claims
   fenced by synchronous copies, everything else replicated asynchronously, epochs by lineage, the majority rule; departures as
   announced failures, deadlines, hedged reads, refusal under overload. Tested by `EphemeralDatastoreContract` on a
   simulated cluster with injected faults (kills, stalls, slow nodes, lost messages), and by the fence's own test:
   a primary killed just after an acknowledged claim, and the claim on its successor under the same epoch.
2. **The sizing loop for families**: copies by load, the controller, the ranking and the stabilization window.
   Tested by a step and a ramp of load in simulation, against the stated margin.
3. **Promotion to the store, and gateways**: the same controller a level up, in cascade, with referral to
   gateways and store pressure. From here the store grows past the size where every process can store.
4. **Measurements**: the JVM's real cost per request, the directory's propagation time, the loop's step response,
   and a rollout simulated end to end, from which this document's numbers (8,000 writes a second, 80,000 hosts) are
   derived again.

**Transport.** Store traffic uses the [channel trunks'](channels.md) mechanism, its framing, heartbeats and
shared-secret authentication, in an **instance of its own**: separate connections, so that a busy application
channel never holds a lease's renewal behind it in a shared buffer.

**Deferred**: [zones](#zones), [shares](#shares-the-next-scaling-bottleneck), and [degraded
nodes](#open-questions), which need a design of their own.

## Open questions

- **Degraded nodes.** A node that is slow but not dead (a noisy neighbour, a failing disk, a sick JVM) is
  nasty for Henge as a whole, not only for its store: hedging hides it from reads, but its primaries stay slow, and
  its services slow their callers. It deserves its own design. The direction is **self-removal**: a node whose
  own health (Lifeguard's measure of it, and its own latencies) stays bad announces its death, as a departing node
  does, and the orchestrator replaces it. Self-selected, with nobody voting a node out.

