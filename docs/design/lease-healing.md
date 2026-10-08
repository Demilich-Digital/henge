# Design: lease healing

Leases move while the cluster runs: a refused node takes one up, a node that loses one gives it up, and a store
that forgets everything is claimed from afresh. This document is about what that has to promise: **every path
leads to healing**. That is the priority. Making the window after a store reset smaller is secondary, and
[comes last](#shrinking-the-window-secondary).

**Status.** Not built, except [the Redis epoch token](#every-redis-loss-changes-the-epoch). What exists is [taking up a lease later and giving one up](self-orchestration.md#behavior)
(`HengeLeasePoller`, `HengeLeaseKeeper`, and the negative claim in `SystemEphemeralDatastore.claim`). This is
the design for making that machinery heal from its own failures; shrinking the window after a store reset is
optional work behind it. [Decisions](#decisions) were made in discussion; [open questions](#open-questions) come with a
recommendation.

## The problem

Two things were found by running the machinery against a cluster and then reading it for stuck states.

**A reset can't be closed, only made small.** A store that comes back empty has forgotten who holds what. Until
each holder has said so again, the store will grant capacity that is in use: a pool that is open but untracked.
Nothing a node does after the fact makes that moment not have happened. Measured on three shop replicas, a
wipe of Redis never held more than two claims in three runs, but earlier builds did over-commit, and the margin
was the poller's claim landing before a holder's heartbeat.

**Some failures don't heal.** The code retries a refused lease and gives a lost one up, but several of its own
steps are "try once, log, return". Where that step is the last one that would have put the state right, the state
is wrong for good. [The audit](#the-audit) lists them.

## Decisions

- **Every state is either correct or on a path to correct, with no operator.** A step that fails is either
  retried or finished by something else, as a store write that fails is finished by its entry's TTL. It is never
  logged and dropped with nothing to finish it. A background task that fails stays scheduled, or the process
  stops being live.
- **The store is never over a node's capacity at the moment a claim is made.** Built; the healing here relies on it
  and doesn't change it.
- **A reset window is not closed, and shrinking it matters less than healing.** The design doesn't pretend to a
  guarantee. Capacities are an intentional underestimate of the real limit
  ([soft limits](../guide/06-leases-and-rate-limits.md#soft-limits)), which is what pays for the window.
- **No new coordination.** Everything below is a node acting on what it reads and what it did itself.

## The state machine

Per lease on a node, and per service standing on it:

| State | Meaning | Leaves it when |
|---|---|---|
| `NONE` | No claim, nothing hosted | A service of this node is refused, so it becomes a candidate |
| candidate | Service is remote, waiting for room | The poller finds room, claims, and hosts it (`HELD`) |
| `HELD` | Claim renewed on the heartbeat, services hosted | A renewal is refused, so `LEAVING`; or the last service releases it, so `NONE` |
| `LEAVING` | Claim written as a negative one, services being retired | The services are retired and the claim is removed, so `NONE`, and each service is a candidate again |

The healing question is whether every arrow is taken. A state that only a failed arrow leaves, and that nothing
retries, is stuck.

## The audit

For each way the system can go wrong, how it comes back.

| Path | Heals by | Stuck? |
|---|---|---|
| A holder crashes | Its claim and advertisement lapse by TTL; a candidate takes the lease up | No |
| The store is away | Holders sit still; the guard backs off and retries; heartbeats resume | No |
| The store is wiped, a holder is refused | It gives the lease up; it polls, and finds it full or not | No, but see [the window](#shrinking-the-window-secondary) |
| The store is away for longer than a lease TTL, and comes back with the same epoch | Every claim lapsed during the outage, so this is a wipe the epoch doesn't show, and a poller doesn't wait. A holder that loses the race gives up, as after a wipe | No, but the window is open, and [only the outage-ended signal shows it](#trust-less-and-wait-less) |
| Capacity changes in a rollout | The nodes that see the cluster over their own capacity give up, one at a time | No |
| A node crashes while `LEAVING` | The marker lapses by TTL | No |
| A re-host fails (the implementation won't build) | The claim is released and the candidate stays, with backoff | No |
| **An eviction fails withdrawing the advertisement** | Nothing: still hosted, on a lease written off | **Yes**: stuck 1 |
| **An eviction fails handing the claim back** | Nothing: not hosted, and never a candidate again | **Yes**: stuck 1 |
| **A refusal lands before the evictor is registered** | Nothing | **Yes**: stuck 2 |
| **An eviction withdrew the advertisement and failed** | Nothing: the service is never advertised again | **Yes**: stuck 3, part of 1 |
| **The poller or the heartbeat dies** | Nothing | **Yes**: stuck 4 |
| A lease is still `LEAVING` when another service on it is a candidate | `acquireAll` refuses it, so the poller backs off and retries once the lease is handed back | No, unless the other eviction is stuck (stuck 1) |

### Stuck 1: a failed eviction is never retried

`HengeServiceBindingFactoryBean.evict()` calls `HengeServiceRegistry.retire(...)` once. If that throws, it logs
an error and returns. There are two store calls in it that can throw, and they leave opposite states:

- **The advertisement withdrawal** (`HengeServiceAdvertiser.withdraw`, before the grace). Nothing after it ran:
  the service is still hosted, on a lease that is `LEAVING` (`Held.leaving`), so the heartbeat keeps writing the
  marker for ever. It is also unadvertised for good, since `withdraw` marked it before the store call failed and
  only a successful eviction calls `resume`.
- **The hand-back of the claim** (`HengeLeaseKeeper.release`, in `retire`'s `finally`). Everything local ran: the
  binding is remote and the implementation destroyed, and the keeper has already forgotten the lease, so the
  marker lapses by TTL. But `evict` returns before `resume` and before adding the service back to the poller, so
  **this node never hosts the service again**, and the error it logs ("it is still hosted here") is wrong.

**Fix.** Make every store call in an eviction best-effort, and the eviction can't fail on the store at all.
Each of them already has a fallback in the [first rule](../failure-modes.md#the-rules-that-make-recovery-automatic),
everything expires:

- *Withdraw the advertisement*: `withdrawn` stops the renewals whatever the store said, and an entry that isn't
  removed lapses within its TTL. A caller that still routes here meanwhile gets a `404` from a binding that isn't
  local, which means nothing ran, and [is retried](../failure-modes.md#a-call-fails) on the next host.
- *Hand the claim back*: the keeper has already dropped the lease, so nothing renews the marker, and it lapses
  within its TTL. Pollers count it as in use until then (`HengeLeasePoller.room`), so the cost is up to a TTL of
  capacity that looks taken.

So `withdraw` and the keeper's `drop`, on the eviction path, log a failure from the store (at debug, through
`GuardedDatastore.logFailure`) and carry on, as `dropQuietly` does already. What is left in an eviction is local
(switch, drain, destroy, forget the lease) and an interrupt, which only a closing context sends. No retry loop
is needed, and none of its backoff or its bound on failed passes has to be chosen.

Afterwards, the `retired` flag is reset, `resume` called and the service added back to the poller on every path
that left the binding remote, not only the one where nothing threw. That belongs in a `finally`, or `evict`'s
catch has to do the same three things.

### Stuck 2: the evictor isn't there when the loss happens

A service registers its evictor with `keeper.onEviction` *after* it becomes local, and the keeper adds it as a
holder inside `acquireAll`, *before* that. A renewal refused between the two sets `leaving` and runs the
evictors that are registered, which doesn't include this one. Registering later runs nothing, so the service
stays hosted on a lease that is `LEAVING`, with its marker written for ever.

**Fix.** `onEviction(localName, evictor)` runs the evictor at once if any lease it stands on is already
`leaving`, on its own virtual thread as `lost` does, since the grace and the drain must not run on the caller
or under the keeper's lock. That closes the gap without moving the registration into `acquireAll`, whose callers (tests, the
poller, boot) have no evictor to give.

### Stuck 3: a withdrawn advertisement that is never resumed

`HengeServiceAdvertiser.withdraw` is permanent until `resume`, and `resume` is called at the end of a *successful*
eviction. A failed one leaves the service hosted and unadvertised. With stuck 1 fixed, an eviction doesn't fail on
the store, and `resume` runs on every path that ends with the service remote. The one case left is a context
that closes mid-eviction, where it doesn't matter.

### Stuck 4: a background task that dies

`HengeLeaseKeeper.renewAll`, `HengeLeasePoller.tick` and `HengeServiceAdvertiser.renew` run on
`scheduleWithFixedDelay`, which cancels every later run if one throws, and keeps what it threw in a future
nobody reads. Each body already catches `RuntimeException` around every store call and every attempt, so what
can escape is narrow: a bug in the few lines outside those catches (the boot gate, the metrics, the registry's
list of hosted services), or an `Error`.

**Fix.** Each scheduled body is wrapped once, at its top:

- A `RuntimeException` is logged and the run returns, so the next run still happens.
- An `Error` is logged, and then it is treated as fatal on purpose. A process whose heartbeat has stopped keeps
  hosting on claims that lapse, which is [the cut-off node's overlap](../failure-modes.md#one-node-is-cut-off-from-the-store)
  with no partition to end it. Leaving it there is not healing. With the Boot starter the process publishes
  `LivenessState.BROKEN`, so the orchestrator restarts it, which is what heals it. Without Boot it logs at
  `ERROR` and the task stays stopped, as today.

Catching `Exception` and letting `Error` go, as first proposed, changes nothing: no body throws a checked
exception, and every `RuntimeException` that is expected is caught already.

## Shrinking the window (secondary)

None of this is needed for healing, and it is built after the stuck paths, and only if the measured window is
worth the work.

**The window** is the time from the store coming back empty until every holder has re-asserted its claim. While
it lasts, a claim that fits the empty store is granted, whether it is a poller's or a booting node's.

It has three parts, and each can be cut:

1. **Noticing**: how long until a holder knows the store was reset.
2. **Re-asserting**: how long from noticing to its claim being back. Today this is the next heartbeat, which is up
   to a third of the TTL, 10 seconds.
3. **Trusting**: how long other claimants wait after a reset before believing what they read. Today it is a
   whole TTL, 30 seconds, and only if the poller sees the epoch change, which needs it to have read the lease
   before the reset. Two kinds of emptiness don't change the epoch at all: an outage longer than a TTL, in which
   every claim expired, which is not a loss, so no store reports it; and a loss the store doesn't see, which for
   Redis today is a flush or an eviction ([closed below](#every-redis-loss-changes-the-epoch)).

### Re-assert on recovery

`GuardedDatastore` already knows when an outage ends (`succeeded()` after a failure) and logs it. It gains a
list of listeners, called once when an outage ends. The keeper registers `renewAll`, the advertiser registers
`renew`, and the poller registers "a reset may have happened". A holder then re-asserts within the time it takes
to reconnect and make one call, not at its next heartbeat. That cuts part 2 from up to 10 seconds to about zero,
for every reset the guard can see: a restart, a failover that dropped connections, a network partition healing.

The guard sees a reset only if a call failed during it. A store can restart between two calls and fail neither
(a client that reconnects in between, say), and then the epoch is the only sign.

`succeeded()` runs on whichever thread made the call that found the store back: a request, a heartbeat, or the
poller itself. The listeners are handed to a thread of their own, outside the guard's lock, since each of them
calls the store again.

This needs no change to the store contract.

### Notice a reset that isn't an outage

A failover that promotes a copy that missed writes resets the store with no failed call, so the guard sees
nothing. The store's *epoch* is what shows it: a read returns it, and a different one means the data may have
been lost. The poller already compares epochs on its reads. This extends it to the keeper and the advertiser,
which read nothing today.

A loss the store's epoch can't see shows nothing to either. The contract requires the epoch to change for
every loss the store can suffer in operation. Healing never depends on it, since the give-up repairs an
unreported loss, but the window does.

### Every Redis loss changes the epoch

A flush and a reboot without warning are both ordinary failure modes of Redis. Over a cluster's lifetime both
will happen, so both are covered by the design, not by asking operators to avoid them. The Redis store's epoch
today is the server's `run_id`, which sees only some of them:

| Loss | Scope | `run_id` changes |
|---|---|---|
| Restart | Everything | Yes |
| Failover to a replica that missed writes | Everything on that server | Yes |
| `FLUSHALL`, `FLUSHDB` | Everything | **No** |
| Eviction under `maxmemory` (any eviction policy; every Henge key has a TTL, so `volatile-*` policies take them too) | **Single keys**, silently | **No** |
| `maxmemory` with `noeviction` | Nothing lost: writes fail | Not needed: an outage, and holders sit still |

A token per server can't cover eviction, which takes one key and leaves the rest. **The token lives in the
key.** Each key's hash carries one field that isn't a member, `~epoch` (members are `<nodeId>/<localName>`, so
the name can't collide). Every script that writes a member creates it when it is missing, valued from `TIME`.
The epoch a read reports is `run_id` and the token together, or `run_id` and nothing when the key has no token:

- A restart or a failover changes the `run_id`.
- A flush or an eviction removes the hash, token and all, so a read sees no token, and the next write creates a
  new one.
- **Natural expiry keeps it.** The token has no field TTL, and every write keeps the hash alive at least a
  retention period past now (an hour), longer than any member. When every member lapses the token stays, so an
  empty key from the same epoch is still believed at once, as the routing table and the poller need.
- A key nobody writes for the retention period is forgotten. Its epoch then changes from one that was already
  empty, which is a change with nothing lost, and the contract allows it. No reader is fooled by it: a reader goes
  no longer than 5 minutes (the poller's longest interval) between reads, so it has seen the members lapse under
  the old token, and the rules that distrust a new epoch only distrust a *smaller* answer, or, for the poller,
  wait before claiming, which an empty key doesn't need.
- A resharding moves a key with its token to a server with another `run_id`, which is also a change with
  nothing lost.

**Reads stay reads.** A read that created the token would catch one more case: a key read with no token,
written, and lost, all between two reads by the same reader. That reader saw an empty key before and sees one
after, from the same epoch, and believes it, so the loss is unreported. It changes no decision: the reader
believed the key empty already, and a poller that finds an empty key claims it on that read rather than waiting
for a second. Paying a write on every routing read for it is not worth it. A replica still can't serve reads,
since it has its own `run_id`. It costs one small field per key.
Buckets (`tryAcquire`) carry no epoch. A lost bucket restarts empty, which over-admits by at most one bucket and
is already inside the soft-limit contract.

`noeviction` remains the better `maxmemory` policy for a Henge Redis: a full store then fails honestly as an
outage, instead of shedding claims that are still in use. The token makes the other policies safe, not good.

This is a change to `henge-redis` alone, and belongs in phase 1 with the stuck paths: it is a failure mode
that will happen, not a shrinking of the window. The test is a Redis that is flushed, and one that has a key
deleted from under it, each between two reads: the epoch changes. And one whose members all lapse: it doesn't.

The keeper's own claim needs no help: the heartbeat's `claim` re-creates a missing one on the same beat that
would notice the epoch changed, so reading the epoch there makes the keeper's re-assert no earlier. What noticing
buys is telling the advertiser and the poller, and that is bounded by the beat either way. A reset with no failed
call therefore keeps up to a beat of window unless something checks the epoch more often than the heartbeat.
Options:

- **`claim` returns more than a boolean**, an outcome that says whether the member existed (`RENEWED`,
  `CREATED`, `REFUSED`, `LEAVING`). It costs a change to the signature in `SystemEphemeralDatastore`, both stores,
  `GuardedDatastore` and `MeteredDatastore`, and every caller, and it carries the information for free. **Not
  recommended now**: it changes the contract for a signal that the next option gets cheaper.
- **Leave it.** The poller's epoch read already notices a wipe when there is a candidate, and the rest is
  bounded by a beat. **Recommended**, since this is the secondary concern.
- **A separate, cheaper epoch check** on a shorter period, triggering the same re-assert-everything as a
  recovery. Only if the measuring script shows the beat matters.

### Trust less, and wait less

With holders re-asserting at once, the poller no longer needs a whole TTL: it waits one heartbeat (TTL/3) after
anything that may have been a reset, which is the outage-ended signal as well as the epoch change, since an
outage that ends is the more common reset. That also makes recovery faster, since a node that was refused waits
10 seconds, not 30, before it looks again.

The poller asks the guard when the last outage ended instead of being told by a listener. Its own read may be
the call that ends the outage, and a listener would then fire after the read it should have changed. So `room`
reads, and then treats an outage that ended within the last heartbeat like an epoch change.

This part is not only about shrinking the window. An outage longer than a lease TTL lets every claim lapse and
leaves the epoch as it was, so today nothing makes the poller wait, and it can take a holder's place at the moment
the store comes back. The outage-ended signal is the only thing that shows that reset, and it is cheap, so it is
worth building with the stuck paths and not left to phase 3.

A node booting into a store that has just come back has no memory of it, and cannot wait: the boot would stall
for a reset it doesn't know happened. That is the part of the window that stays open, and it is small: it needs
a node to start in the few seconds between a store resetting and its holders re-asserting.

### Measuring it

The window is only small if it is measured. A script in `examples/docker` wipes the store and samples once a
second, reporting:

- the most claims ever held against the capacity, and the seconds spent over it;
- the seconds a service had no advertiser;
- the seconds until the cluster is back to the claims it had before.

It is the same loop that was run by hand to find the over-commit, kept so that it can run on a change and in CI
later. Its output is the number this design is trying to move.

## What stays open

- **The untracked pool.** Between a store resetting and the holder's re-assert, a node's pool is open and the
  store doesn't know. The design shortens that and doesn't close it.
- **A node partitioned from the store sits still,** by the store failure policy: it keeps its leases and its
  pool, and renews when it can. If another node was granted the capacity meanwhile, which needs the node's claim
  to have lapsed, the cluster is over capacity until the partition heals and the refused renewal gives the lease
  up. The overlap lasts as long as the partition, not a lease TTL; [partition tolerance](partition-tolerance.md)
  is about bounding it.
- **A loss the store's epoch doesn't show** is early expiry with no warning: healed by the give-up, with no wait
  to make it rare. For Redis, the token closes the losses known today; a store that adds a new way to lose data
  has to extend its epoch with it.
- **The margin pays for all of it.** A capacity at the real limit has no room for any of this.

## Testing

- **Stuck 1**: an eviction against a store that fails the withdrawal, and one that fails the hand-back: in both,
  the service ends up not hosted, the keeper holds nothing for it, the advertisement is resumed, and a candidate
  is registered. A failure from a store that is away is not an `ERROR`.
- **Stuck 2**: a renewal refused between `acquireAll` and `onEviction`: the evictor runs on registration.
- **Stuck 4**: a scheduled body made to throw a `RuntimeException`: the next run still happens. One made to throw
  an `Error`: liveness goes `BROKEN`.
- **Recovery**: a guarded store that fails and then answers: the keeper renews, the advertiser renews, and the
  poller settles, without waiting for a heartbeat. Also with the poller's own read as the call that ends the
  outage.
- **The window script**, run before and after, in the Docker cluster. The expectation is that the seconds over
  capacity are zero in nearly every run, and that the time to recover is bounded by the client's reconnect.

## Phasing

Each phase is useful alone.

1. **The stuck paths** (1, 2, 3 and 4), with their tests. Correctness bugs, independent of everything else. With
   them, **the poller waits after an outage ends**, since that is the only thing that shows an outage longer than
   a TTL, and **the Redis epoch token**, so a flush and an eviction are seen like a restart.
2. **The measuring script**, to see whether the window is worth shrinking. It runs a long outage as well as a
   wipe.
3. **Re-assert on recovery, and the shorter wait,** if the measurement says so.

## Open questions

- **Recovery listeners** are per guarded store. A process with more than one store (not supported today) would
  need them per store.
- **How long the poller waits** after a possible reset: one heartbeat is derived from the holders re-asserting at
  recovery, and should be checked against the measured window rather than trusted.
