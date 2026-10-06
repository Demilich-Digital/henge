# 8. Channels

A call is one request and one answer. Some things are not: an order's status as it changes, a feed, a
chat. These are long-lived connections to a client, and they have a problem calls don't: if each of a
thousand customers holds a connection to the service behind your frontend, that service carries a thousand
connections, which is the load the frontend exists to absorb.

A **channel** is a connection to a client that goes through a frontend, which holds the clients, and a
**trunk**, one connection from the frontend to each backend node it talks to, however many clients that is.
The service behind sees one connection per frontend.

```
 browsers ──ws──►  frontend (every node)  ═══ trunk (one websocket) ═══►  backend (few nodes)
 browsers ──ws──►     holds N client sockets,                                sees one connection
 browsers ──ws──►     one trunk per backend node                             per frontend
```

The first use is websockets at the edge, and the shop has one: a customer watches an order.

## A channel method

A channel is opened by an ordinary service method that **returns a `ChannelHandler`** and takes the
client's `Channel` as its last parameter. There is no annotation.

```java
@HengeService
public interface OrderService {

    /** Opens a feed of an order's status: its current status first, then each change. */
    ChannelHandler watch(UUID orderId, Channel toClient);
}
```

`Channel` is how the service reaches the client (`sendText`, `sendBinary`, `close`), and `ChannelHandler` is
what the service gives back: it is called for what the client sends and when the channel closes.

```java
@Override
public ChannelHandler watch(UUID orderId, Channel toClient) {
    get(orderId); // throwing refuses the channel
    watchers.computeIfAbsent(orderId, id -> ConcurrentHashMap.newKeySet()).add(toClient);
    toClient.sendText(orders.get(orderId).status().name());
    return new ChannelHandler() {
        @Override
        public void onClose(CloseStatus status) {
            watchers.get(orderId).remove(toClient);
        }
    };
}
```

