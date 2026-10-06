# Gotchas

The traps a system walks into as it escalates from one process to a distributed one, in the order it
meets them, and what Henge does about each. None of them shows up while everything runs in one
process, which is why they're usually found in production. Henge's aim is to surface them on the first
day, while fixing them is cheap (see [Philosophy](philosophy.md)).

## Splitting one process into several

What was a method call becomes a network call, and everything that relied on sharing a process stops
being true.

| Trap | What Henge does |
|---|---|
| **Shared mutable objects.** In one process, caller and service hold the same object, and a change on one side is seen on the other. Over the wire it's a copy, and the change silently stops arriving. JPA entities are the worst case: attached and lazy in one process, detached and half-populated over the wire. | Every type crossing a boundary must be provably immutable, checked at compile time. [Boundaries](guide/02-services-and-boundaries.md#what-crosses-a-boundary-is-a-value) |
| **Exceptions change meaning.** A failure that was a typed exception becomes a generic HTTP error, and every handler written for it stops matching. | Exceptions are rebuilt as their own type on the caller's side; checked exceptions, which can't be, are a compile error. [Exceptions](guide/02-services-and-boundaries.md#exceptions-are-unchecked-and-carry-their-meaning) |
| **Hidden coupling to an implementation.** Code that reached past an interface to the class behind it compiles and works, until that class is in another process. | The build puts implementations on the runtime classpath only, so the reference can't be written. [Project setup](guide/01-project-setup.md#three-modules) |
| **A service that only works everywhere.** A frontend or local load balancer is useless reached over the network, but a narrowed `henge.serve`, an `internal-rest` mode or a lease that is refused would put it there. | `@RunOnEveryNode` on the interface: a process that wouldn't host it fails startup. [Everywhere](guide/04-splitting.md#a-service-that-must-be-everywhere) |
| **State in memory.** A service that keeps state in its own objects, or two services that share a stateful bean, get independent copies once split, and nothing says so. | **Not caught yet.** Keep shared state in something built to share it; detection and an isolated mode are on the [roadmap](scope.md#roadmap). [State](guide/02-services-and-boundaries.md#state-lives-somewhere-you-chose) |
| **A hung dependency.** A process that stops answering holds every caller's thread with it. | Connect and read timeouts by default. [Failures](guide/04-splitting.md#when-a-call-fails) |
| **Retries that repeat side effects.** Retrying a call that may have run charges the card twice. | Only calls that provably never ran are retried; a `404` means nothing happened. [Failures](guide/04-splitting.md#when-a-call-fails) |

## Deploying the pieces independently

Once services deploy on their own schedules, old and new code run side by side, for minutes or for
months.

| Trap | What Henge does |
|---|---|
| **Lockstep deploys.** A changed API forces every caller to upgrade at once, which is the monolith's deploy problem again, now across a network. | Versions live in the binary: old and new run side by side in the same jar, and each caller gets the version it asks for. [Versions](guide/03-versions.md) |
| **Mixed releases talking.** A record that gained a field breaks processes still running the previous release. | Added record components are ignored by old readers and default for new ones; removals and renames are a new version. [Records](guide/03-versions.md#changing-records) |

## Running many copies

With replicas coming and going, knowing who is where becomes a problem of its own.

| Trap | What Henge does |
|---|---|
| **An address book that rots.** Configured addresses outlive the processes they name. | Processes advertise themselves in the ephemeral store, and advertisements expire unless renewed. [Advertisements](guide/05-the-ephemeral-store.md#advertisements) |
| **Calls to the dead.** A process that crashed keeps receiving calls until someone notices. | A call that couldn't connect fails over to the next advertised host; a crashed process drops out within its time to live. [Failover](guide/05-the-ephemeral-store.md#failover) |
| **Shared limits that aren't shared.** Each process keeps its own in-process state, so a lease, a rate limit or a job that should hold across the cluster holds per process, and nothing looks wrong. | A process that hosts only part of the services refuses to start without a store configured; naming `in-process` says its processes share nothing. A replicated monolith looks like a single one and is **not caught**. [A split needs a store](guide/04-splitting.md#a-split-needs-a-store) |
| **Cron jobs that run once per replica.** `@Scheduled` is per process, so three replicas run the 3 a.m. job three times. Nothing shows it in development. | `@HengeScheduled` runs once per fire across the cluster, and plain `@Scheduled` is a compile error and a startup failure unless it carries `@HengeAcknowledgeThisRunsOnEveryNode`. [Scheduled jobs](guide/09-scheduled-jobs.md) |
| **A job that runs into its own next run.** A long run overlaps the fire after it, and the two corrupt each other. | A fire that finds the last run going is skipped, not queued, and says where it is running; `overlap = true` opts out. [Overlap](guide/09-scheduled-jobs.md#a-run-that-outlasts-its-interval) |
| **A job that dies with its node.** A job that runs for hours or days fails outright when the process it ran on does, and nothing resumes it. | **Not solved, and said so**: Henge has no durable store to resume from. `maxRuntime` defaults to an hour, raising it logs a reminder, and batching, with progress in your own database, is yours to write. [Long jobs](guide/09-scheduled-jobs.md#long-jobs) |

## Holding connections open

Long-lived connections turn the load around: the thing holding the connection is no longer the thing
doing the work.

| Trap | What Henge does |
|---|---|
| **A connection per user at the service.** A websocket from each browser straight to the service makes the resource-bound tier carry the most connections. | A frontend holds the clients and opens one trunk per backend node; the service sees one connection per frontend. [Channels](guide/08-channels.md) |
| **A deploy that strands connections.** Rolling a backend cuts every connection it holds, with no word to the client. | A retiring service closes its channels with `1012`, and a lost trunk closes them with `1011`, so a client knows to reconnect. Nothing is silently re-opened. [When a channel ends](guide/08-channels.md#when-a-channel-ends) |
| **One slow client stalls the rest.** A queue shared by every channel on a connection makes the slowest one everyone's pace. | Every channel has its own bounded queues, and one that overflows is closed `1013` alone. |

## Sharing what doesn't scale

Some resources don't grow with the cluster, and every limit enforced per process is wrong by a factor of
the number of processes.

| Trap | What Henge does |
|---|---|
| **Pools that multiply.** Each process opens its own connection pool, and adding processes pushes the database past its connection limit. | A lease caps the cluster's share of the resource; a process that isn't granted one never opens the pool. A `DataSource` that Spring builds (Boot's `spring.datasource.*`) opens its pool on every process before a lease could be asked for, so it is refused at startup, unless `@HengeAcknowledgeThisOpensAPoolOnEveryNode` says that is wanted. [Leases](guide/06-leases-and-rate-limits.md#leases), [A DataSource, from lease to close](guide/06-leases-and-rate-limits.md#a-datasource-from-lease-to-close) |
| **Per-process rate limits.** A limit of 10 a second, enforced by each of 5 processes, is 50 a second. | Rate limits are one bucket in the ephemeral store, for the whole cluster. [Rate limits](guide/06-leases-and-rate-limits.md#rate-limits) |
| **Limits that drift under partial views.** A store that's failing over, or a replica that missed writes, sees fewer holders than there are, and grants too much. | Every limit errs toward over-admitting, never toward refusing, and the configured capacity is an intentional underestimate, so an over-grant lands in the margin. A lost lease is noticed, but **not yet given up**: that needs [de-allocation](scope.md#roadmap). [Soft limits](guide/06-leases-and-rate-limits.md#soft-limits) |
| **Capacity that doesn't move.** When the process holding a lease goes away, its share is free, but nobody else picks it up. | **Not yet.** Leases are claimed at startup only; taking one up later is part of [self-management](scope.md#toward-self-management). |
