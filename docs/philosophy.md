# Philosophy

Henge is a set of patterns for building a system that can grow, in complexity and in scale, without being
rewritten on the way, and a framework that makes those patterns the path of least resistance. This page
is the argument for them.

## Neither monolith nor microservices

A monolith is easy to build, to debug and to change: one process, one deploy, calls that are method
calls, refactoring the IDE can see across. It gets hard when it gets big: every change deploys
everything, every part scales together, one bad component takes the whole process down.

Microservices fix exactly those problems, at a price paid from the first day: a network between every
pair of components, API versioning at every boundary, distributed debugging, and a design that has to
guess where the boundaries go before anyone knows.

Henge takes the parts of each that are worth having. You build a monolith, with its ergonomics, but
its internal boundaries are real: interfaces whose arguments are values, whose exceptions survive a
network, whose versions are explicit. Because they're real, whether a boundary is crossed by a method call
or an HTTP request is a **deployment decision**, made per process by configuration, and changed without
touching the code. The same jar is the monolith and every one of the services.

This is not a "modulith" in the usual sense, a monolith with well-organized modules, deployed as one
thing forever. Two claims set Henge apart:

1. **API versions live in the binary, not at the network.** Two versions of a service are two classes in
   the same jar, both running, selected by dependency injection and checked at compile time. No gateway
   routes between differently-versioned deployments to make it work.
2. **Topology is decided at deployment, not at build time.** Monolith, fully split, or anything between
   (version 1 of a service embedded while version 2 runs elsewhere, mid-migration) is a matter of flags.

## Gotchas, lit in advance

Every system that goes from one process to many hits the same traps: state that was shared and quietly
isn't, exceptions that change meaning over the wire, deploys that must happen in lockstep, connection
pools that multiply past what the database allows, retries that repeat something that already happened,
limits that only hold per process. They're well known, and still routinely discovered in production,
because nothing about a single process makes them visible.

Henge makes them visible on the first day, while fixing them is cheap. Mutable state at a boundary is a
compile error, not a production incident a year later. A version is something you declare, not something
you discover you needed. A connection pool is sized from a cluster-wide lease even when the cluster is
one laptop. The long-term right thing is made the easy thing early, so that growing later is a change of
configuration. [Gotchas](gotchas.md) lists every one, and how each is handled.

The monolith is the degenerate case of the cluster, and behaves identically: code that works in one
process must not do anything a cluster would refuse.

## A fast ephemeral store, instead of a coordinator

Distributed systems are usually held together by something that knows the truth: a configuration
service, a consensus store, a scheduler, an operator. Henge's processes coordinate through a **fast
ephemeral store** instead, and the store is built so that nothing in it has to be true for long:

- **Everything expires**, and is kept alive only by whoever wrote it, re-asserting it on a heartbeat. A
  process that dies stops asserting, and is forgotten.
- **Writers don't overwrite each other.** Each process writes its own entries. Where many share one
  value, a rate limit's bucket, copies merge by taking the highest level, so a partial view reads the
  bucket as correct or emptier than it is, and errs toward letting a call through, never toward refusing
  one.
- **Anything may be lost at any time.** Losing the whole store looks the same as everything expiring
  early, and every process puts back what it knows within a heartbeat.

This is convergent distributed state: replicas that diverge, writes that are repeated, a store that
restarts, all settle back to the same answer without anyone deciding it. That
lets the store be fast and cheap to run: Redis today, a DHT built for private clusters later, or anything
else that honors [the contract](ephemeral-store.md). What needs a hard guarantee (a lock, exactly-once, a
transaction) is out of its scope on purpose, and belongs in a system built on consensus.

## A critical store that can be away

A system that coordinates through one shared store has made that store a single point of failure, and
the usual answer is to harden the store, or to stop when it is gone. Henge does neither, because of what
the store holds. Nothing in it is the truth; it is what the processes last told each other, and they will
tell each other again. So when it is away, a process has two honest options: act on what it last heard,
which was true a moment ago and is rarely wrong now, or take the conservative side of the question where
it never heard. A caller keeps the hosts it last read. A lease is held, not torn down. A rate limit
falls back to its own share of the limit, so the cluster still adds up to it. What can't be answered is
said plainly, as a `503`, and a process started without the store waits, alive and not ready, instead of
crashing into a restart that wouldn't help.

That is the same design as everything above, seen from the other side. State that expires, converges and
can be lost at any time can also be unreachable at any time, and a restart of Redis is the case it was
built for: an outage, then a wipe, then every process putting back what it knows within a heartbeat. The
cost is that a long outage degrades the cluster rather than stopping it, quietly, and the first to
notice should be your monitoring. That is a better failure than most critical stores allow. The details
are in [the guide](guide/05-the-ephemeral-store.md#fault-tolerance-when-the-store-is-away).

## Toward a system that manages itself

Henge's long-term goal is a cluster that manages its own topology: processes that watch demand and
pressure through shared, decaying signals, and adjust what they host, the way an ant colony allocates
work with no one in charge. Each process decides for itself, at random, weighted by what it can see; the
colony converges on what needs doing.

That will be a hard idea for some. The usual instinct is that a system this important needs rules and an
owner. But centralized management, whether a controller or a person, is itself the bottleneck: it has to
know everything, decide everything and be available for everything, and it scales with neither the
system nor the team. Behavior that emerges from local decisions has no such bottleneck, and fails one
process at a time instead of all at once.

It isn't built yet, and isn't needed to get value from what is. But the pieces that are built already work
this way. Advertisements are re-asserted and decay; callers spread over whatever is advertised. A process
that dies gives its lease back by going quiet. In [chapter 6](guide/06-leases-and-rate-limits.md#the-cluster-deciding)
of the guide, two identical processes are started and work out between them which one builds inventory,
from nothing but the capacity of a database. Self-organized role selection is the same mechanism, applied
to the next decision. The plan is in [the design doc](design/self-orchestration.md).

The division of labor stays clear: an orchestrator (Kubernetes, ECS, ...) decides *how many* processes
run; Henge decides *what each one does*. Self-organization earns its keep on what doesn't flow freely
between processes (connection limits, memory, special hardware, failure isolation), so its job is mostly
to decide where a service should *not* run.

## Every layer is opt-in

None of this is all-or-nothing. Henge is a ladder, and every rung is somewhere a team can stop:

| Rung | You add | You get |
|---|---|---|
| 0. Monolith | `@HengeService` boundaries, the annotation processor | Monolith ergonomics, compile-time boundary rules, versions in the binary |
| 1. Split | Flags, and your orchestrator's DNS | The same jar as independently deployed services, routed by the platform you already run |
| 2. Shared store | A fast ephemeral store | Processes that find each other and fail over, with no addresses configured |
| 3. Shared limits | Leases, rate limits | Resource caps and rate limits that hold across the whole cluster |
| 4. Self-management | *Not built yet* | A cluster that decides its own topology |

You can deploy on Kubernetes with flags and DNS, never run a shared store, and never use a lease or a
rate limiter. Each rung has to be useful on its own, or it isn't done. The [guide](guide/01-project-setup.md)
climbs them in order.
