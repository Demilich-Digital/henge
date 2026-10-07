# Configuration

Everything is read from Spring's `Environment`, so command-line flags, `application.yml`, environment
variables and `SPRING_APPLICATION_JSON` all work. A `henge.services.*` property that names no discovered
service, or isn't one of the keys below (say `.mdoe`), fails startup instead of being ignored.

## Topology

| Property | Default | Meaning |
|---|---|---|
| `henge.serve` | unset | `name[@version]` entries, comma-separated or as a YAML list, naming what this process hosts; everything else discovered defaults to `internal-rest`. Names that match no `@HengeService` fail startup. |
| `henge.services.<name>.mode` | `embedded` | `embedded` or `internal-rest`, for every version of the service unless overridden below. |
| `henge.services.<name>.url` | — | Base URL of the process hosting the service, used when its mode is `internal-rest`. |
| `henge.services.<name>.versions.<n>.mode` / `.url` | the service's | Per-version override; `<n>` is an integer. |
| `henge.remote-url-template` | unset | URL template for any `internal-rest` service without an explicit `url`: `{service}` and `{version}` are substituted, and any other placeholder fails startup. Can't be combined with leases. |
| `henge.recent-versions` | `2` | How many of the most recent versions of each service this process runs; older implementations stay on the classpath, unrun. A version that `defaultVersion`, `henge.serve` or `henge.services` names outside the window fails startup. A positive integer. |

**Mode**, per service version: explicit `mode` → else `embedded` if `henge.serve` names it or is unset →
else `internal-rest`. Naming a service in `henge.serve` while setting its mode to `internal-rest` fails
startup.

**Url**, for an `internal-rest` service version: explicit `url` → else `henge.remote-url-template` → else
whoever advertises it on the store → else the call fails, saying so.

## Transport

| Property | Default | Meaning |
|---|---|---|
| `henge.transport.connect-timeout` | `2s` | A bare number is milliseconds; `500ms`, `2s` and ISO-8601 (`PT2S`) work too. `0` means no timeout. |
| `henge.transport.read-timeout` | `10s` | Same format. |
| `henge.transport.retry.max-attempts` | `3` | Calls made at most per invocation, the first included; `1` turns retries off. |
| `henge.transport.retry.backoff` | `50ms` | Wait before each retry; same format, `0` retries at once. |
| `henge.transport.retry.on` | `connect,not-served` | Which failures are retried: `connect` (no connection was made), `not-served` (any `404`), or both. |
| `henge.transport.secret` | unset | Shared secret sent as `Henge-Internal-Secret` and required by the dispatcher; with Spring Security it becomes an authentication. |

## Serving

| Property | Default | Meaning |
|---|---|---|
| `henge.server.enabled` | `true` | Boot only: whether this process serves `/_henge/**` at all. A non-web application never does. |
| `henge.server.path-prefix` | `/_henge` | Path prefix of the dispatch endpoint, on both the serving and the calling side. Must start with `/` and not end with one. |
| `henge.topology.enabled` | `false` | Serve the topology as JSON at `<path-prefix>/topology` and as a page at `<path-prefix>/topology/ui`. Needs `henge.server.enabled` in Boot. |

## Channels

How [channels](../guide/08-channels.md) are bounded. The trunk they travel over is served at
`<henge.server.path-prefix>/_trunk`, with `henge.transport.secret` and `henge.transport.connect-timeout`
as for calls.

| Property | Default | Meaning |
|---|---|---|
| `henge.channels.queue-size` | `256` | Frames a channel may have queued in each direction before it is closed `1013`. A positive integer. |
| `henge.channels.max-frame-bytes` | `65536` | The largest text or binary frame, and open. A larger frame closes the channel `1009`. A positive integer. |
| `henge.channels.trunk.ping-interval` | `15s` | How often a trunk is pinged, by each end; two intervals of silence from the other end drop it, closing its channels (`1011` at the frontend, `1001` at the backend). Same duration format as the timeouts; positive. |
| `henge.channels.trunk.idle-timeout` | `60s` | How long a trunk with no channels is kept. Same format; positive. |