Everything else about it is a service method. The other parameters are boundary types, checked at compile
time like any other ([the rules](../reference/compile-time-checks.md#channel-methods)). `@AddedIn` and
versions apply unchanged. Interceptors wrap the open. The caller writes `orders.watch(id, clientChannel)`
and gets the handler back, the same code whether the service is in this process or another.

Henge never reads a frame: text and binary are passed through as they are, and the protocol between your
client and your service is yours.

## The client's websocket

Your application serves the websocket to browsers, as Spring always has, and Henge supplies the glue:

```java
@Configuration
@EnableWebSocket
class OrderFeed implements WebSocketConfigurer {

    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                ClientChannels.bridge((session, toClient) -> orders.watch(orderIdOf(session), toClient)),
                "/ws/orders/*");
    }
}
```

`ClientChannels.bridge` builds the `Channel` for each client connection, calls your function to open the
channel, and pipes the client's frames to the handler. A close from either end closes the other with the
same status. Authenticating the customer, and checking that the order is theirs, is your endpoint's job, on
your path: whatever identity the service needs goes to it as ordinary arguments of `watch`.

Add `spring-boot-starter-websocket` to the application: it is the websocket server, and brings
`spring-websocket`, which Henge only uses when it is there. A node that opens no channels, and hosts none,
pays nothing.

## Running it

The same jar at every rung, as ever.

- **A monolith**: the channel is a method call. Nothing is on a network.
- **Split**: the process the client dials, the *edge*, hosts none of the service. The first channel to a
  backend opens a trunk to it, `{henge.server.path-prefix}/_trunk` (`/_henge/_trunk`), and every channel
  after it shares that trunk. The backend is found as a call's is: its configured url, else
  `henge.remote-url-template`, else whoever advertises the service.

```bash
# the storefront, which holds the orders
java -jar $JAR --server.port=8081 --henge.serve=order-service,notification-service \
  --henge.services.inventory-service.url=http://localhost:8082
# the edge, which customers connect to, and holds no orders
java -jar $JAR --server.port=8080 --henge.serve=notification-service \
  --henge.services.order-service.url=http://localhost:8081 \
  --henge.services.inventory-service.url=http://localhost:8082
```

A thousand customers watching orders at the edge are a thousand client sockets there, and one connection
at the storefront.

## When a channel ends

A channel closes with a WebSocket close code, so clients and browsers understand it. **Reconnecting is
always the client's choice, and always correct**: a closed channel is never silently re-opened, because
what the service had remembered about it is gone.

| Code | Meaning |
|---|---|
| `1000` | Normal close, by either end. |
| `1011` | Unexpected failure: the backend threw, or its trunk was lost (it died, or the network broke). |
| `1012` | The backend is **retiring** the service. Reconnect, and you land elsewhere. |
| `1013` | Try again later: a queue overflowed, or no backend could be found or asked. |
| `1009` | A frame larger than `henge.channels.max-frame-bytes`. |
| `4000 + status` | The open threw an exception annotated `@ErrorStatus(status)`: `@ErrorStatus(404)` closes `4404`, a version that doesn't have the method `4501`. An unannotated exception is `1011`. |

- **A backend retires or is rolled.** Its channels close `1012` as it drains, one node at a time, and the
  clients reconnect to the new ones. Open channels don't hold up the drain.
- **A backend dies.** Its trunk drops, and every channel on it closes `1011`.
- **A backend that doesn't host the service** answers an open with `404`, which says nothing happened, so
  the frontend opens the channel once more on another advertised backend, resending what the client had
  sent meanwhile.
- **The store goes away.** An open channel never needs the store: it is read to *choose* a backend for a new
  channel, never while one is open. Channels stay open, and are never closed because of the store. A new
  channel on a backend the frontend already has a trunk to, and has opened the service on, still opens. With
  none known it fails `1013`, honestly, instead of guessing. See [the ephemeral
  store](05-the-ephemeral-store.md#fault-tolerance-when-the-store-is-away).
- **Too slow.** A frontend is light, so everything is bounded: a channel may have `henge.channels.queue-size`
  frames waiting in each direction, and one that overflows is closed `1013`, without holding up any other
  channel on the trunk. A slow handler stalls only its own channel.

## Behind TLS

Henge does no TLS of its own: it belongs at the edge, in whatever fronts your processes (a reverse proxy,
`tailscale serve`), and the trunk is plain `ws://` on the private network, like `/_henge`. A backend url that
is `https://` is dialed as `wss://`, with the JVM's trust.

A proxy that terminates TLS for the client's websocket needs one thing from your application: Spring
rejects a websocket handshake whose `Origin` doesn't match the host it sees, and behind a proxy it sees the
wrong one. Set `server.forward-headers-strategy=framework`, or `setAllowedOrigins(...)` on the handler. The
trunk is not affected: its client sends no `Origin`.

## Operating it

- **Security** is the network, as for `/_henge`. With `henge.transport.secret` set, the trunk's handshake
  needs it (`403` otherwise), and a node that isn't ready answers it `503`. Spring Security's chain for
  `/_henge` covers the trunk's `GET` too.
- **Metrics**: `henge.channels.open`, `henge.channels.closed` and `henge.trunks.open`; see
  [observability](../reference/observability.md#system-meters).
- **Frame size**: `henge.channels.max-frame-bytes` also bounds the open, so arguments of an open that are
  larger than a frame are refused. Pass identifiers, not documents.
- **Not built yet**: re-attaching a channel to another backend, sending to a channel by id from another
  node, trace propagation across the trunk, and a backend noticing a frontend that vanished without closing
  the connection. See [scope](../scope.md).

## What you have

Long-lived connections to clients, held by many light frontends and carried to a few resource-bound
backends over one connection each, from the same service interface and the same jar as everything else.

## Where next

- [Operating](07-operating.md), [Configuration](../reference/configuration.md#channels), the
  [design](../design/channels.md)
