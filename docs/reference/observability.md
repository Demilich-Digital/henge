# Observability

Henge observes what your services can't observe about themselves. Calls go through Micrometer's
Observation API, so one instrumentation point gives timers wherever a `MeterRegistry` is connected and
spans wherever a tracer is. What Henge does on its own account is reported as plain meters. Every tag is
bounded by your interfaces and configuration, never by the traffic.

## Switching it on

- **Spring Boot:** observations switch on when the application has an `ObservationRegistry` bean
  (Actuator provides one), and the system meters when it has a `MeterRegistry` bean. Without Micrometer,
  or without a registry, nothing is observed and nothing is loaded.
- **Without Boot:** declare `ObservationServiceCallInterceptor` and `ObservationServiceDispatchObserver`
  as beans over your `ObservationRegistry`, and `MicrometerSystemMetrics` and `HengeHostedGauges` over
  your `MeterRegistry`.

## Observations

| Observation | Where | Key values |
|---|---|---|
| `henge.call` | Every call a caller in this process makes to a service, embedded or remote: the whole of it, retries and failover included. | `henge.service`, `henge.version`, `henge.method`, `henge.mode` (`embedded` or `internal-rest`); a call that throws carries the error. |
| `henge.dispatch` | Every request to `/_henge` on the serving side, rejected ones included. | `henge.status` (the HTTP status answered), `henge.exception` (the business exception's class, else `none`), `henge.service`, `henge.version`, `henge.method`. |

A request that names a service or method this process doesn't serve has `none` for those three, never
the caller's text. Compare `henge.call` on the caller with `henge.dispatch` on the host, for the same
method, to see the network. `henge.mode` is read per call, so it is right when a service is remote
because its lease was refused. The call is the current observation while it runs, so whatever it calls in
turn is its child.

## Traces across the transport

A trace that starts in the calling process continues in the serving one. The transport's `RestClient` is
given the application's `ObservationRegistry`, so each HTTP attempt is an `http.client.requests`
observation, and wherever the registry has a tracing handler (Boot with Actuator and a Micrometer Tracing
bridge) the trace context goes out in the request's headers. The host's request observation continues
it, and `henge.dispatch` nests inside that:

```
henge.call → http.client.requests (one per attempt) → the host's request → henge.dispatch
```

- **Henge brings no tracer.** Whether the registry has tracing handlers is the application's choice;
  without one, the attempts are still timed and nothing is propagated.
- **`http.client.requests` from the transport is tagged `client.name=henge`**, not with the host. An
  advertised host is a process's own address, so as processes came and went the usual host tag would mint
  a new series for each; which process a call went to is on the span.
- **The transport's `RestClient` stays its own.** It takes only the registry from the application, not
  its `RestClient.Builder`: converters, interceptors and request-factory settings for the application's
  own HTTP calls don't reach an internal call.

## System meters

| Meter | Kind | Tags | Tells you |
|---|---|---|---|
| `henge.lease.claims` | counter | `lease`, `outcome` (`granted`, `refused`) | This process asked the cluster for a lease. A refusal holds nothing, so each leased service that asks after one is a refusal too. |
| `henge.lease.renewals` | counter | `lease`, `outcome` (`renewed`, `over-capacity`, `error`) | The heartbeat that keeps a held lease. `over-capacity` is the cluster holding more than its capacity (nothing evicts yet); `error` is the store failing. |
| `henge.lease.held` | gauge | `lease` | The amount this process holds; `0` once it hands it back. |
| `henge.service.hosted` | gauge | `service`, `version`, `mode` (as configured) | `1` if this process serves the version, `0` if it's reached remotely. A `0` on an `embedded` version is a lease refusal. |
| `henge.advertisement.renewals` | counter | `service`, `version`, `outcome` (`success`, `error`) | This process keeping its advertisements alive. A run of `error` is a process about to disappear from the cluster. |
| `henge.service.advertisers` | gauge | `service`, `version` | How many processes advertise the version, as the last lookup saw it. Set whenever a call reads the advertisements (at most once per refresh interval), so it is as old as the last such call, and absent for a service only ever reached by configured url. An empty read from a store that may just have restarted isn't recorded. |
| `henge.transport.retries` | counter | `service`, `version`, `reason` (`connect`, `not-served`) | A call that provably never ran, tried again. |
| `henge.transport.giveups` | counter | `service`, `version`, `reason` | Such a call, abandoned: the attempts ran out, or retries are off. |
| `henge.transport.endpoint.failures` | counter | `service`, `version` | An advertised host failed a call, so it isn't offered again for a while. |
| `henge.rate-limit.acquisitions` | counter | `limit`, `outcome` (`granted`, `refused`) | A rate limit's answers, for its whole bucket and its subjects' together; the subject is never a tag. |
| `henge.rate-limit.subscribers` | gauge | `limit` | How many nodes draw on a rate limit, as of this node's last heartbeat: what this node's share is a fraction of while the store is away. |
| `henge.rate-limit.degraded` | counter | `limit` | Answers decided from this node's own share of the limit, because the store couldn't be reached. |
| `henge.channels.open` | gauge | `service`, `version`, `side` (`frontend`, `backend`) | Channels open on this process: the frontend counts the clients it holds a channel for, the backend those it hosts. Only channels carried over a trunk: one in a monolith has no trunk to count. |
| `henge.channels.closed` | counter | `service`, `version`, `side`, `status` | Channels that closed, by the WebSocket close code: `1000` normal, `1011` lost, `1012` retired, `1013` overloaded, `4404` refused. |
| `henge.trunks.open` | gauge | `side` | Trunks on this process: connections to backends (`frontend`), or from frontends (`backend`). Not tagged by backend, which would be unbounded. |
| `henge.scheduled.fires` | counter | `job`, `outcome` (`ran`, `taken`, `still-running`, `duplicate`, `late`, `store-unavailable`) | A fire of a `@HengeScheduled` job came due on this process. `ran`: it won the fire and started a run. `taken`: another process did. `still-running`: it won, but the last run of a job that doesn't overlap is going. `duplicate`: it won, but another process won the same fire too, so the store was failing over. `late`: too late to run, and never caught up. `store-unavailable`: the store couldn't be asked, so the fire was skipped. |
| `henge.scheduled.runs` | counter | `job`, `outcome` (`succeeded`, `failed`) | A run ended, by returning or by throwing. |
| `henge.scheduled.interruptions` | counter | `job`, `reason` (`max-runtime`, `claim-lost`) | A run was interrupted: it passed its `maxRuntime`, or the store refused its renewal because another process was given the job. |
| `henge.store.operations` | timer | `purpose` (`lease`, `advertisement`, `routing`, `rate-limit`, `scheduler`), `operation` (`put`, `remove`, `read`, `claim`, `tryAcquire`), `outcome` (`success`, `error`) | The store under all of the above: its latency and its errors, by who asked. A refused `claim` or `tryAcquire` is a `success`. |

A call that is retried is one `henge.call` observation and as many `henge.transport.retries` as it
took.
