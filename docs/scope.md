# Scope

What Henge deliberately doesn't do, and what it plans to do next. The two are kept apart: the first is
scope excluded on purpose, the second is work deferred.

## Not in scope

- **Strong consistency in the ephemeral store.** No locks, compare-and-set, or transactions across keys.
  Anything that needs consensus belongs in a system built on it, used directly, outside Henge.
- **A job queue, or durable jobs.** A queue's record has to survive the store, which holds nothing that
  has to. A scheduled job's record is its annotation, and a job that must not be lost belongs in your own
  database. A run that is cut short is never resumed: batching is the job's own.
- **Replacing the orchestrator.** Kubernetes (or ECS, Nomad, ...) decides how many processes run; Henge
  decides, at most, what each one does.
- **Sizing or owning your resources.** A lease is bookkeeping; your provider builds the pool from it.
- **A separate port for `/_henge`.** Considered and rejected: `/_henge` was never meant to face the
  internet, so a port of its own solves nothing a network boundary doesn't.
- **Overloaded methods on a `@HengeService` interface.** Calls are dispatched by name; use
  `@ServiceMethod(name = ...)` for two methods that would share one.
- **Checked exceptions across a boundary.** They can't behave the same way remotely as embedded.

## Not built yet

- **Health- and load-aware routing, and circuit breaking.** Calls rotate over whatever is advertised, and
  a process that stops renewing drops out after its time to live, but nothing measures a host's health
  or load.
- **Retrying a call that may have run.** Only calls that provably never ran are retried; whether
  repeating anything else is safe depends on the method, and there's no way yet to declare it.
- **Giving up a lost lease.** A lease can be over-granted when the store's view is incomplete; the next
  renewal notices, and the claim lapses, but the services on it keep running on the resource. Until
  de-allocation exists, the intentional margin below the real limit is all that covers it.
- **Taking up a lease after startup.** Leases are claimed at startup only, so a refused process stays
  remote for that service until it restarts, even after the holder goes away. Switching a running
  service from remote to embedded needs the switchable proxies below. In practice: a lease holder that
  crashes and restarts before its lease lapses leaves its service with no host, and it stays that way until
  some process restarts ([deploying](guide/07-operating.md#deploying-a-new-version)).
- **Catching up a missed fire.** A fire nobody was up for is skipped, as nothing durable records that it was
  due. A misfire policy would need a record.
- **Exempting a third party's `@Scheduled`.** Spring's own classes are left alone, and any other bean with a
  `@Scheduled` method is refused unless it carries the acknowledgement, which a library's class can't.
- **Telling replicas of a whole monolith from one monolith.** A process that reaches a service remotely
  must have a store configured, but the whole jar run twice reaches nothing remotely, so it starts on the
  in-process store and shares nothing. Leases, rate limits and scheduled jobs are then per process, and
  nothing says so.
- **Other transports** (gRPC, say). `ServiceTransport` is a seam, but the serving side and the wiring are
  REST-specific too.
- **mTLS between processes.** The optional shared secret is the only built-in protection; the network is
  the boundary.
- **Asynchronous methods.** Calls are synchronous. (A connection that stays open is a [channel](guide/08-channels.md), which is built.)
- **Re-attaching a channel to another backend.** A channel closes when its backend goes, with a status, and
  the client reconnects. Resuming one needs the service's session state handed over, a much bigger scope.
- **Sending to a channel by id from another node.** Needs the store to know where a channel lives, which
  nothing consumes yet; it would be added with its first consumer.
- **Trace propagation across a trunk.**
- **Per-channel flow-control credits on a trunk.** An overflowing channel is closed instead.
- **Typed channel messages.** Frames are opaque text and binary.

## Roadmap

Roughly in order:

- **De-allocation on a lost lease**, the critical path to using leases in production: a node whose
  renewal fails stops hosting the services on the lease, closes the resource, and reaches them remotely.
  It needs switchable proxies and a child context per service, the first phase [toward
  self-management](#toward-self-management).
- **Shared-singleton detection.** Two services that inject the same stateful bean share one instance in
  a monolith, and silently get independent copies once split. A startup walk of the bean graph,
  heuristic and suppressible, would at least turn that into a loud warning.
- **Strict and isolated modes.** `strict` would send embedded calls through the same serialization as
  remote ones, so mutation bugs and non-serializable types show up on a laptop. `isolated` would go
  further, with each service in its own child Spring context in development, so shared in-memory state
  stops being shared, deterministically, with a debugger attached: the real answer to shared singletons.
- **A test slice**: boot one service's module, with its dependencies as strict-mode proxies.
- **Topology, further**: log the `service@version → mode → url` table at startup, expose it through
  Actuator, and aggregate the view across processes.
- **Metrics, further**: the dependency graph's remote edges as a gauge.
- **Dynamic lease filling and rebalancing**, the next thing: a refused process claims a lease when a slot
  frees up, and holders rebalance toward a target spread. It needs a service to be built and torn down in
  a running process, which waits on better service isolation (the per-version proxies).
- **Docker Compose is the supported small-scale deployment** (see [`examples/docker`](../examples/docker));
  keep it tested as the shop and Henge change. **Kubernetes** is the next step up: a manifest set and the
  same checks (a replica lost to a store outage, a rolling update against a missing store) run against a
  real cluster, as docs and test integrations.
- **Publishing and CI**, with the public repository.

## Toward self-management

The [ladder's](philosophy.md#every-layer-is-opt-in) top rung, in phases, each useful on its own (see the
[design doc](design/self-orchestration.md#phasing)):

1. **Switchable proxies and a child context per service**: a service that can move between embedded and
   remote while the process runs, and be torn down cleanly. Its first use is de-allocation on a lost
   lease. The stable proxy is built, and a service can be retired (drained and destroyed, its lease released); nothing triggers that yet, and the child contexts aren't built.
2. **Eviction**: a process that stops hosting a service under memory pressure or misbehavior, draining
   first.
3. A **built-in DHT** as the ephemeral store, with no separate system to run.
4. **Self-organized role selection**, preceded by a simulation of the decision loop, with slow
   dependencies and partitions injected, before any of it touches a real cluster.
