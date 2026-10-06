# Design: scheduled jobs

**Status.** Built, and documented in [chapter 9](../guide/09-scheduled-jobs.md): `@HengeScheduled` with the fire
claim, the run claim with its heartbeat, `overlap`, `maxRuntime` and the batching reminder, the startup and
compile-time checks that refuse plain `@Scheduled` without `@HengeAcknowledgeThisRunsOnEveryNode`, and the
meters. The guide chapter is the reference for behavior; this is why it is the way it is.

A scheduled job in Spring runs once per process. Deploy three replicas and `@Scheduled(cron = "...")`
runs three times, silently, and only in production: with one process it looks correct. It is the same
shape as every other gotcha in [Gotchas](../gotchas.md), and gets the same treatment: make the safe
thing the easy thing, and make the unsafe thing impossible to write by accident.

## The durable record is the annotation

A job queue can't be built on the [ephemeral store](../ephemeral-store.md): a job that exists only
there is lost when the store is wiped, and the contract says anything may be, so "accepted means it
will run" can't be promised. A scheduled job needs no such promise. Its durable record is the annotation
in the jar: every node knows every job, and when it is due, with no store involved. The store only
decides **who runs it**, which is what `claim` is for. So this is not a queue, and a job that must
not be lost belongs in the application's own database, not here.

## The annotations

| You write | Meaning |
|---|---|
| `@HengeScheduled(cron = "...")` | Runs once per fire, across the cluster. |
| `@Scheduled` + `@HengeAcknowledgeThisRunsOnEveryNode` | Spring's scheduling, and the author has said they know it runs on every node. |
| `@Scheduled` alone | An error. |

`@HengeScheduled` supports `cron` only. `fixedRate` and `fixedDelay` have no cluster-wide meaning, since
each node counts from its own boot; they are rejected with a message pointing at `cron`.

### Enforcement, in two layers

1. **Compile time**, in `henge-processor`: a `@Scheduled` without the acknowledgement, on the method or
   a class it is nested in, is a compile error naming both fixes. The processor is opt-in per module, so
   this alone is not enough. It is also only run by the compiler for a module with annotations it claims,
   so it can't see a `SchedulingConfigurer`, which carries none; the startup check does.
2. **Startup**, in `henge-spring`: a bean post-processor fails context startup for any `@Scheduled`
   method without the acknowledgement, on the method or its class. A `SchedulingConfigurer` registers
   its tasks in code, where an annotation check can't see them, so one is refused unless the configurer
   itself carries the acknowledgement. Beans of Spring's own classes (`org.springframework.*`, such as
   Spring Session's cleanup) are left alone: they schedule what they need and the author can't annotate
   them. A third-party library outside that is not exempted, and there is no setting to exempt one yet.

## How a cluster runs a job once

Two claims, both on the existing `claim` primitive, one per concern.

**The fire** (deduplication). Key `cron:<job>@<nominal fire instant>`, capacity 1, TTL long enough to
outlast clock skew and jitter. Every node computes the same instant from the cron expression, so skew
doesn't matter, and the first `claim` wins. A node only runs a fire less than five minutes late, and the
claim is kept for ten, so it can't lapse while a node still believes the fire is due; a later one is
skipped, which is the no-catch-up rule. It is **not released on completion**: releasing it when a
fast job finishes lets a node whose clock is slightly behind find it free and run the fire again. Fire
keys expire by themselves, so there is nothing to clean up, and a wipe looks like early expiry.

**The run** (exclusion, "don't overlap"). Key `cron:<job>:running`, capacity 1, a short TTL renewed on a
heartbeat while the job executes and released when it ends. A worker that dies stops renewing and gives
the claim back one TTL later, the same way a lease does. Without it, a run that outlasts the interval
overlaps the next fire, since the fires use different keys. With `overlap = true` the key includes the
fire, `cron:<job>:running@<instant>`, so no other fire holds it: a fire is never in the way of the last
run, but each run is still visible, still renewed and cut off at `maxRuntime`, and a second node that won
the same fire (a store failing over) is refused by it. The code has one path for both.