## The ephemeral store

| Property | Default | Meaning |
|---|---|---|
| `henge.store.type` | `in-process` | Which store holds this process's shared state: `in-process`, or the type of an adapter on the classpath (`redis`, from `henge-redis`). Left unset, a process that reaches any service over the network fails startup, since the default is private to one process; naming `in-process` says its processes share nothing. Setting it while also defining a `SystemEphemeralDatastore` bean fails startup. A store that is configured but can't be reached doesn't: the process starts not ready (see [operating](../guide/07-operating.md#starting-without-the-store)). |
| `henge.store.redis.uri` | — | For `type=redis`: a Lettuce URI, e.g. `redis://host:6379/0`; `rediss://` for TLS, `redis://:password@host` for a password, options as query parameters (`?timeout=5s`). Commands and connection attempts time out after `500ms` unless the URI sets `timeout`. Lettuce's own default is a minute, far too long for a store on the critical path: a store that drops packets would hold every caller for all of it. |
| `henge.store.redis.cluster-nodes` | — | For `type=redis` on Redis Cluster, instead of `uri`: comma-separated seed URIs. The other nodes are discovered, and the topology refreshed on failover or resharding. Exactly one of `uri` and `cluster-nodes` is required. |
| `henge.store.backoff.initial` | `500ms` | After a call to the store fails, the wait before the first retry; every use of the store in the process fails at once (`StoreUnavailableException`, a `503`) until it has passed. It doubles with each further failure. `0` turns the backoff off. |
| `henge.store.backoff.max` | `10s` | The longest the store is left alone after failing. What was last read about who hosts a service keeps being served meanwhile. |
| `henge.advertise.url` | unset | Base URL (`http://host:port`) at which other processes reach this one's `/_henge`, published in its advertisements. Unset: advertisements say what this process hosts, but give no address. |

## Leases and rate limits

| Property | Default | Meaning |
|---|---|---|
| `henge.leases.<lease>.capacity` | — | Cluster-wide capacity of a resource that services claim shares of with `@RequiresLease`. A positive integer; required for every lease in use. |
| `henge.leases.<lease>.amount` | — | How much one process claims, however many of its services need the lease. A positive integer, at most the capacity; required for every lease in use. |
| `henge.rate-limits.<name>.permits` / `.period` | — | A cluster-wide rate limit, injected with `@RateLimited("<name>")`: `permits` (a positive integer) drain every `period` (a duration, 1 ms to 1 h). Both required. |
| `henge.rate-limits.<name>.capacity` | `permits` | The most the bucket holds: the burst a quiet limit lets through at once. |

## Scheduled jobs

There are no `henge.*` properties for jobs: a job's settings are on its `@HengeScheduled` annotation
(`cron`, `zone`, `name`, `maxRuntime`, `overlap`), and the first four resolve `${placeholders}`, so they can
come from any property. A job is claimed in the [ephemeral store](#the-ephemeral-store), so several
processes need a shared one. See [scheduled jobs](../guide/09-scheduled-jobs.md).

## Environment variables

Every key is a plain dotted key, read from Spring's `Environment` with no Boot-specific binding, so an
orchestrator that only offers environment variables can configure everything. Spring Framework translates
`HENGE_SERVICES_INVENTORY_SERVICE_MODE` to `henge.services.inventory-service.mode` at lookup, and
Henge's version discovery reads both forms, so `HENGE_SERVICES_<NAME>_VERSIONS_<N>_URL` (and `_MODE`)
declare a version's url or mode with no local implementation and no mention in `henge.serve`.

For nested configuration as one value, Boot's `SPRING_APPLICATION_JSON` works too:

```bash
SPRING_APPLICATION_JSON='{"henge":{"services":{"inventory-service":{"versions":{"2":{"mode":"internal-rest","url":"http://inventory-v2:8080"}}}}}}'
```
