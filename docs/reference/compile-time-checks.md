# Compile-time checks

`henge-processor` checks every `@HengeService` interface and `@ServiceVersion` implementation it
compiles, and generates version skeletons. It runs only where it's declared as an `annotationProcessor`
(see [Project setup](../guide/01-project-setup.md#the-annotation-processor)). Each check below would
otherwise compile, and then fail, or silently do nothing, at startup or once a service is split. Where a
mistake can also come from code compiled without the processor (names, stereotypes, duplicate versions),
startup repeats the check.

## Boundary types

Every parameter and return type of a `@HengeService` method must be provably immutable, recursively,
through record components and type arguments. Allowed:

- records (each component checked) and enums
- primitives, `String`, and immutable JDK value types: `java.time.*`, `UUID`, `BigDecimal`, `BigInteger`
- `ImmutableBytes` from `henge-core`, for binary data (see [below](#binary-data))
- `ImmutableList<T>`, `ImmutableSet<T>`, `ImmutableMap<K, V>` from `henge-core`, Guava's classes of the
  same names, and `Optional<T>`, of allowed types

Rejected, with the reason:

- **`java.util.List`, `Set`, `Map`** and other mutable types. Over the wire they deserialize as a mutable
  `ArrayList` or `HashMap`, and a caller and service in one process would share them by reference.
- **JPA entities**, with a message naming `@Entity`: mutable, lazily loaded, attached in one process and
  detached over the wire.
- **Sealed interfaces**: JSON has no type information to pick a subtype with, so they work embedded and
  fail once split. Model a tagged union as a record: a `kind` enum and the fields each kind needs.
- **Map keys other than** `String`, a boxed primitive, an enum or one of the value types above. A key
  travels as a JSON object key, a string, so a record, `Optional`, collection or `ImmutableBytes` key can't
  be read back.
- **Arrays, type variables, and wildcards without a usable bound** (`?`, `? super X`). A `byte[]` is
  answered with "use ImmutableBytes", any other array with "use ImmutableList<T>".
  `ImmutableList<? extends Point>` is fine.
- Types nested deeper than the processor follows.

`henge-core`'s immutable collections are backed by `List.copyOf`, `Set.copyOf` and `Map.copyOf`; their
mutators throw. They're distinctly named so the processor can recognize the *type*, and the transport
deserializes them (and Guava's, when Guava is on the classpath; Henge never adds it). `henge-core`
itself doesn't depend on Jackson.

## Binary data

`ImmutableBytes` is the boundary type for a byte sequence, because a `byte[]` can be mutated by whoever
holds it. It copies on the way in (`copyOf`) and out (`toByteArray`), and compares by content, so a record
with an `ImmutableBytes` component has the `equals` you'd expect. On the wire it is a base64 string, the
text Jackson writes for a `byte[]`.

The whole body of a call is held in memory, and `henge.transport.max-body-bytes` (10 MiB by default,
counting the base64, so about 7.5 MiB of binary) bounds it. For anything larger, use a
[channel](../guide/08-channels.md), which carries binary frames, or pass a reference to where the data
lives.

## Exceptions

A `@HengeService` method may not declare a checked exception. Embedded, the caller would get it; over
the wire it can't be rebuilt (see [Wire protocol](wire-protocol.md#exceptions)). Wrap it in an unchecked
exception.

`@ErrorStatus` must be a `4xx` or `5xx` code.

## Interface shape

Over its own methods and those it inherits (exactly the methods the dispatcher exposes):

- **No overloaded method names.** Calls are dispatched by name; rename one, or give it
  `@ServiceMethod(name = ...)`.
- **No static methods.**
- **No type parameters** on the interface or its methods, and no methods inherited from a generic
  interface: nothing at runtime knows what `T` is, so it would arrive as an untyped JSON map. Generic
  *records* are fine, checked through their type arguments: `Box<Point>` passes, `Box<List<String>>`
  doesn't.
- `@HengeService` on a non-private interface only, with a positive `defaultVersion`.

## Channel methods

A method that returns `ChannelHandler` opens a [channel](../design/channels.md) instead of answering a
call, and the processor recognizes it by that return type:

- It must have exactly one `Channel` parameter, and it must be the last.
- Every other parameter is a boundary type as usual, and the `ChannelHandler` return type is not checked
  as one.
- `Channel` anywhere but as that last parameter, and `ChannelHandler` anywhere but as the return type, is
  an error. Declare a channel as `ChannelHandler watch(String orderId, Channel toClient)`.

## Scheduled methods

Spring's `@Scheduled` runs once per process, so in a cluster it runs once per node. A method annotated
`@Scheduled` (or `@Schedules`) is an error unless it, or a class it is nested in, carries
`@HengeAcknowledgeThisRunsOnEveryNode`. The message names the method and both ways out: `@HengeScheduled`
for once per cluster, or the acknowledgement. Only a module that runs the processor and has Spring on its
classpath is checked; `henge-spring` refuses the same at startup, which also covers a
`SchedulingConfigurer` that no annotation marks. See [scheduled jobs](../guide/09-scheduled-jobs.md#running-on-every-process).

## Names

A service's name, explicit or the interface's simple name in kebab case (`InventoryService` →
`inventory-service`), must be lowercase kebab case: it appears in configuration keys and in the dispatch
path. A method's RPC name must be a Java identifier.

## Versions

- **`@AddedIn(n)` / `@DeprecatedSince(n)`** on an interface method: the method exists from version `n`
  on, or is optional from version `n` on. Ranges must be non-empty and positive, and they can't be on a
  default method (a method with a body is never required).
- For any interface with such methods, the processor generates `{Interface}Skeleton` in the same package:
  an abstract class implementing every versioned method by throwing `ServiceVersionUnsupportedException`.
  Implementations extend it.
- **An implementation must really implement every method in its version's range**, `[addedIn,
  deprecatedSince)`. Relying on the skeleton's stub for one is an error.

Versions are plain integers everywhere, which is how ranges are computed, and why `1` and `01` can't be
two different versions.

## Implementations

`@ServiceVersion` must be on a concrete top-level or static nested class (or record) that implements a
`@HengeService` interface, with a positive version that no other implementation of the interface in the
same compilation claims, and **without `@Component` or `@Service`**: Henge registers it, and a
stereotype would make a second instance. At an injection point, `@ServiceVersion` must name the
interface being injected.
