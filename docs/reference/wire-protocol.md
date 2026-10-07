# Wire protocol

How an `internal-rest` call travels between two Henge processes. Both sides are Henge; this is
documented so that what's on the network is never a mystery, not as an API to build other clients
against.

## The request

```
POST {path-prefix}/{service}/{version}/{method}
Content-Type: application/json
Henge-Internal-Secret: <secret>          (only when henge.transport.secret is set)

[ <argument 1>, <argument 2>, ... ]
```

- `{path-prefix}` is `henge.server.path-prefix`, `/_henge` by default; `{service}` the service's name,
  `{version}` an integer, `{method}` the method's name (or its `@ServiceMethod(name = ...)`).
- The body is a JSON array of the arguments, matched by position against the method's declared parameter
  types.
- The request goes to the url resolved for that service version (see
  [Configuration](configuration.md#topology)), with the configured connect and read timeouts.

## The response

| Status | Body | Meaning |
|---|---|---|
| `200` | The return value, as JSON | The method returned. |
| `204` | none | A `void` method returned. |
| `400` | error | The arguments couldn't be read. |
| `403` | error | The secret is missing or wrong. Checked before the body is read. |
| `413` | error | The body is over `henge.transport.max-body-bytes`. Reading stops at the limit; the method isn't called. |
| `404` | error | This process doesn't serve that service, version or method. |
| `4xx`/`5xx` from `@ErrorStatus` | error, with the exception | The method threw an exception annotated `@ErrorStatus`. |
| `501` | error, with the exception | `ServiceVersionUnsupportedException`: the method isn't in this version's range. |
| `500` | error, with the exception | The method threw anything else. |

The error body:

```json
{
  "error": "Henge service 'inventory-service#reserve' threw digital.demilich.henge.examples.shop.inventory.OutOfStockException: chisel: 1000 wanted, 2 available",
  "exceptionType": "digital.demilich.henge.examples.shop.inventory.OutOfStockException",
  "exceptionMessage": "chisel: 1000 wanted, 2 available"
}
```

`exceptionType` and `exceptionMessage` are present only when the method itself threw. The dispatcher's
own failures (`400`, `403`, `404`) carry no exception, so they stay distinguishable from a business
exception that uses the same status.

An `ImmutableBytes` is a JSON string of standard base64 with padding, the text Jackson writes for a
`byte[]`; an empty one is `""`. `henge.transport.max-body-bytes` (10 MiB) bounds the body in both
directions: the dispatcher answers `413` to a request over it, and a caller refuses to send an argument
over it, or to read a response over it, before wasting the trip.

Arguments and return values are written by the transport's own `ObjectMapper`, never the application's,
so an application's Jackson configuration doesn't change what's on the wire. It reads Henge's immutable
collections, and Guava's when Guava is present, and ignores record components it doesn't know (a missing
one reads as `null` or `0`), which is what lets processes of different releases talk.

## Exceptions

When the response carries an exception, the caller rebuilds it: if `exceptionType` names a
`RuntimeException` with a public `(String)` constructor, the caller throws an instance of that type, with
the message, and a `RemoteServiceException` (which service, which method, which status) as its cause.
Anything that can't be rebuilt (an unknown type, a checked exception, no such constructor) is thrown as
the `RemoteServiceException` alone.

The type is resolved through the application's class loader, not that of the interface declaring the
method, so it works for methods inherited from JDK or third-party interfaces, and under split class
loaders such as Spring Boot DevTools'. A peer chooses which exception type the caller instantiates, among
the `RuntimeException`s on its classpath: one more reason `/_henge` belongs on a trusted network.

Checked exceptions are out of scope, and rejected at compile time: a JDK dynamic proxy can only throw a
checked exception that the interface method declares, and a rebuilt one that doesn't fit would surface as
an opaque `UndeclaredThrowableException`.

## Retries

A call is retried (by default up to 3 attempts, 50 ms apart) only when it provably never ran:

- **`connect`**: no connection could be made (refused, unknown host, no route, a connect timeout).
- **`not-served`**: any `404`. A `404` means nothing happened: the process doesn't serve that service
  version, or isn't a Henge process at all. It is also what an `@ErrorStatus(404)` exception answers, so
  such an exception must be thrown before the method has any effect.

A retry goes to the next advertised host where there is one, and otherwise to the same url. A host that
just failed isn't offered again until the advertisements are next read, unless it's the only one.
Nothing that may have started is retried: a read timeout, a reset, a `5xx`, any other exception. When the
attempts run out, a failed connection or a plain `404` is a `RemoteServiceException` (`... gave up after 3
attempts`), and an `@ErrorStatus(404)` exception is thrown as it came, so a caller still catches its own
type.

## Channels: the trunk

A [channel](../guide/08-channels.md) doesn't travel as a call. Between a frontend and a backend node there is
one WebSocket, the **trunk**, carrying every channel between them:

```
GET {path-prefix}/_trunk            (an upgrade to a WebSocket)
Henge-Internal-Secret: <secret>     (only when henge.transport.secret is set)
```

`403` without the secret, `503` while the node isn't ready, before the upgrade. `https://` backends are
dialed as `wss://`. The trunk carries **binary** messages only, each one frame:

```
[ type : 1 byte ] [ channel : 8 bytes, big-endian ] [ payload : the rest ]
```

| Type | Direction | Payload |
|---|---|---|
| `1` `OPEN` | frontend to backend | UTF-8 JSON `{"service": ..., "version": ..., "method": ..., "args": [...]}`. The channel number is the frontend's, unique within the trunk. |
| `2` `TEXT` | both | UTF-8 text. |
| `3` `BINARY` | both | The bytes. |
| `4` `CLOSE` | both | Two bytes of status code, then the UTF-8 reason (at most 123 bytes). Closing is idempotent. |

`args` is written and read as the body of a call is: positional, typed by the method's declared parameters
except the final `Channel`, by the transport's own `ObjectMapper`. An `OPEN` that can't be served is answered
with a `CLOSE`: `4404` for a service, version or method this node doesn't host, `4400` for arguments that
can't be read or a method that isn't a channel method, and otherwise the status the open's exception maps to
(see [When a channel ends](../guide/08-channels.md#when-a-channel-ends)). A frame for a channel that is
closed is ignored.

Both ends ping the trunk (`henge.channels.trunk.ping-interval`), and any frame counts as an answer. A trunk
that stays silent for two intervals, or is closed, ends every channel on it: with `1011` at the frontend, and
`1001` for each handler at the backend. A trunk with no channels is closed after
`henge.channels.trunk.idle-timeout`.
