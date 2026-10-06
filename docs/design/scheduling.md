# Design: scheduled jobs

**Status.** Step 1 of the [build order](#build-order) is built: `@HengeScheduled` with the fire claim, cron
only. Not built: the run claim and `maxRuntime` (so a run that outlasts its interval can overlap the next
fire), the startup and compile-time checks for plain `@Scheduled`, `@HengeAcknowledgeThisRunsOnEveryNode`,
metrics, and the guide chapter.

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

1. **Compile time**, in `henge-processor`: a `@Scheduled` without the acknowledgement is a compile
   error naming both fixes. The processor is opt-in per module, so this alone is not enough.
2. **Startup**, in `henge-spring`: a bean post-processor fails context startup for any `@Scheduled`
   method without the acknowledgement, including tasks registered through `SchedulingConfigurer`,
   which an annotation check can't see.

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
overlaps the next fire, since the fires use different keys. It is on by default, and `overlap = true`
turns it off.

Order: the node that wins the fire then tries the run claim. If that is refused, **the fire is
skipped**, everywhere, and logged ("skipped: still running on node X since T", from a `read` of the
running key). It is skipped, not deferred. Deferring is a queue, with backlog, catch-up and ordering,
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
- **A lost renewal** (a long pause, a partition, the store away past the TTL) is noticed by the next
  heartbeat and the job is interrupted. As with leases, the claim may already be gone and another node
  may have started the job, so the same bounded overlap applies.

## The local acknowledgement

`@HengeAcknowledgeThisRunsOnEveryNode` is for jobs that really should run everywhere: evicting a local
cache, flushing local metrics. The name is the point: the person writing it has typed the consequence.
`@Scheduled` itself is untouched; Henge only refuses it without the acknowledgement. Overlap on a
local job is an in-process flag and needs no store.

## Metrics

Per job: fired, skipped as already taken, skipped as still running, skipped as store unreachable,
interrupted at `maxRuntime`, lost its renewal.

## Build order

1. `@HengeScheduled` with the fire claim, cron only, on the in-process store.
2. The run claim, its heartbeat, `maxRuntime`, and the startup reminder.
3. The startup check for `@Scheduled`, and the acknowledgement annotation.
4. The processor check.
5. Metrics, a guide chapter, and a gotchas entry ("cron jobs that run N times").
