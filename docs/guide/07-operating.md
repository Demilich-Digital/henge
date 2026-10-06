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
chain for exactly `POST /_henge/**`, ordered ahead of yours and alongside it, never instead of it.
Nothing else you serve is affected.

- With a secret, a valid `Henge-Internal-Secret` header becomes an authenticated principal,
  `henge-internal-service` with `ROLE_HENGE_SERVICE`, visible to method security and auditing. Anything
  else is `403`.
- Without one, the chain permits the path: the network is the boundary.

Sessions are stateless and CSRF is off for this path, which is sound: CSRF defends cookie and session
authentication, and this path has neither. To apply a policy of your own (JWT, mTLS, ...), define a
`SecurityFilterChain` bean named `hengeSecurityFilterChain`, and Henge's backs off.

## Failures, in the logs

A caller gets an exception's type and message; the process that ran the method logs the stack trace:
`ERROR` for a failure answered with a `5xx`, `DEBUG` for a `4xx`. `@ErrorLogLevel` on an exception sets
it explicitly, `NONE` included.

## When the store goes away

The [ephemeral store](05-the-ephemeral-store.md) is on the critical path, so Henge treats an unreachable
one as an outage to ride out, not a mode to run in. A restart or a replacement is the expected case, and
it is brief.

- **What is already known keeps working.** A caller keeps routing to the hosts it last read, however
  stale; a lease and its resource are kept, and re-claimed on the first heartbeat after the store
  returns; nothing is evicted. Nothing new is learned, and this process's own advertisement lapses, so
  traffic drains away from a node that can't renew it.
- **Everything else fails fast.** After one failure every use of the store in the process fails at once
  with `StoreUnavailableException`, for a backoff that doubles up to a cap (`henge.store.backoff.*`),
  with one call at a time let through to find out. Nobody waits out a connect timeout per call.
- **At the edge it is a `503`.** Over `internal-rest` the exception is a `503`, and a servlet application
  on the starter answers it with a `503` too. Handle `StoreUnavailableException` yourself to answer
  differently. Henge never retries a `503`; your clients may, and that is their choice.
- **Logs say it once.** The start and the end of an outage are each logged once, not per call.

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

Only this first contact is gated. A node that has been ready stays ready through a later outage, as above.
Without Actuator there are no health endpoints, so the node reports nothing and every request is `503`.
In plain Spring, inject the `HengeBootGate` bean and ask its `isReady()` from your own health check.

If the store stays away, callers' routes go stale and eventually the calls fail on their own: Henge adds
no deadline of its own.

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
refusals, and the latency and errors of the store under all of them. A `0` on
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

## Where next

- [Philosophy](../philosophy.md): why Henge is built this way, and where it's going
- [Gotchas](../gotchas.md): every trap on the way from one process to many, and how each is handled
- [Configuration](../reference/configuration.md), [Wire protocol](../reference/wire-protocol.md),
  [Internals](../internals.md)
