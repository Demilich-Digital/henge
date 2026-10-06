# Modules

| Module | Contents |
|---|---|
| `henge-core` | What contracts and services compile against: `@HengeService`, `@ServiceVersion`, `@ServiceMethod`, `@AddedIn`, `@DeprecatedSince`, `@ErrorStatus`, `@ErrorLogLevel`, `@RequiresLease` / `Lease`, `@LeasedResource` / `ResourceProvider`, `@RateLimited` / `RateLimiter` / `RateLimit`, `@HengeScheduled` and `@HengeAcknowledgeThisRunsOnEveryNode` for [scheduled jobs](../guide/09-scheduled-jobs.md), the immutable collections, the `SystemEphemeralDatastore` contract and its in-process implementation, the `ServiceTransport` seam, `RemoteServiceException`, `ServiceVersionUnsupportedException`, and the [channel](../guide/08-channels.md) types `Channel`, `ChannelHandler` and `CloseStatus`. Its only Spring dependency is `spring-beans`, for the `@Qualifier` meta-annotation on `@ServiceVersion` and `@RateLimited`. |
| `henge-processor` | The compile-time half: generates `{Interface}Skeleton` classes, validates implementations against them, and enforces the [boundary rules](compile-time-checks.md), and refuses an unacknowledged `@Scheduled`. An aggregating incremental processor; depends only on `henge-core`. |
| `henge-spring` | The mechanism, with no Spring Boot: `@EnableHengeServices`, the bean wiring, the `internal-rest` transport, the dispatcher, leases, rate limiters, advertisements, routing and retries, the topology endpoint, store selection, the observability hooks, [scheduled jobs](../guide/09-scheduled-jobs.md), and [channels](../guide/08-channels.md): the trunk server and its client, and `ClientChannels`, which bridges a client websocket to a channel (it needs `spring-websocket`, optional). `HengeTransportConfiguration`, `HengeDispatcherConfiguration` and `HengeConfiguration` are plain `@Configuration` classes for [applications without Boot](../plain-spring.md). Depends on `spring-context` and `spring-web`, plus `spring-webmvc` to serve. |
| `henge-spring-boot-starter` | Boot auto-configuration over `henge-spring`: the transport, the dispatcher in servlet applications (behind `henge.server.enabled`), observations and meters when Micrometer registries are present, the `/_henge` security chain when Spring Security is, and configuration metadata for IDE completion of `henge.*`. |
| `henge-redis` | A [fast ephemeral store](../ephemeral-store.md) on Redis 7.4+ (standalone or Cluster), selected with `henge.store.type=redis`. Needs Lettuce, which Boot already has; no Spring. |

## Examples

| Module | Contents |
|---|---|
| `examples/shop-contracts` | The shop's interfaces, records and exceptions. |
| `examples/shop-services` | Their implementations: inventory (two versions, a leased database), orders, notifications (rate limited). |
| `examples/shop-app` | The Boot application, and tests that run it at every rung: `MonolithTest`, `SplitTest`, `SharedStoreTest`. |
| `examples/shop-plain-spring` | The same services under plain Spring, no Boot. |
