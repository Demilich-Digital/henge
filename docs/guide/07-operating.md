# 7. Operating

What to know before a Henge application carries real traffic, at whichever rung you've stopped.

## Security: the network is the boundary

`/_henge` is how processes call each other, and it serves every service a process hosts, on the same
port as your public API. Henge's security model is **network isolation**: `/_henge` belongs on a private
network (a VPC, a cluster's pod network, a service mesh), reachable only by your own processes. Running
with no secret is supported, and is the expected configuration there. The process logs one `INFO` line
saying so.

Where the network isn't enough, set a shared secret on every process:

```yaml
henge:
  transport:
    secret: ${HENGE_SECRET}
```

Every internal call then carries it as the `Henge-Internal-Secret` header, and the dispatcher requires
it: a constant-time comparison, before the request body is read, `403` otherwise.

There's no separate port for `/_henge`, deliberately: it was never meant to face the internet, so a port
of its own adds nothing a network boundary doesn't already give you. If a process also serves a public
API, put that behind your ingress as usual, and don't route `/_henge/**` through it.

### With Spring Security

Add Spring Security and `/_henge` keeps working, with nothing to configure. Spring Security's defaults
would demand a session and a CSRF token on every `POST`, so the Boot starter adds a dedicated filter
chain for exactly `POST /_henge/**` (and the `GET` that opens the [channels](08-channels.md) trunk, `/_henge/_trunk`), ordered ahead of yours and alongside it, never instead of it.
Nothing else you serve is affected.

- With a secret, a valid `Henge-Internal-Secret` header becomes an authenticated principal,
  `henge-internal-service` with `ROLE_HENGE_SERVICE`, visible to method security and auditing. Anything
  else is `403`.
- Without one, the chain permits the path: the network is the boundary.

Sessions are stateless and CSRF is off for this path, which is sound: CSRF defends cookie and session
authentication, and this path has neither. To apply a policy of your own (JWT, mTLS, ...), define a
`SecurityFilterChain` bean named `hengeSecurityFilterChain`, and Henge's backs off.

## How many instances

Run at least **three** of anything that isn't a development setup. This is advice from the failure modes,
not a quorum requirement: Henge works with one or two.

With one instance nothing is distributed, so nothing can disagree. With two, a disagreement is a tie: when
one node says a lease is held and the other says it isn't, you can't tell which is wrong. With three, the
odd one out is visible, and so are the bugs that only exist between nodes: a limit that holds per process,
a job that fires twice, a service two nodes both think they host, a node that has dropped out of the
cluster's view while still serving. Those are the [gotchas](../gotchas.md) Henge exists to catch, and a
deployment too small to show them is not testing them.

It also lets you lose one and still have two to compare, and roll a deploy one node at a time without
dropping to a single survivor.

## Failures, in the logs

A caller gets an exception's type and message; the process that ran the method logs the stack trace:
`ERROR` for a failure answered with a `5xx`, `DEBUG` for a `4xx`. `@ErrorLogLevel` on an exception sets
it explicitly, `NONE` included.

## When the store goes away

A restart or a replacement of the shared store is expected, and Henge rides it out: callers keep the
hosts they last read, leases and their resources stay where they are, rate limits answer from a local
share, and what can't be answered is a `503` (`StoreUnavailableException`) that the starter also
answers at your own edge. The design, and what each part does, is in
[Fault tolerance](05-the-ephemeral-store.md#fault-tolerance-when-the-store-is-away). What is yours to
operate is below.

- **Watch it.** `henge.store.operations` with `outcome=error`, and `henge.rate-limit.degraded`, say
  that the store is away. The start and the end of an outage are each logged once.
- **Tune the probing.** After one failure every use of the store fails at once, for a backoff that
  doubles up to a cap (`henge.store.backoff.*`), with one call at a time let through to find out.
- **Decide what a `503` means to your clients.** Henge never retries it; yours may.

### Starting without the store

A process started while the store is away does not fail to start, because restarting it wouldn't bring
the store back, and an orchestrator that sees a node crash will only replace it, over and over. It starts
**alive, listening, and not ready**:

- Everything but the health endpoints answers `503` with `Retry-After: 1` and `Connection: close`
  (the starter's `HengeNotReadyFilter`), including `/_henge`.
- With Spring Boot Actuator, `/actuator/health/liveness` stays `200`, and readiness stays
  `REFUSING_TRAFFIC` (so `/actuator/health/readiness` is `503`) until the process has reached the store.
  Kubernetes then leaves the pod alone but sends it nothing, and with a headless Service takes it out of the
  DNS answers, so clients that dial the cluster by name land on the older nodes. Use the probes
  Boot provides (`management.endpoint.health.probes.enabled=true`, on by default in Kubernetes):
  liveness for `livenessProbe`, readiness for `readinessProbe`.
- A service that needs a lease can't say whether this process hosts it, so its calls fail with
  `StoreUnavailableException` until the store answers and it is decided. Rate limiters refuse until they
  have counted their nodes. Nothing is advertised yet.
- It retries every half second (the [backoff](../reference/configuration.md#the-ephemeral-store) permitting).
  When the store answers, the leases are claimed, the limiters join, the node advertises, and readiness
  turns `UP`, with no restart.

Only this first contact is gated. A node that has been ready stays ready through a later outage.
Without Actuator there are no health endpoints, so the node reports nothing and every request is `503`.
In plain Spring, inject the `HengeBootGate` bean and ask its `isReady()` from your own health check.

## Metrics and traces

With Spring Boot Actuator (or any `ObservationRegistry` bean), Henge observes every call:

- **`henge.call`** on the caller, embedded or remote, retries included, tagged with the service, version,
  method and mode
- **`henge.dispatch`** on the host, tagged with the HTTP status answered and the exception, if any

Comparing the two for one method shows what the network costs. With a Micrometer Tracing bridge, a trace
continues across processes: the caller's `henge.call`, one HTTP client span per attempt, the host's
request, and its `henge.dispatch`, all one trace.

Henge also reports what it does on its own account as plain meters: lease claims and renewals, which
services each process hosts, advertisement renewals, retries and give-ups, rate-limit grants and
refusals, scheduled jobs' fires and runs, and the latency and errors of the store under all of them. A `0` on
`henge.service.hosted` for a service configured `embedded` is a lease refusal; a run of
`henge.advertisement.renewals` errors is a process about to drop out of the cluster.

Every tag is bounded by your interfaces and configuration, never by traffic. The full list is in
[Observability](../reference/observability.md).

## Seeing the topology

Configuration decides the topology, so the result should be visible. With `henge.topology.enabled=true`,
a process serves it at `GET /_henge/topology` as JSON, and draws it at `/_henge/topology/ui`:

```bash
java -jar examples/shop-app/build/libs/shop-app-0.1.0-SNAPSHOT.jar --henge.topology.enabled=true
open http://localhost:8080/_henge/topology/ui
```

For each service version: how it was configured and why (explicit, `henge.serve`, or the default), its
state here (`hosted`, `remote`, or `lease-refused`), the route a call would take, and who advertises it.
Then which of your beans inject which services, with the edges that cross the network marked, and each
lease's capacity and holders, read fresh from the store.

It is off by default, since it lists every service's host. When on, it follows the same rules as
`/_henge`: a private network, and the secret where one is set. The page holds no data itself, asks for
the secret, and sends it with its requests. It shows one process's view, plus what the store says about
the others.

## A checklist

- [ ] `/_henge` is reachable only from your own processes, or a secret is set.
- [ ] Every process that shares a store configures the same leases and rate limits, the same way.
- [ ] Lease capacities sit below the real limits, as a margin.
- [ ] Services that keep state in memory are hosted by one process only.
- [ ] On Kubernetes, `livenessProbe` and `readinessProbe` use the actuator's liveness and readiness groups, so a node that can't reach the store is left alone but sent nothing.
- [ ] `henge.advertise.url` (with a shared store) is an address other processes can actually reach.
- [ ] More than one process means a shared store, or every process runs every `@HengeScheduled` fire.
- [ ] Scheduled jobs are safe to run twice, and a job that runs longer than an hour is written as batches.

## Where next

- [Channels](08-channels.md): long-lived connections to clients
- [Scheduled jobs](09-scheduled-jobs.md): cron jobs that run once across the cluster
- [Philosophy](../philosophy.md): why Henge is built this way, and where it's going
- [Gotchas](../gotchas.md): every trap on the way from one process to many, and how each is handled
- [Configuration](../reference/configuration.md), [Wire protocol](../reference/wire-protocol.md),
  [Internals](../internals.md)
