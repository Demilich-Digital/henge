# Design: channels

Long-lived, bidirectional connections between a client and a service, through a frontend that holds
thousands of them and a backend that holds almost none. The first use is **websockets at the edge**: every
node runs the frontend, so a DNS dial of the cluster always reaches one, and the frontends are light. The
services behind them are fewer and resource-bound, and must not carry a connection per end user.

**Status.** Phase 1 (the contract and the embedded path) is built; the trunk is not. This is the design to implement; the guide chapter comes with the code.
[Decisions](#decisions) were made in discussion and are final unless something here proves unworkable;
[open choices](#choices-left-to-the-implementer) come with a recommendation.

## The problem

Henge's only call today is `POST /_henge/{service}/{version}/{method}`: one request, one response, no
connection kept (`docs/scope.md` lists streaming as not built). A frontend that wants to offer a
websocket to a browser has nothing to hand it to. If each client socket were a socket to a backend, the
backend would carry one connection per end user, which is the load the frontends exist to absorb.

So the shape is: **many client sockets on a frontend, multiplexed over one long-lived connection (a
*trunk*) per backend node it talks to**. A backend sees one connection per frontend, however many users
there are.

```
 browsers ──ws──►  frontend (every node)  ═══ trunk (one websocket) ═══►  backend (few nodes)
 browsers ──ws──►     holds N client sockets,                                sees one connection
 browsers ──ws──►     one trunk per backend node                             per frontend, with
                                                                             N logical channels on it
```

## Decisions

| Question | Chosen | Rejected, and why |
|---|---|---|
| Topology | **Multiplexed trunk** per (frontend, backend node) | Connection per channel: backends carry O(users) sockets, which defeats the goal. |
| Backend API | **Handler interface in the service contract**: a `@HengeService` method that returns a `ChannelHandler` | A separate `@HengeChannelService` family: a second annotation set to version and validate. Raw frames with no contract: no versioning, no compile-time checks. |
| Trunk wire | **WebSocket** (spring-websocket on the serving side) | Raw TCP/Netty: a new server stack and auth on every node. HTTP/2 streaming: awkward in the servlet stack, and `RestClient` has no HTTP/2. |
| Backend dies or retires | **Close the channel with a status; the client reconnects** | Transparent re-attach: needs session state handed to the application. A much bigger scope. |
| Frame payload | **Opaque text/binary.** Henge never parses a frame | Typed messages: the frontend would parse every frame and Henge would dictate the client's protocol. Can be layered on later. |
| Placement in the store | **Not in v1.** Nothing consumes it | A per-node or per-channel key: code kept for a hypothetical consumer (push by channel id). Add it with its first consumer, as a per-node batch key, never per channel. |
| Backpressure | **Bounded queues; a full queue closes that channel**, status `1013` | Blocking the sender: one slow channel stalls the trunk. Dropping silently: the application can't tell. |

The trunk is the source of truth for where a channel lives. That is what makes the rest of the design
independent of the store.

## Principles it inherits

These come from the store work already done (see `docs/guide/05-the-ephemeral-store.md#fault-tolerance-when-the-store-is-away`
and `docs/philosophy.md`), and the channel code must follow them:

- **An open channel never needs the store.** The store is read to *choose* a backend for a new channel,
  never while one is open. A store outage, restart or wipe changes nothing for open channels, and open
  channels are never closed because of the store.
- **Prefer what is already connected.** A frontend that has a live trunk to a node that served the
  service before can open a channel on it with no lookup (below), so a store outage doesn't stop it
  taking new channels there either.
- **Fail honestly.** A channel that can't be opened because no backend is known, and the store can't be
  asked, fails with `StoreUnavailableException` (a `503`), not a made-up answer.
- **A node that hasn't reached the store is not ready.** `HengeBootGate` and `HengeNotReadyFilter` already
  answer every HTTP request, including a websocket handshake (an HTTP GET), with `503` until ready. Nothing
  channel-specific is needed for that.
- **Bounded everything.** A frontend is light: no unbounded queue, no thread per client.

## The contract

A channel is opened by an ordinary service method. It is recognized by its **return type**, no extra
annotation:

```java
@HengeService
public interface OrderService {

    /** Opens a feed of status changes for one order. */
    ChannelHandler watch(String orderId, Channel toClient);
}
```

`henge-core` gains three types:

```java
/** The backend's handle on the client at the other end: send to it, or close it. */
public interface Channel {
    String id();
    boolean isOpen();
    void sendText(String text);
    void sendBinary(byte[] data);
    void close(CloseStatus status);
}

/** What the backend gives back: called for what the client sends and for the channel closing. */
public interface ChannelHandler {
    default void onText(String text) { }
    default void onBinary(byte[] data) { }
    default void onClose(CloseStatus status) { }
}

public record CloseStatus(int code, String reason) { /* constants below */ }
```

Opening is the call itself, so there is no `onOpen`: `watch(...)` runs, may start sending through
`toClient`, and returns the handler. Throwing refuses the channel.

- **Arguments.** Every parameter but the final `Channel` is an ordinary boundary type (immutable, as
  checked today) and travels in the open frame. The `Channel` is not an argument: it is how the backend
  reaches the client, supplied by Henge on whichever side the implementation runs.
- **Uniform at the call site.** `ChannelHandler h = orders.watch(id, clientChannel)` is the same code
  embedded (a method call, the handler comes straight back), reached over a trunk (the returned handler is
  a proxy that forwards frames), or in a monolith. This is the property to preserve above all.
- **Interceptors.** The open is a normal call, so `ServiceCallInterceptor`s (tracing, observation) wrap
  it on the caller's side, for free, and `/_henge` skips them as it does today.
- **Versions.** `@AddedIn`, `@DeprecatedSince` and the generated skeleton apply unchanged. A version that
  doesn't support the method refuses the channel (status `4501`).
- **Not an HTTP method.** A channel method must never be callable through `POST /_henge`:
  `HengeServiceDescriptor` marks it, and `HengeDispatcherController` answers `400` for it instead of
  trying to serialize a handler.

### Close statuses

WebSocket close codes, so clients and browsers understand them. Reconnecting is always the client's
choice, and always correct.

| Code | Meaning |
|---|---|
| `1000` | Normal close, by either end. |
| `1011` | Unexpected failure: the backend threw, or the trunk was lost (the backend died or the network broke). |
| `1012` | Service restart: the backend is **retiring** this service. Reconnect, and you land elsewhere. |
| `1013` | Try again later: a queue overflowed (**overloaded**), or no backend could be found or asked (`StoreUnavailableException`). |
| `4000 + status` | The open call threw an exception annotated `@ErrorStatus(status)`: `@ErrorStatus(404)` closes with `4404`, a refused version with `4501`. Unannotated exceptions close `1011`. |

The exception's message is the close reason (truncated to the 123 bytes a close frame allows).
`@ErrorStatus(404)` keeps its existing meaning: the call did nothing, so it may be retried on another
node. For a channel open that means the frontend may try the next advertised host once before giving up.

## The trunk

### Wire format

One WebSocket per (frontend, backend node), at `{henge.server.path-prefix}/_trunk` (`/_henge/_trunk`),
carrying **binary** frames only. Each frame is:

```
[ type : 1 byte ] [ channel : 8 bytes, big-endian ] [ payload : the rest ]
```

| Type | Direction | Payload |
|---|---|---|
| `OPEN` | frontend → backend | UTF-8 JSON `{"service": ..., "version": ..., "method": ..., "args": [...]}`. The channel id is chosen by the frontend, unique within the trunk. |
| `TEXT` | both | UTF-8 text. |
| `BINARY` | both | The bytes. |
| `CLOSE` | both | 2 bytes of status code, then the UTF-8 reason. Closing a channel is idempotent. |

A binary header keeps the hot path (`TEXT`/`BINARY`) free of any parsing on the frontend, which only
forwards the payload. `args` is written and read exactly as `/_henge` does it: positional, typed by the
method's generic parameter types, streamed through a `JsonParser` (never a `JsonNode`, which loses a
`BigDecimal`'s precision), with `HengeTransportSupport.objectMapper()`. That logic is private to
`HengeDispatcherController.readArguments` today and needs lifting into something both share.

WebSocket pings keep the trunk alive and detect a dead one. The channel id the application sees
(`Channel.id()`) is the trunk session id plus the number, so it is unique on the backend.

### Backend: serving a trunk

A `BinaryWebSocketHandler` (spring-websocket) registered at the trunk path, with:

- A **handshake interceptor** that checks `Henge-Internal-Secret` with `SharedSecret` (the check
  `HengeDispatcherController` uses; today the Spring Security integration covers only `POST` and the topology
  `GET`s), so a wrong secret is `403` before the upgrade.
- **Spring Security**: `HengeSecurityAutoConfiguration`'s chain matches only `POST {prefix}/**`. The trunk
  handshake is a `GET`, so the chain must also match `GET {prefix}/_trunk`, exactly as it already matches
  the topology GETs, or an application with Spring Security rejects every handshake. Easy to miss.
- On `OPEN`: look the method up in the `HengeServiceRegistry` (the same pre-built table, so nothing
  outside it is reachable), build the `Channel`, call the implementation through the `ServiceBinding`, and
  keep the returned handler under its channel id. A throw becomes a `CLOSE`.
- **No head-of-line blocking.** Inbound frames for a channel are queued to a per-channel mailbox, drained by
  a virtual thread (the toolchain is Java 21), so a slow handler stalls only its own channel and delivery
  within a channel stays ordered. A full mailbox closes that channel with `1013`.
- Outbound (`Channel.sendText` from application threads): a bounded per-channel queue drained onto the
  trunk by one writer, with fair turns across channels. A full queue closes that channel with `1013`. One
  send in flight at a time on the session (a `ConcurrentWebSocketSessionDecorator`, or one writer).

### Frontend: opening channels

A `TrunkPool` holds the trunks, keyed by backend base URL, opened lazily and closed after an idle period
with no channels. For a new channel it:

1. Resolves a backend the way an RPC does. `InternalRestTransport.resolveEndpoint` does exactly this today:
   an explicit `henge.services.<name>.url`, else `remote-url-template`, else
   `AdvertisedEndpoints.next(service, version)`. Reuse it, and `failed(...)` on a connect failure, so
   failover and the routing table behave the same.
2. If that lookup throws `StoreUnavailableException` (a cold routing table, store away), falls back to a
   **live trunk that has already opened this service version** (the pool remembers what each trunk has
   served). Only with none does it fail, `1013`.
3. Opens or reuses the trunk, allocates a channel id, sends `OPEN`, and returns a proxy `ChannelHandler`
   whose `onText`/`onBinary`/`onClose` become frames. Frames arriving for the channel go to the `Channel`
   argument the caller passed.

If the backend answers `OPEN` with `CLOSE 4404` (it doesn't host the version) or the trunk can't connect,
one attempt on the next advertised host is allowed, as for an RPC. Nothing else is retried: a channel that
ran is never silently re-opened.

The client of the trunk is the JDK's `java.net.http.WebSocket` (see choices): no extra dependency, and
its one-send-at-a-time model fits a single writer per trunk. A lost trunk (ping timeout, error, close)
closes **every** channel on it with `1011` (the reason says "backend lost") and is dropped from the pool;
the next open connects afresh. Channels are never re-opened for the client.

### The client-facing bridge

Applications expose a websocket to browsers with spring-websocket as usual, and Henge supplies the glue so
the handler is a few lines:

```java
registry.addHandler(
    ClientChannels.bridge((session, toClient) -> orders.watch(orderIdFrom(session), toClient)),
    "/ws/orders/{id}");
```

`ClientChannels.bridge` returns a `WebSocketHandler` that, for each client connection, builds the `Channel`
(sends go to the client session through a `ConcurrentWebSocketSessionDecorator` with a send-time limit and
buffer limit, and a client that falls behind is closed `1013`), calls the supplied function to open the
channel, and pipes client frames to the returned handler. A client frame or close reaches the handler; a
close of the channel from either side closes the client socket with the same status. An exception from
the open maps as in the table above. Authenticating the end user is the application's own concern, on its
own endpoint; whatever identity the backend needs is passed as ordinary arguments of the open.

## Lifecycle

- **A channel service retires** (`HengeServiceRegistry.retire`, `HengeServiceBindingFactoryBean.retire`):
  today it withdraws the advertisement, waits `grace`, switches the binding to the transport, drains
  in-flight *calls* (`ServiceBinding.Local.enter/exit/close`), destroys the implementation and releases
  leases. For channels, after the calls drain and before the implementation is destroyed, **open channels
  are closed with `1012`** and their handlers' `onClose` run (bounded by `drainTimeout`). An open channel
  is not a call in flight, so counting it in `Local.inFlight` would hold the drain open forever; keep a
  separate channel registry per binding, which also covers channels opened by an embedded caller (the
  monolith), so a retired service closes all of them the same way.
- **A backend node dies.** The frontend's trunk drops; its channels close `1011`.
- **The whole backend is replaced** (rolling deploy): channels close `1012` one node at a time as each
  retires, clients reconnect, and land on the new nodes.
- **The store goes away or is wiped.** Nothing happens to open channels. See
  [Principles](#principles-it-inherits).
- **A frontend shuts down.** Its trunks close; the backends see the channels close `1001`/`1011` and run
  `onClose`.

## Configuration

Properties under `henge.channels.*`, to be added to `docs/reference/configuration.md` and the starter's
`spring-configuration-metadata.json` (a test, `ConfigurationMetadataTest`, lists them). Starting defaults;
tune from measurement:

| Property | Default | Meaning |
|---|---|---|
| `henge.channels.queue-size` | `256` | Frames a channel may have queued, each direction, before it is closed `1013`. |
| `henge.channels.max-frame-bytes` | `65536` | The largest text or binary frame accepted. Larger closes the channel `1009`. |
| `henge.channels.trunk.ping-interval` | `15s` | How often a trunk is pinged; two missed pongs drop it. |
| `henge.channels.trunk.idle-timeout` | `60s` | How long a trunk with no channels is kept. |

The trunk connect timeout is the existing `henge.transport.connect-timeout`, and the secret is the
existing `henge.transport.secret`.

## Observability

Default no-ops on `SystemMetrics`, implemented in `MicrometerSystemMetrics`, as for the metrics added so far:
`henge.channels.open` (gauge, tags `service`, `version`, `side` = `frontend`/`backend`),
`henge.channels.closed` (counter, tags `service`, `version`, `status`), and `henge.trunks.open`
(gauge, backend URL excluded from tags: it is unbounded). Document them in
`docs/reference/observability.md`. Trace context across the trunk is out of scope for v1.

## Compile-time checks

`henge-processor` (`ServiceVersionProcessor`) must learn the channel method, because today it rejects any
parameter or return type that isn't provably immutable (`ChannelHandler` and `Channel` are not):

- A method is a channel method if it returns `ChannelHandler`.
- It must have exactly one `Channel` parameter, and it must be the last.
- Every other parameter is a boundary type as usual. No checked exceptions, as usual.
- `Channel` or `ChannelHandler` anywhere else in a `@HengeService` signature is an error, with a message
  saying how to declare a channel.
- The skeleton generation (`generateSkeletonIfNeeded`) needs nothing: its throwing override simply returns
  `ChannelHandler`.

Document it in `docs/reference/compile-time-checks.md`.

## Wiring

Following the repo's own structure (`docs/reference/modules.md`):

- `henge-core`: `Channel`, `ChannelHandler`, `CloseStatus`. No Spring.
- `henge-processor`: the rules above.
- `henge-spring` (package `digital.demilich.henge.spring`, so it can reach the package-private
  `ServiceBinding`, `HengeServiceRegistry`, `AdvertisedEndpoints`, `HengeBootGate`): the trunk server, the
  `TrunkPool`, the channel registry, `ClientChannels`. `spring-websocket` is a `compileOnly` dependency,
  and the channel beans are registered only when it is on the classpath and a declared service has a
  channel method, so a node that uses no channels pays nothing. `ServiceBinding.Remote` is given an optional
  channel opener and uses it instead of `ServiceTransport.invoke` when the method returns `ChannelHandler`.
  `ServiceTransport` stays as it is: it is documented as not an extension point.
- `henge-spring-boot-starter`: the Spring Security chain for the trunk `GET`, metadata, and
  `spring-boot-starter-websocket` guidance in the docs.

## Choices left to the implementer

| Choice | Recommendation | Alternative |
|---|---|---|
| Trunk client | **JDK `java.net.http.WebSocket`.** No dependency, async, one send at a time matches one writer per trunk. | Spring's `StandardWebSocketClient`: more integration (interceptors, decorators) but pulls in the JSR-356 client implementation on every frontend. |
| Marking a channel method | **By return type**, no annotation. One fewer thing to forget. | A `@ChannelMethod` annotation: more explicit, redundant with the return type. |
| Channel id | Trunk session id + number. | A UUID per channel: nothing needs global uniqueness yet. |
| Where the bridge lives | `henge-spring`, behind the `spring-websocket` condition. | A new `henge-channels` module: cleaner dependency story, one more module to publish; revisit if `henge-spring` grows heavy. |

## Not in scope

Re-attaching or resuming a channel on another backend, push to a channel by id from another node (the
store placement entry exists for that, and is added with it), typed messages, per-channel flow-control
credits on the trunk (overflow closes the channel instead), trace propagation across the trunk, and
authenticating end users (the application's own endpoint).

## Phasing

Commit each phase on its own, with tests, in the repo's style (a short imperative subject, a body that
says why, and the `Co-Authored-By` trailer the session specifies).

1. **Contract and the embedded path, no network.** `henge-core` types, the processor rules, the descriptor
   marking and the dispatcher's `400`, the per-binding channel registry, `ClientChannels.bridge` over a
   local call. A monolith test: a client websocket reaches a service's handler and back.
2. **The trunk.** Server handler and handshake (secret, Spring Security `GET`), frame codec, `TrunkPool`,
   the `ServiceBinding.Remote` channel opener, mailbox/queue/overflow behaviour, close-status mapping. A
   split test: two real contexts, many client sockets, **exactly one trunk connection** between them.
3. **Failure and lifecycle.** Trunk loss closes channels `1011`; retire closes `1012` and drains; the
   store-away behaviour (a wipe or outage with channels open changes nothing; a new channel with a cold
   routing table and a live trunk still opens); the `4404` retry on the next host.
4. **The example and the docs.** An order-status feed in `examples/shop-*`, run at every rung
   (`MonolithTest`, `SplitTest`, `SharedStoreTest`), a guide chapter (`docs/guide/08-channels.md`),
   `docs/scope.md` (the streaming line), `docs/reference/{wire-protocol,configuration,modules,observability,compile-time-checks}.md`.

## Verification

- Unit: the frame codec; `TrunkPool` reuse, idle close and reconnect; mailbox and queue overflow close
  `1013` without disturbing other channels; the close-status table; the processor rules (positive and
  negative, like the existing processor tests).
- Integration, in the style of `HengeServiceRemoteDispatchIntegrationTest` and `HengeRetirementTest`: a
  backend context and a frontend context; kill the backend and the client gets `1011`; retire and it gets
  `1012` and the drain completes; a wrong secret is `403` at the handshake; a node not ready answers the
  handshake `503`.
- Store: with channels open, make the store unavailable and wipe it (the `SwitchableStore` in
  `HengeBootGateTest` is a ready model): channels keep flowing; open another on a live trunk; with no
  trunk and a cold routing table the open fails `1013`.
- `./gradlew build` passes. The Redis tests need Docker, which the machine has.

## Where things are, and what to know

The state of the code this builds on, as of the commit that adds this document:

- **Store failure policy (done).** `GuardedDatastore` wraps the datastore bean (so tests that cast the bean
  must unwrap: `((GuardedDatastore) bean).delegate()`); `StoreUnavailableException` is `@ErrorStatus(503)`;
  `HengeBootGate` (a `SmartLifecycle`, phase `0`, public `isReady()`) holds a node not ready until it has
  reached the store; `HengeNotReadyFilter` and `HengeBootGateRunner` in the starter turn that into `503`
  and Boot readiness; leased services start as a pending `ServiceBinding`; rate limiters degrade to a `1/N`
  share. `AdvertisedEndpoints` keeps a *routing table* (never call the store a cache: it is the critical
  path, and the docs say so).
- **`SmartLifecycle` phases.** The default phase is `Integer.MAX_VALUE`, the same as the advertiser and
  after the web server (`MAX_VALUE - 1`). Give anything that must run earlier an explicit phase; the gate
  learned this the hard way.
- **Same package, two jars.** `henge-spring-boot-starter` uses the package `digital.demilich.henge.spring`
  too, so it reaches package-private types of `henge-spring`.
- **`404` means nothing happened** and is retried by the RPC transport; never put it on a failure that can
  follow side effects.
- **Versions are plain integers**; don't propose semver.
- **Docs conventions.** Guide chapters are numbered under `docs/guide/`; reference tables are updated with
  the code, and `ConfigurationMetadataTest` lists the properties in the metadata JSON. Keep wording honest:
  say what isn't built.
- **Git.** Commit locally; nothing here is pushed. `.claude/` is untracked and stays that way.