Order: the node that wins the fire then tries the run claim. If that is refused, **the fire is
skipped**, everywhere, and logged ("skipped: the last run is still going on node X", from a `read` of the
running key; a claim's value is only its amount, so there is no start time to report). It is skipped, not deferred. Deferring is a queue, with backlog, catch-up and ordering,
which is exactly what this design declines to build.

## What it promises, and what it doesn't

- **At most once per fire in the normal case, with a bounded chance of a duplicate.** `claim` can
  over-grant under a partial view, as it can for a lease. Jobs must be idempotent. The docs say so.
- **No catch-up.** Nothing records "last ran" durably, so a fire that nobody was up for is skipped, as
  it is in Spring today.
- **Store unreachable: the fire is skipped and counted.** A skipped run is better than N duplicate
  ones, and matches "sit still, fail fast". It is a metric, so monitoring is the first to notice.
- **One process, in-process store:** it just runs, with no configuration.

## Long jobs, and the batching the framework can't do

People write cron jobs that run for hours or days, and that fail outright if the node they run on dies.
Henge can't fix this: surviving the loss of the node means durable progress, and there is no durable
store here, on purpose. What it can do is stop pretending, and say so where people will read it.

- **`maxRuntime` defaults to one hour.** When it passes, the heartbeat stops renewing the running claim
  and the job thread is interrupted. The claim then expires, and the next fire may start. An interrupt
  is not a guarantee, so the old run may still be going: overlap is bounded, not impossible. The
  default also stops a hung job, which keeps heartbeating from a stuck thread, from blocking every
  future run forever.
- **Raising it is the moment to remind.** A job that legitimately needs longer sets `maxRuntime`
  itself, and that is where the guide and the startup log say the same thing: a node that dies
  mid-run loses the run, which starts again from nothing at the next fire. **Splitting the work into
  batches, recording progress in your own database, and making each batch safe to repeat is your job,
  not Henge's.** A job over the default logs this once at startup, with its name and the value it set.
- **A lost renewal.** A renewal the store *refuses* means the claim lapsed and another node was given the
  job (a long pause, a partition, a wipe): the run is interrupted. As with leases it is noticed late, so
  the same bounded overlap applies. A renewal that can't be *made* because the store is away is retried and
  the run goes on: nothing else can have been given the job without the store either, and the first
  renewal to get through takes the claim back if nobody else has.
- **A run on this node has its own claim name**, so a second run starting on the same node while the first
  is going is refused like one on another node, instead of being taken for a renewal of the first.

## The local acknowledgement

`@HengeAcknowledgeThisRunsOnEveryNode` is for jobs that really should run everywhere: evicting a local
cache, flushing local metrics. The name is the point: the person writing it has typed the consequence.
`@Scheduled` itself is untouched; Henge only refuses it without the acknowledgement. Overlap on a
local job is an in-process flag and needs no store.

## Metrics

Per job (the tag is `job`, bounded by the code), through `SystemMetrics` like the rest:

- `henge.scheduled.fires`, by `outcome`: `ran` (this node won the fire and started a run), `taken` (another
  node won it), `still-running` (won, but the last run of a job that doesn't overlap is going), `duplicate`
  (won, but another node won the same fire and is running it: the store was failing over), `late` (too late
  to run, never caught up) and `store-unavailable` (the store couldn't be asked).
- `henge.scheduled.runs`, by `outcome`: `succeeded` or `failed`, a run that returned or threw.
- `henge.scheduled.interruptions`, by `reason`: `max-runtime` or `claim-lost`.

Its claims are also timed with the rest of the store's operations, as `henge.store.operations` with
`purpose=scheduler`. The fires a cluster is owed are not counted anywhere: nothing records that one was due
while no node was up, which is the no-catch-up rule seen from the other side.

## Build order

1. `@HengeScheduled` with the fire claim, cron only, on the in-process store. *(built)*
2. The run claim, its heartbeat, `maxRuntime`, and the startup reminder. *(built)*
3. The startup check for `@Scheduled`, and the acknowledgement annotation. *(built)*
4. The processor check. *(built)*
5. Metrics, a guide chapter, and a gotchas entry ("cron jobs that run N times"). *(built)*
