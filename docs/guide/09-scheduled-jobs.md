# 9. Scheduled jobs

Spring's `@Scheduled` runs a method on a timer, once per process. Deploy three replicas and `@Scheduled(cron
= "0 0 3 * * *")` runs three times at 3 a.m.: three emails, three archives, three charges. It is invisible in
development, where there is one process, and routinely found in production.

`@HengeScheduled` runs the method **once per fire across the cluster**, however many processes host the bean.
Spring's own `@Scheduled` is refused unless the author says, in writing, that running on every process is what
they mean.

```java
@Component
class Housekeeping {

    @HengeScheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void archiveOldOrders() {
        // runs on one process at 03:00 UTC, whichever wins
    }
}
```

A job is a method with no arguments on a singleton bean. The cron expression is Spring's, six fields (second,
minute, hour, day, month, weekday), and only cron is supported: `fixedRate` and `fixedDelay` count from each
process's own start, so they have no cluster-wide meaning. `cron`, `zone`, `name` and `maxRuntime` resolve
`${placeholders}`, so they can come from configuration.

| Attribute | Default | Meaning |
|---|---|---|
| `cron` | required | When the job is due. |
| `zone` | the JVM's zone | The zone the cron is read in. **Set it explicitly for a cluster that spans zones**: processes that read the same cron in different zones don't agree on when a fire is due, and each runs its own. |
| `name` | `<class>#<method>` | What a fire is claimed under, and the `job` tag on its meters. Only needed for two beans of one class that both declare the job, or to keep a job's identity through a rename. |
| `maxRuntime` | `1h` | The longest one run may take. See [Long jobs](#long-jobs). |
| `overlap` | `false` | Whether a fire may start while the last run is still going. See [A run that outlasts its interval](#a-run-that-outlasts-its-interval). |

## What it promises, and what it doesn't

The job's durable record is the annotation: it is in the jar, so every process knows every job and when it
is due, with nothing stored anywhere. The processes use the [ephemeral store](05-the-ephemeral-store.md) only
to decide **which one runs a given fire**. That is all the store can do. It holds no application data, and a
job that exists only in it would be lost with it, so this is not a queue. A job that must not be lost belongs in
your own database.

Because nothing records that a job ran:

- **A fire is run at most once in the normal case, but not exactly once.** A store that is failing over can
  let two processes both win a fire, as it can over-grant a lease. **The method must be safe to run twice.**
- **A fire that nobody was up for is skipped, never caught up.** If every process is down at 03:00, 03:00
  doesn't happen, as with Spring today. A node that wakes more than five minutes late for a fire skips it too.
- **If the store can't be reached when a fire is due, the fire is skipped**, and counted. A skipped run is
  better than three duplicate ones. A process started without the store waits for it, alive and not ready
  ([chapter 5](05-the-ephemeral-store.md#fault-tolerance-when-the-store-is-away)); it runs no fires meanwhile.
- **One process with no shared store is a cluster of one**, which is why everything works with no
  configuration, and also why **several processes with no shared store each run every fire**: each is
  alone in its own in-process store. Once you run more than one process, share a store
  ([chapter 5](05-the-ephemeral-store.md)).

## A run that outlasts its interval

Two claims keep the cluster in agreement, one for each thing it has to decide. The first is **the fire**: every
process computes the same instants from the cron expression, so they agree on what a fire is without talking,
and the first to claim it runs it. It is never given back, so a process whose clock is a little slow finds the
fire taken however quickly the job finished. The second is **the run**: the process that won the fire claims
the job for as long as the method executes, renewing it every 10 seconds, and gives it back when the method
ends. A process that dies stops renewing, and the job is free again 30 seconds later.

A fire that finds the job's run still going is **skipped, not queued**, on every process alike, and logged with
the process that is running it:

```
Henge scheduled job 'com.acme.Housekeeping#archiveOldOrders' skipped its fire at 2026-10-07T03:00:00Z:
the last run is still going on node 3f2c…. Skipped, not queued.
```

A queue means a backlog, catch-up and ordering, and a job that has run long enough to miss a fire shouldn't
have fires piling up behind it. If a job should start regardless, set `overlap = true`. Each run still holds a
claim of its own, named for its fire, so it is visible, renewed and cut off like any other, and a second
process that won the same fire is refused.

## Long jobs

People write jobs that run for hours or days, and that fail outright if the process they ran on dies. Henge can't
fix that: surviving the loss of a process needs durable progress, and Henge has no durable store, on purpose.
What it does is stop pretending.

**`maxRuntime` defaults to one hour.** When it passes, the run is interrupted and stops renewing its claim, so the
next fire may start. A method that ignores the interrupt keeps going, so overlap is bounded, not impossible. The
default is also what keeps a hung run, whose thread would otherwise report itself alive forever, from blocking
every future run.

**A run lives on one process, and is lost with it.** If that process dies, nothing resumes the run. The next fire
starts again from nothing, which for a daily job is tomorrow. So a job that needs longer than the default raises
`maxRuntime` itself, and Henge logs this once at startup when it does:

```
Henge scheduled job '…' may run for PT6H, longer than the default PT1H. A run lives on one node and is lost
with it: if that node dies, nothing resumes it, and the next fire starts again from nothing. Henge has no
durable store to resume from, so a long job has to be written as batches, recording its own progress in your
database and safe to repeat.
```

**Batching is yours to write.** Split the work into pieces, record in your own database which are done, and make
each piece safe to repeat, so that any run, on any process, picks up where the last one stopped:

```java
@HengeScheduled(cron = "0 0 3 * * *", maxRuntime = "4h")
public void archiveOldOrders() {
    int archived;
    do {
        // marks 500 rows archived in your own database; running it twice does no harm
        archived = orders.archiveBatch(500);
    } while (archived > 0 && !Thread.currentThread().isInterrupted());
}
```

Checking for the interrupt matters: it is how `maxRuntime`, and a lost run claim, stop the run.

## When a run loses its claim

Renewing the run's claim is a heartbeat, and it can fail two ways. If the store **refuses** the renewal, the
claim lapsed (a long pause, a partition, a wipe) and another process was given the job: the run is interrupted. As
with leases, this is noticed late, so another run may have started in the meantime. If the renewal **can't be
made** because the store is away, the run goes on, because nothing else can have been given the job without the
store either, and the first renewal to get through takes the claim back if nobody else has.

## Running on every process

Some work really is per process: evicting a local cache, flushing local metrics. Spring's `@Scheduled` is for
that, and Henge refuses it unless you say so:

```java
@Component
class LocalCache {

    @Scheduled(fixedRate = 60_000)
    @HengeAcknowledgeThisRunsOnEveryNode
    void evictExpired() { ... }
}
```

Without the acknowledgement, a bean with a `@Scheduled` method **doesn't compile** (the annotation processor
says so, naming the method and both ways out) and, in a module that doesn't run the processor, **doesn't
start**. It goes on the method, or on the class to cover every `@Scheduled` method in it. A
`SchedulingConfigurer`, which registers tasks in code where no annotation shows, is refused at startup unless
the configurer class carries it.

Spring's own classes (`org.springframework.*`, such as Spring Session's cleanup) are left alone: they schedule
what they need, and you can't annotate them. A third-party library's `@Scheduled` outside Spring is not
exempt, and there is no setting to exempt one yet.

`@Scheduled` still needs `@EnableScheduling` to run, as always. The check doesn't depend on it.

## Seeing it

Each job logs, at startup, its cron, zone and `maxRuntime`, and that it must be safe to run twice. Henge
reports, per job (the tag is `job`):

- **`henge.scheduled.fires`**, by outcome: `ran` here, `taken` by another process, `still-running`,
  `duplicate` (two processes won one fire: the store was failing over), `late`, or `store-unavailable`.
- **`henge.scheduled.runs`**, by `succeeded` or `failed`: a run that returned or threw.
- **`henge.scheduled.interruptions`**, by `max-runtime` or `claim-lost`.

Its claims are timed with the rest of the store, as `henge.store.operations` with `purpose=scheduler`. See
[Observability](../reference/observability.md#system-meters). A job that throws is logged at `ERROR` with its
fire, and counted; the next fire runs as usual.

The claims live in the store as `cron:<job>@<instant>` (the fire), `cron:<job>:running` (the run) and, for a
job that overlaps, `cron:<job>:running@<instant>`; see [the store's
contract](../ephemeral-store.md#what-henge-keeps-there).

## What you have

A cron job that runs once per fire across the cluster, that doesn't pile up on itself, that can't hang forever,
and that a mistaken `@Scheduled` can't quietly multiply, with everything that can't be promised said plainly:
runs may repeat, missed fires are skipped, and a long job's progress is yours to keep.

## Where next

- [Observability](../reference/observability.md), [Gotchas](../gotchas.md), the
  [design](../design/scheduling.md)
