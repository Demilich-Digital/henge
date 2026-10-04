# Henge

Run this jar with no flags, and it's a monolith. Run the *exact same jar*, twice, each with one
flag, and it's two independently deployable microservices talking to each other over HTTP —
nothing recompiled, nothing rewritten, same `.jar` both times. Henge is what makes that true:
whether a service call happens in-process or over the network is a deployment-time decision, not a
design-time one, and API versioning lives in the compiled binary instead of at the network
boundary.

A Spring Boot add-on for building these services: define one once as a Java interface plus a
`@ServiceVersion`-annotated implementation, and deployment config — not code — decides whether it
runs in-process or gets dispatched over HTTP, per `--modular.services.<name>.mode=...` flag.
Multiple implementations of the same interface can run side by side — each dependency pins which
version it wants, or gets the default automatically, so a version change never requires the whole
deployment to move in lockstep.

## Why this isn't just a "modulith"

The term "modulith" usually means a code-organization discipline: one deployable, forever,
internally organized into well-bounded modules (this is what Spring Modulith itself gives you).
That's not what this project does. Henge makes two specific, load-bearing claims that a
code-organization discipline alone doesn't:

1. **API versioning lives in the binary, not the network.** `@ServiceVersion` plus
   `@AddedIn`/`@DeprecatedSince` mean two generations of a service's API coexist as real compiled
   types in the same jar, resolved by Spring DI qualifier. There's no gateway doing header-based
   routing between differently-versioned deployed revisions to make this work — the "old" and
   "new" implementation are just classes, and which one a given caller gets is a compile-time-checked,
   runtime-resolved fact, not an infrastructure concern layered on afterward.
2. **The monolith-vs-microservice topology is a deployment-time decision, not a build-time one.**
   The exact same jar you run as a single local process (direct method calls, one thing to debug)
   is the exact same jar you split across machines (independent scaling, independent failure
   domains) — switched entirely by `--modular.services.*` flags, including per-version, mid
   migration if you want v1 of a service embedded while v2 is already split out.

That combination — monolith development ergonomics plus microservice deployment flexibility,
without a rewrite or a redeploy to move between them — is the actual goal. The
versioning-in-the-binary and deploy-time-topology mechanics are solid, and processes can find each
other without configured addresses: each advertises what it hosts on a shared datastore (see
"Service advertisements"), calls rotate over what's advertised, and a call that provably never ran is
retried on the next host (see "Retries"). `--modular.serve` and `--modular.remote-url-template`
still let the binary lean on an existing orchestrator's own discovery (k8s DNS, Consul DNS, ...)
instead. What isn't built is the rest of the operational maturity of a "real" microservice fleet:
health- or load-aware routing, circuit breaking, and tracing across the process boundary (see
"Not in v1" and the Roadmap).

## Concept

- **`@ModularService`** marks an *internal* service boundary interface. It is never used for a
  project's public-facing HTTP API — that stays plain Spring MVC (`@RestController`,
  `@GetMapping`, ...), fully developer-owned, and the framework never touches it.
- **`@ServiceVersion(value = TheInterface.class, version = 1)`** stands in for `@Service` on an
  implementation — the framework registers the bean itself, so don't also annotate it `@Service`/
  `@Component` (that would register a second instance, and is rejected at compile time and at startup). Multiple classes can implement the same interface as long as each declares a
  distinct version; they all coexist as separate beans in the same process.
- A dependency that doesn't care which version it gets just injects the interface normally —
  no annotation needed, resolves to the interface's `defaultVersion()` (default version) via
  Spring's `@Primary` mechanism. A dependency that needs a *specific* version puts the same
  `@ServiceVersion` annotation on the constructor parameter or field:

  ```java
  @ServiceVersion(value = GreetingService.class, version = 1)
  public class GreetingServiceImpl implements GreetingService {
      public GreetingServiceImpl(@ServiceVersion(value = AuditService.class, version = 1) AuditService auditService) { ... }
  }
  ```

  Most services only ever have one version and never need to write `@ServiceVersion` at an
  injection site at all — this only matters once a second version of a dependency exists.
- Whether a given service (version) is **embedded** (direct in-process method call) or
  **internal-rest** (dispatched over HTTP to whichever process hosts it) is decided per-service,
  per-process, by config:

  ```yaml
  modular:
    services:
      audit-service:
        mode: internal-rest        # embedded (default) | internal-rest -- applies to every version unless overridden below
        url: http://localhost:8082 # where to call it; optional, see "Service advertisements"
        versions:
          "2":
            mode: embedded          # per-version override; inherits mode/url above when unset
  ```

  or the CLI-flag equivalent: `--modular.services.audit-service.mode=internal-rest --modular.services.audit-service.url=http://localhost:8082`.
  Single-version services never need the `versions` block at all.
- For deployments with more than a couple of services, spelling out `mode`/`url` for every
  service *other* than the one(s) a given process hosts gets old fast — **`--modular.serve`** and
  **`--modular.remote-url-template`** exist so a process can instead just declare what it *is* and
  let an existing orchestrator (Kubernetes, ECS, Nomad, ...) handle the rest. See "Fitting into an
  existing orchestrator" below.
- Callers always just `@Autowired` the interface (optionally qualified with `@ServiceVersion`).
  They never know or care which mode is in effect — that's the location transparency the whole
  framework exists for.
- **`@AddedIn(2)`** / **`@DeprecatedSince(3)`** on an interface method mark it as only
  existing from that version onward, or optional from that version onward, respectively — so an
  older (or newer) `@ServiceVersion` implementation isn't forced by the Java compiler to
  implement a method that doesn't apply to it. See "Compile-time method versioning" below.

## How it works

- `@EnableModularServices` (put next to `@SpringBootApplication`) discovers every
  `@ModularService` interface and every `@ServiceVersion`-annotated implementation on the
  classpath (implementations are registered directly by the framework, not by `@ComponentScan` —
  `@ServiceVersion` carries no `@Component` meta-annotation). For every (interface, version) pair
  in play:
  - **embedded** (default): registers the local implementation class directly. Fails fast at
    startup if no implementation exists for a version configured/expected as embedded.
  - **internal-rest**: registers a JDK dynamic proxy in its place that dispatches calls over HTTP.
  - Whichever version matches the interface's `defaultVersion()` is marked `@Primary`, and every
    registered bean carries `@ServiceVersion` qualifier metadata — together these are what let
    Spring's own autowiring resolve an unqualified dependency to the default version and a
    qualified one to the exact version it asked for, with no custom autowiring code.
- A single generic `ModularDispatcherController` serves `POST /_modular/{service}/{version}/{method}`
  for every (service, version) this process embeds, validated strictly against a startup-built
  registry keyed by bean name — never by type alone, since multiple versions of the same interface
  may be embedded in the same process. Arguments and the return value are a JSON array / JSON
  value, matched positionally against the method's declared parameter types. The
  security model is network isolation: `/_modular` is meant to sit on a private network (VPC,
  service mesh, cluster-internal DNS), and running with no secret is a fully supported
  configuration — the expected one there, since every embedded `@ModularService` method is
  reachable by whoever can reach the process's HTTP port, which on a private network is only your
  own services. Set `modular.transport.secret` for defense in depth where that isn't enough:
  `InternalRestTransport` then sends it as a `Modular-Internal-Secret` header on every call, and
  the dispatcher requires it (constant-time compare, checked before the request body is parsed,
  `403` otherwise). With no secret the dispatcher logs one INFO line saying so, not a warning.
  `/_modular` shares `server.port` with whatever public API you
  build on top — there's no separate listen port, and there isn't meant to be one: `/_modular` was
  never intended to be internet-facing in the first place, so splitting it onto its own port
  doesn't buy anything a network boundary (VPC / service mesh) doesn't already give you. If you do
  expose a public "frontend" API from the same process, put it on a different port/process
  yourself — that's a decision this framework deliberately stays out of.
- **Spring Security.** Add it and `/_modular` just works — no flag. Spring Security's default chain would
  demand a user session and a CSRF token on every POST, so the Boot starter adds a dedicated
  `SecurityFilterChain` for exactly `POST {modular.server.path-prefix}/**` (under `spring.mvc.servlet.path`, if you
  set one), ordered ahead of yours and
  added *next to* whatever you have, including Boot's default chain, never instead of it — nothing else
  you serve is affected. With `modular.transport.secret` set, the secret is a real authentication: a
  valid `Modular-Internal-Secret` header becomes an authenticated principal
  (`modular-internal-service`, holding `ROLE_MODULAR_SERVICE`, visible to your code, method security and
  auditing), and anything else is rejected by Spring Security with `403` — the same status the
  dispatcher answers without it. With no secret the chain is an explicit `permitAll`: the network is the
  boundary, a supported configuration. Sessions are stateless and CSRF is off for this path, which is
  sound because CSRF defends cookie/session authentication and this endpoint has none. To apply your own
  policy to the path instead (JWT, mTLS, ...), define a bean named `modularSecurityFilterChain` and
  Henge's backs off.
- Remote calls go through a single `ServiceTransport` seam (`digital.demilich.henge.core`) between the
  generated proxy and the wire. `internal-rest` (HTTP + JSON) is the only transport, and it is not an
  extension point today: implementing the interface is the small part of adding another (say gRPC). The
  serving side (`ModularDispatcherController`) and the wiring are REST-specific too —
  `ModularTransportConfiguration` registers exactly one `ServiceTransport`, and the proxy looks it up by
  type, so a second bean would be ambiguous. A new transport is a design exercise, not a drop-in.
- A caller of an **embedded** service sees whatever exception the real implementation throws; a
  naive **internal-rest** transport would only ever be able to throw a generic exception instead,
  which defeats the point of location transparency the moment something goes wrong. So when the
  remote implementation's own method throws, `internal-rest` reconstructs that original exception
  client-side — on a best-effort basis, for `RuntimeException` subtypes with a `(String)`
  constructor (the common case for hand-written business exceptions) — with a
  `RemoteServiceException` (the network-level diagnostic: which service/method, what HTTP status)
  chained in as its cause. Anything that can't be reconstructed (an unknown type, a checked
  exception, no compatible constructor) falls back to that `RemoteServiceException` alone. See `RemoteExceptionReconstructor`'s Javadoc for why checked exceptions are out of scope
  — it comes down to a real limitation of JDK dynamic proxies, not an oversight.
  Reconstruction instantiates whichever `RuntimeException` subtype the remote response names
  (public `(String)` constructor required), looked up through the application's own classloader —
  not the loader of the interface that declares the called method, so it also works for methods
  inherited from a JDK/third-party interface and under split-classloader setups such as Spring
  Boot DevTools. Like everything else on `/_modular`, this assumes the peer is on a trusted
  network: a compromised peer could pick any such exception type present on the caller's classpath.
- **HTTP status of a failure.** The dispatcher answers `500` for anything it can't classify — a
  business exception that says nothing about its own status, or an unexpected error — and
  deliberately nothing else. An exception opts into a different status with
  `@ErrorStatus(404)` (`digital.demilich.henge.core`; `4xx`/`5xx` only, inherited by subclasses),
  e.g. `@ErrorStatus(404) class WidgetNotFoundException extends RuntimeException` (the framework's own
  `ServiceVersionUnsupportedException`, a call outside the implementation's version range, carries
  `@ErrorStatus(501)`). The caller still
  gets the original exception reconstructed; only the wire status changes, and it has no effect when
  the service is embedded. The dispatcher's own failures use `400` (malformed request/arguments),
  `403` (bad secret) and `404` (unknown service/version/method); those bodies carry no exception
  type, so they stay distinguishable from an annotated business exception that reuses the code.
  Every `404` is treated as "nothing happened", and retried: see "Retries" below.
- **Logging a failure.** The caller only gets the exception's type and message, so the serving
  process logs the stack trace: a failure answered with a `5xx` at `ERROR`, a `4xx` at `DEBUG`.
  `@ErrorLogLevel(ErrorLogLevel.Level.WARN)` (`digital.demilich.henge.core`) on the exception
  overrides that, independently of its status — `NONE` turns it off, any other level logs there.
  `ServiceVersionUnsupportedException` carries `DEBUG`: it's the caller's mistake.
- The runtime wiring itself is just `BeanDefinitionRegistry` manipulation, `java.lang.reflect.Proxy`,
  and Spring's own `@Primary`/qualifier autowiring machinery — no bytecode generation there. See
  the Javadoc on
  [`ModularServiceRegistrar`](modular-spring/src/main/java/digital/demilich/henge/spring/ModularServiceRegistrar.java)
  for the exact bean-wiring mechanics. `@AddedIn`/`@DeprecatedSince` are the one place this
  project *does* use real annotation processing — see below.

## Compile-time method versioning: `@AddedIn` / `@DeprecatedSince`

Java forces every concrete class to implement every abstract interface method — so without help,
adding a method to a `@ModularService` interface for version 2 would force version 1's
implementation to implement it too, even though it's meaningless there. `@AddedIn`/
`@DeprecatedSince` fix this at compile time via a real `javax.annotation.processing.Processor`
(the `modular-processor` module):

```java
public interface AuditService {
    void recordEvent(String event);
    ImmutableList<String> getEvents();

    @AddedIn(2)
    ImmutableList<String> getRecentEvents(int limit);
}
```

For any `@ModularService` interface with at least one `@AddedIn`/`@DeprecatedSince` method, the
processor generates a companion abstract class, `{Interface}Skeleton`, in the same package, with a
throwing override of every such method. An implementation `extends {Interface}Skeleton` instead of
`implements {Interface}` directly, and only overrides the methods actually in range for its
declared version — everything else falls through to the generated stub, with zero hand-written
boilerplate:

```java
@ServiceVersion(value = AuditService.class, version = 1)
public class AuditServiceImpl extends AuditServiceSkeleton {
    // doesn't override getRecentEvents at all -- calling it throws
    // ServiceVersionUnsupportedException: "...AuditServiceImpl#getRecentEvents is not
    // supported by this implementation (requires version >= 2)"
}

@ServiceVersion(value = AuditService.class, version = 2)
public class AuditServiceImplV2 extends AuditServiceSkeleton {
    @Override
    public ImmutableList<String> getRecentEvents(int limit) { ... } // version 2 actually supports it
}
```

The same processor also *validates* every `@ServiceVersion` implementation: if a method's
`[addedIn, deprecatedSince)` range includes the implementation's own declared version, it must be
genuinely overridden — silently relying on the generated throwing stub for a method the
implementation is actually supposed to support is a compile error, not a runtime surprise.

Two things worth knowing:
- Versions are plain integers everywhere: `@ServiceVersion(version = 2)`, `@AddedIn(2)`,
  `@DeprecatedSince(3)`, `@ModularService(defaultVersion = ...)`, and the `versions.<n>` /
  `name@<n>` keys in config and `--modular.serve`. That's how the processor orders versions to
  compute ranges, and it means `1` and `01` can never be two different versions. A non-integer
  version in config or `--modular.serve` fails at startup.
- Any module that compiles a `@ModularService` interface with versioned methods, or a
  `@ServiceVersion` implementation, needs `modular-processor` on its `annotationProcessor` (or
  `testAnnotationProcessor`) configuration explicitly — Gradle does not propagate annotation
  processors transitively. See `examples/example-contracts` and `examples/example-services`'s
  `build.gradle.kts` for the pattern.

## Compile-time boundary guarantees: the state-ownership doctrine

Location transparency is only real if an embedded call and an internal-rest call to the same
method behave identically. Two ways they silently don't, unless something stops them:

- **Checked exceptions.** Embedded dispatch propagates whatever the real implementation throws;
  internal-rest reconstructs failures as `RuntimeException` only (see
  `RemoteExceptionReconstructor`'s Javadoc). A checked exception on a `@ModularService` method
  would behave differently depending on which mode is configured for it — so `modular-processor`
  rejects checked exceptions in `@ModularService` method `throws` clauses at compile time. Wrap
  them in an unchecked exception instead.
- **Mutable/aliased state.** Embedded calls pass arguments and return values by reference inside
  one shared bean universe; internal-rest passes them by value, as JSON. A plain mutable class
  (or a JPA entity — attached, lazy-loadable embedded, detached and half-populated over the wire)
  can be mutated by whichever side holds a reference, invisibly to the other side, only in embedded
  mode. So the processor enforces a positive rule instead of chasing individual bad shapes: every
  parameter and return type reachable from a `@ModularService` method must be **provably
  immutable** — recursively, through record components and collection type arguments. Allowed:
  - `record`s and `enum`s (recursed into, for records)
  - primitives, `String`, and well-known immutable JDK value types (`java.time.*`, `UUID`,
    `BigDecimal`, `BigInteger`)
  - `ImmutableList<T>` / `ImmutableSet<T>` / `ImmutableMap<K, V>` (`digital.demilich.henge.core`) or
    `Optional<T>` of an allowed type — plain `java.util.List`/`Set`/`Map` are **not** allowed:
    Jackson deserializes them to a mutable `ArrayList`/`HashMap` by default, which reopens exactly
    the aliasing gap this rule exists to close. A map *key* is narrower still: it travels as a JSON
    object key (a string), so it must be `String`, a boxed primitive, an enum or one of the value
    types above — a record, `Optional` or collection key can't be read back, and is rejected
  - Guava's `ImmutableList`/`ImmutableSet`/`ImmutableMap` — if you already depend on Guava, you
    don't need a second immutable-collection type just to satisfy this rule. The processor
    recognizes them by name, and the transport deserializes them whenever Guava is on the
    classpath; Henge never adds Guava to yours

  Records may gain components without breaking a rolling deploy: the transport ignores a
  component it doesn't know and reads a missing one as `null`/`0`. Renaming or removing one is a
  breaking change — ship it as a new `@ServiceVersion`.

  Sealed interfaces are deliberately *not* allowed: Jackson can't pick a subtype without type
  information, so they would work embedded and fail once the service is split. Model a tagged
  union as a record instead (e.g. a `kind` enum plus the fields each kind needs).

  A JPA entity can never satisfy this — no-arg constructor, mutable fields, lazy proxying — so it's
  rejected as a side effect of the positive rule, with a message calling out the `@Entity`
  annotation specifically rather than the generic "not an allowed type" message.

  `ImmutableList`/`ImmutableSet`/`ImmutableMap` are genuinely immutable (backed by
  `List.copyOf`/`Set.copyOf`/`Map.copyOf`, mutator methods throw `UnsupportedOperationException`),
  and distinctly named so the processor can recognize the *type*, not just runtime behavior a
  caller happened to rely on. `modular-spring`'s shared transport `ObjectMapper` knows how to
  deserialize them, and Guava's (`HengeCollectionsModule`) — `modular-core` itself stays Jackson-free.

The same processor also checks the *shape* of a `@ModularService` interface, over its own and its
superinterfaces' methods alike (those are exactly the methods the dispatcher exposes): no
overloaded RPC names (rename one, or use `@ServiceMethod(name = ...)`), no static methods, no empty
or non-positive `@AddedIn`/`@DeprecatedSince` ranges and none on a default method (a method with a
body is never required to be implemented, so there's nothing to enforce), a positive
`defaultVersion`, and `@ModularService` only on a non-private interface. Names end up in the
dispatch path and in config keys, so a service name (explicit, or the interface's simple name in
kebab case: `AuditService` → `audit-service`) must be lowercase kebab case, and an RPC method name a
Java identifier.

`@ServiceVersion` must sit on a concrete top-level or static nested class (or record) that actually
implements a `@ModularService` interface, with a positive version no other implementation in the
same compilation claims, and without `@Component`/`@Service` (the framework registers it; a
stereotype would make a second instance). At an injection site it must name the injected
interface. `@ErrorStatus` must be a `4xx`/`5xx` code.

Each of these would otherwise compile and then fail — or silently do nothing — at startup or once
the service is split. Where the mistake can also come from code compiled without the processor
(names, stereotypes, duplicate versions), the registrar repeats the check at startup.

Generics: a `@ModularService` interface or method can't declare type parameters (nothing at runtime
knows what `T` is, so it would bind as an untyped JSON map once split), nor inherit methods from a
generic interface. Generic *records* are fine — `Box<Point>` is validated through its type
arguments, so `Box<List<String>>` is rejected like `List<String>` itself — and a wildcard needs a
usable bound (`ImmutableList<? extends Point>`; `?` and `? super X` are rejected).

**Enforcing the boundary at the build level, not just the type level:** nothing in the language
stops a consumer from depending on a service's implementation class directly instead of its
interface — that compiles fine embedded, then breaks the moment the service is split into its own
process. `examples/example-app` demonstrates the fix: its `build.gradle.kts` declares
`implementation(example-contracts)` + `runtimeOnly(example-services)`, so impl classes are on the
runtime classpath but never the *compile* classpath — `DemoController` physically cannot resolve a
reference to `AuditServiceImpl`, only to `AuditService`. This is the recommended consumption
pattern for any module that calls into a `@ModularService`.

## Modules

| Module | Contents |
|---|---|
| `modular-core` | `@ModularService`, `@ServiceVersion`, `@ServiceMethod`, `@AddedIn`, `@DeprecatedSince`, `@ErrorStatus`, `@ErrorLogLevel`, `@RequiresLease` / `Lease`, the `ServiceTransport` seam, the `SystemEphemeralDatastore` contract and its `InProcessEphemeralDatastore`, `RemoteServiceException`, `ServiceVersionUnsupportedException`. The only Spring dependency in this module is `spring-beans`, for `@ServiceVersion`'s `@Qualifier` meta-annotation — nothing else. |
| `modular-processor` | The compile-time half: generates `{Interface}Skeleton` classes for `@AddedIn`/`@DeprecatedSince`, validates `@ServiceVersion` implementations against them, and enforces the boundary rules (immutable boundary types, no checked exceptions, no generics/overloads/statics, sane version ranges and names). Declared to Gradle as an aggregating incremental processor. Depends only on `modular-core` — no Spring. |
| `modular-spring` | The actual mechanism, and Boot-free: `@EnableModularServices`, the bean-wiring registrar, the internal-rest transport, the dispatcher controller, leases (`@RequiresLease` enforcement and renewal), service advertisements and advertisement-based routing, retries, datastore selection (`modular.store.type`), plus `ModularTransportConfiguration`/`ModularDispatcherConfiguration`/`ModularConfiguration` — plain `@Configuration` classes a non-Boot consumer `@Import`s explicitly. Depends only on `spring-context`/`spring-web` (plus `spring-webmvc` at the consumer's own request for dispatch) — no Spring Boot anywhere. |
| `modular-spring-boot-starter` | A thin classpath-autodetection layer on top of `modular-spring`: `@AutoConfiguration` that imports the same transport wiring automatically, registers the dispatcher in servlet web applications behind the `modular.server.enabled` property gate, ships configuration metadata for IDE completion of `modular.*`, and — when Spring Security is present — adds the dedicated security chain for `/_modular` (see "Spring Security" above). These are the things a Boot classpath gets "for free" that a plain-Spring one doesn't. |
| `modular-redis` | `RedisEphemeralDatastore`: a `SystemEphemeralDatastore` on Redis 7.4+ (hash-field TTLs, one Lua script per operation, so `claim` is atomic across nodes). Needs Lettuce; no Spring. Add the module and set `modular.store.type=redis` and `modular.store.redis.uri`. |
| `examples/example-contracts` | `GreetingService` / `AuditService` — the two `@ModularService` interfaces used by the demo; `AuditService` has an `@AddedIn(2)` method. |
| `examples/example-services` | Their `@ServiceVersion` implementations, including a second `AuditService` version to demonstrate multi-version wiring and the generated-skeleton mechanism. No Spring dependency at all. |
| `examples/example-app` | One Spring Boot application tying it together, runnable as the monolith or as either half of a split deployment. |
| `examples/example-plain-spring` | The same idea with zero Spring Boot: a plain `AnnotationConfigApplicationContext` + `@EnableModularServices`, proving the core wiring mechanism works standalone. See "Using this without Spring Boot" below. |

## Quickstart

```bash
./gradlew build
```

The Redis-backed tests (`modular-redis`, and the Redis case in `modular-spring`) run against a
Redis container through Testcontainers, so they need Docker. Without it they are skipped, unless the
`CI` environment variable is set, in which case the build fails instead of passing without having
tested Redis.

### Run as a monolith (one process, both services embedded)

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar --server.port=8080
```

```bash
curl http://localhost:8080/api/greet/Alice   # -> "Hello, Alice!"
curl http://localhost:8080/api/audit         # -> ["greeted:Alice"]
```

`GreetingServiceImpl` called `AuditService.recordEvent(...)` as a plain in-process method call.
The process also serves `/_modular/**` for both services (`modular.server.enabled` defaults to
`true`), so it's simultaneously a fully valid microservice for either role, even though nothing
crossed the network for this particular request.

### Run as two split processes (same jar, two CLI-flag-configured instances)

Terminal 1 — hosts `audit-service` only; everything else this process discovers on the classpath
(`greeting-service`) defaults to `internal-rest` automatically because `--modular.serve` is set:

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar \
  --server.port=8082 \
  --modular.serve=audit-service
```

Terminal 2 — hosts `greeting-service` only, and is told where to find `audit-service` (no real DNS
on localhost, so this uses an explicit `url` — see "Fitting into an existing orchestrator" below
for the templated form real deployments would use instead):

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar \
  --server.port=8080 \
  --modular.serve=greeting-service \
  --modular.services.audit-service.url=http://localhost:8082
```

```bash
curl http://localhost:8080/api/greet/Bob   # -> "Hello, Bob!" (crossed processes for the audit call)
curl http://localhost:8082/api/audit       # -> ["greeted:Bob"], recorded by the *other* process
```

Same code, same jar, two independently deployable processes — the only difference is what each
one declares itself to *be*, not a growing list of everything it isn't.

### Fitting into an existing orchestrator

`--modular.serve` and `--modular.remote-url-template` are independent and composable:

- **`--modular.serve=<name>[@<version>][,...]`** sets what's embedded in this process; everything
  else discovered on the classpath defaults to `internal-rest` instead of the usual `embedded`
  default. Leaving it unset (the default) keeps everything embedded unless configured otherwise.
- **`--modular.remote-url-template=http://{service}.default.svc.cluster.local:8080`** fills in a
  `url` for anything that ends up `internal-rest` without one, substituting `{service}` with the
  service's name and `{version}` with the resolved version — matching whatever DNS convention an
  orchestrator already hands you for free (a Kubernetes `Service`, an ECS Cloud Map namespace,
  Consul DNS, ...). Any other placeholder fails at startup. `{version}` substitution is a no-op when the placeholder isn't present, so
  existing `{service}`-only templates keep working unchanged; a per-version split (see "Two
  versions of a service side by side" above) needs a template like
  `http://{service}-v{version}.default.svc.cluster.local:8080` to route each version to its own
  endpoint.

Precedence, per service: an explicit `modular.services.<name>.url` always wins (the escape hatch
for anything that doesn't fit the convention) → else derived from `--modular.remote-url-template`
→ else whichever process advertises the service on the shared datastore (see "Service advertisements") → else the
call fails, saying that no url is configured and nobody advertises it. Mode works the same way:
explicit `modular.services.<name>.mode` always wins → else `embedded` if `--modular.serve` names
this (service, version) or `--modular.serve` is empty → else `internal-rest`. Declaring a service
in `--modular.serve` while also explicitly setting its mode to `internal-rest` is a contradiction
and fails fast at startup rather than silently picking one.

There's no real DNS on localhost, but the fallback itself is fully exercisable there too —
equivalent to terminal 2 above, just via the template instead of an explicit `.url`:

```bash
--modular.serve=greeting-service --modular.remote-url-template=http://localhost:8082
```

Using the template (or an explicit url) means Henge tracks nothing about who is running where: the
orchestrator's DNS and load balancer do that. To have Henge track it instead, let the processes
advertise on a shared datastore; see "Service advertisements". See also "Why this isn't just a
'modulith'" above.

**Connect/read timeouts:** the `internal-rest` transport defaults to a 2s connect timeout and a
10s read timeout — a single hung remote service can't pin a caller thread forever. Override with
`modular.transport.connect-timeout` / `modular.transport.read-timeout` (milliseconds, or `10s`-style). A timed-out
call fails as a `RemoteServiceException` naming the timeout, within the configured bound.

**Environment variable configuration:** `modular.services.audit-service.mode` and friends are
plain dotted-kebab keys, read directly from Spring's `Environment` (see "Using this without Spring
Boot" below) — no Boot-specific relaxed-binding `Binder` involved. This works from a plain OS
environment variable regardless: Spring Framework's own `SystemEnvironmentPropertySource` already
translates `MODULAR_SERVICES_AUDIT_SERVICE_MODE` (and the equivalent per-version form,
`MODULAR_SERVICES_AUDIT_SERVICE_VERSIONS_2_URL`) into the dotted key at lookup time, and Henge's
own version-discovery scan checks for both forms directly — so a container orchestrator that only
offers env vars (no YAML/properties file, no CLI flags) can declare a version's mode/url/existence
purely via `MODULAR_SERVICES_<NAME>_VERSIONS_<VERSION>_*` env vars, with no local impl and no
mention in `--modular.serve` needed. If you'd rather set nested config as one blob instead of many
separate env vars, `SPRING_APPLICATION_JSON` (a single JSON-blob env var Boot unpacks into regular
dotted properties) is the Boot-idiomatic alternative — e.g.
`SPRING_APPLICATION_JSON='{"modular":{"services":{"audit-service":{"versions":{"2":{"mode":"internal-rest","url":"http://audit-v2:8080"}}}}}}'`.

### Two versions of a service side by side

The example app also ships a second `AuditService` implementation (`AuditServiceImplV2`,
prefixes events with `v2:`), reachable only via an explicit `@ServiceVersion` qualifier — see
`DemoController`. Both versions are embedded by default (no config needed, since neither overrides
the default `embedded` mode), so a single monolith run shows them coexisting with independent
state:

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar --server.port=8080
```

```bash
curl http://localhost:8080/api/greet/Carol        # -> "Hello, Carol!" (records into v1, the default)
curl http://localhost:8080/api/audit               # -> ["greeted:Carol"]
curl http://localhost:8080/api/audit/v2/hello       # -> ["v2:hello"] (records into v2 explicitly, independent state)
curl http://localhost:8080/api/audit                # -> ["greeted:Carol"], unaffected by the v2 call
```

To run only version 2 embedded here and treat version 1 as remote instead, no code changes are
needed — just config: `--modular.services.audit-service.versions.1.mode=internal-rest --modular.services.audit-service.versions.1.url=...`.

### A method that only exists from version 2 onward

`AuditService.getRecentEvents(int limit)` is `@AddedIn(2)`. Version 1's implementation
(`AuditServiceImpl`) never overrides it; version "2"'s (`AuditServiceImplV2`) does:

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar --server.port=8080
```

```bash
curl http://localhost:8080/api/audit/v2/hello
curl http://localhost:8080/api/audit/v2/world
curl http://localhost:8080/api/audit/v2/recent/2   # -> ["v2:hello","v2:world"] -- version 2 really implements it
curl -i http://localhost:8080/api/audit/recent/2    # -> 500 -- default (version 1) never overrode it
```

The 500 comes from `ServiceVersionUnsupportedException`, thrown by the method
`modular-processor` generated on `AuditServiceSkeleton`, naming exactly which version is required
— visible in the server log even though the HTTP response body itself is Spring Boot's generic
error JSON. (That 500 is `DemoController`'s own unhandled exception. Split, the internal `/_modular`
call itself answers `501 Not Implemented` — the exception carries `@ErrorStatus(501)` — and the caller
still gets `ServiceVersionUnsupportedException` reconstructed.)

## Configuration reference

Everything is read from Spring's `Environment`, so CLI flags, `application.yml`, environment variables and
`SPRING_APPLICATION_JSON` all work. A `modular.services.*` property that names no discovered service or isn't one of the
keys below (say `.mdoe`) fails startup instead of being silently ignored.

| Property | Default | Meaning |
|---|---|---|
| `modular.services.<name>.mode` | `embedded` | `embedded` or `internal-rest`, for every version of the service unless overridden below. |
| `modular.services.<name>.url` | — | Base URL of the process hosting the service; used when the mode is `internal-rest`. |
| `modular.services.<name>.versions.<n>.mode` / `.url` | inherit the service-level value | Per-version override; `<n>` is an integer. |
| `modular.serve` | unset | `name[@version]` entries, comma-separated or as a YAML list, naming what this process hosts; everything else discovered defaults to `internal-rest`. Names that match no `@ModularService` fail at startup. |
| `modular.remote-url-template` | unset | URL template (`{service}`, `{version}`; any other placeholder fails startup) used for any `internal-rest` service without an explicit `url`. |
| `modular.leases.<lease>.capacity` | — | Cluster-wide capacity of a resource that services claim shares of with `@RequiresLease`. A positive integer; required for every declared lease. |
| `modular.services.<name>.leases.<lease>` | — | How much of the lease one instance of the service claims. A positive integer, at most the capacity; required for every lease the implementation declares. |
| `modular.store.type` | `in-process` | Which `SystemEphemeralDatastore` holds this process's shared state (leases, service advertisements): `in-process`, or the type of an adapter on the classpath (`redis`, from `modular-redis`). Setting it while also defining a datastore bean fails startup. |
| `modular.store.redis.uri` | — | For `type=redis`: a Lettuce URI, e.g. `redis://host:6379/0`; `rediss://` for TLS, `redis://:password@host` for a password, options as query parameters (`?timeout=5s`). Required. |
| `modular.transport.retry.max-attempts` | `3` | Calls made at most per invocation, the first included; `1` turns retries off. See "Retries". |
| `modular.transport.retry.backoff` | `50ms` | Wait before each retry; same duration format as the timeouts, `0` retries at once. |
| `modular.transport.retry.on` | `connect,not-served` | Which failures are retried: `connect`, `not-served`, or both. |
| `modular.advertise.url` | unset | Base URL (`http://host:port`) other processes reach this one's `/_modular` at, published in its service advertisements. Unset: the advertisement says this process hosts a service but gives no address. |
| `modular.server.enabled` | `true` | Boot starter only: whether this process serves `/_modular/**` at all (a non-web application never does). |
| `modular.server.path-prefix` | `/_modular` | Path prefix of the dispatch endpoint, for both the server and the client side. Must start with `/` and not end with one. |
| `modular.transport.secret` | unset | Optional shared secret sent as `Modular-Internal-Secret` and required by the dispatcher; with Spring Security it becomes an authentication. |
| `modular.transport.connect-timeout` | `2s` | A bare number is milliseconds; `2s`/`500ms` and ISO-8601 (`PT2S`) work too. `0` means no timeout. |
| `modular.transport.read-timeout` | `10s` | Same format. |

## Leases: sharing a scarce resource

Every node can host every service, so a resource with a hard cap, like a database that accepts 200
connections, would see its pool count grow with the number of nodes. Declare what a service needs and
Henge keeps the cluster under the cap by not constructing the service on a node that can't get it:

```java
@ServiceVersion(value = OrderService.class, version = 1)
@RequiresLease("orders-db")
public class OrderServiceImpl implements OrderService {
    public OrderServiceImpl(Lease ordersDb) {
        config.setMaximumPoolSize(ordersDb.amount());   // size the resource FROM the lease
    }
}
```

```yaml
modular:
  leases:
    orders-db:
      capacity: 180          # cluster-wide; set below the real limit as a margin
  services:
    order-service:
      leases:
        orders-db: 20        # what one instance claims
```

All of a service's leases are acquired before its implementation is constructed, or none are. If
they are refused, the service is reached over `internal-rest` like any other remote one, which needs
an explicit `modular.services.<name>.url` (`modular.remote-url-template` can't be combined with
leases, since the template assumes every node behind the name hosts the service). A lease is held for
the life of the process and renewed on a heartbeat, so a crashed node gives its share back after the
lease's 30-second TTL.

What Henge does and doesn't do:

- **It keeps books; it never sees a connection.** Size the real resource from `Lease.amount()`, and
  keep the resource inside the service: a shared pool bean (or JPA, Flyway, ...) opens its connections
  whether or not the service was built here.
- **Over-allocating a lease fails startup, in every deployment.** A lease's capacity is cluster-wide
  and each leased service has to run somewhere, so if one instance of each service that declares it
  claims more than the capacity, no way of splitting the jar can host them all. That's checked at boot,
  from configuration alone, over every leased implementation on the classpath whose capacity and
  amount are configured (whether or not this process hosts it, so give every process the same
  config), and fails naming each service and amount.
- **The cap is soft.** Shared state lives in a `SystemEphemeralDatastore` (in-process by default, so a
  single node always grants what fits). To share it across nodes, add `modular-redis` and set
  `modular.store.type=redis` with `modular.store.redis.uri`, or define your own bean of that type
  (not both). See `docs/design/self-orchestration.md`.

## Service advertisements

Once a process is fully started it advertises every service version it actually hosts on the
`SystemEphemeralDatastore`, as `adv:<service>@<version>` with its `modular.advertise.url`: a leased
service only if its lease was granted, never one that's `internal-rest` here. The entry is renewed
every 10 seconds and expires after 30, so a crashed process disappears on its own; a graceful stop
withdraws first.

A caller finds a host the same way. For an `internal-rest` service the url is resolved in order: an
explicit `modular.services.<name>.url`, then `modular.remote-url-template`, then **whoever advertises
it**. Callers cache the advertisers per service version and re-read them at most every 10 seconds, so
the datastore sees a read per service per interval however many calls are made, and calls rotate
through everything advertised. If a read comes back empty from a *different* storage (the store was
restarted and hosts haven't re-advertised yet) the previous answer is kept one more interval; an empty
read from the same storage is believed. With nobody advertising, the call fails saying so. A call that
reaches a host that has just died is retried on the next advertised host; see "Retries".

### Retries

`internal-rest` retries only a call that provably **never ran** on the remote, so repeating it is safe
for every method, whatever it does, and needs no idempotency declaration:

- **`connect`**: the connection couldn't be made (refused, unknown host, no route, a connect
  timeout).
- **`not-served`**: any `404`. **A `404` means nothing happened**: the process doesn't serve that
  service version (it never did, withdrew it, or wasn't granted its lease), or it isn't a Henge
  process at all, like a proxy or load balancer's default backend. It is also what an
  `@ErrorStatus(404)` exception thrown by a method answers with, so such an exception must be thrown
  before the method has had any effect: "not found" is fine, a half-finished write is not (give that
  one another status). If you'd rather not retry a legitimate "not found", set
  `modular.transport.retry.on=connect`.

A retry goes to the next advertised host where there is one, and otherwise to the same url, which is
what a load balancer or a Kubernetes Service in front of several processes wants. A host that just
failed isn't offered again until the advertisements are next read (unless it's the only one).
Anything that may have started is never retried: a read timeout, a reset, a 5xx, any other exception
the implementation threw. Whether *that* is safe is a question about the method, and has no answer
here yet. When the attempts run out, a failed connection or a plain `404` says so
(`... (gave up after 3 attempts)`, a `RemoteServiceException`), and an `@ErrorStatus(404)` exception
is thrown as it came, so a caller still catches its own type.

### Finding each other through Redis

The same jar, two processes, and no address configured between them: each only says where *it* can
be reached, and they share a datastore.

```bash
docker run -d --name henge-redis -p 6379:6379 redis:8

STORE="--modular.store.type=redis --modular.store.redis.uri=redis://localhost:6379"

java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar --server.port=8082 \
  --modular.serve=audit-service --modular.advertise.url=http://localhost:8082 $STORE

java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar --server.port=8080 \
  --modular.serve=greeting-service --modular.advertise.url=http://localhost:8080 $STORE
```

```bash
curl http://localhost:8080/api/greet/Alice   # -> "Hello, Alice!" (greeting found audit through Redis)
curl http://localhost:8082/api/audit         # -> ["greeted:Alice"], recorded by the other process
```

Stop the audit process and it withdraws its advertisements at once; start it again, on any port, and
the greeting process picks it up within one refresh interval, with nothing reconfigured.

## Using this without Spring Boot

`modular-spring-boot-starter` is a convenience layer, not a requirement — the actual mechanism
(discovery, bean-definition wiring, the internal-rest transport, the dispatcher controller) lives
in `modular-spring`, which only depends on plain Spring Framework (`spring-context`, `spring-web`).
A plain-Spring consumer does three things Boot users get for free:

- **`@Import` the configuration explicitly.** `modular-spring-boot-starter`'s autoconfiguration
  unconditionally wires `ModularTransportConfiguration` (the `ServiceTransport` and
  `ModularProperties` beans needed to *call* other services) and, unless `modular.server.enabled=false`,
  `ModularDispatcherConfiguration` (the controller needed to *serve* embedded ones). Without Boot,
  import them yourself — `ModularConfiguration` imports both at once, or import
  `ModularTransportConfiguration` alone if this process never serves any requests. "Should this
  process serve requests" becomes a code-level choice (which class you import) instead of a
  runtime property.
- **Spring Security isn't wired for you.** The dedicated `/_modular` chain described under "How it
  works" is part of the Boot starter. A plain-Spring application that uses Spring Security has to permit
  `POST {modular.server.path-prefix}/**` itself (stateless, CSRF off for that path), and — if it sets
  `modular.transport.secret` — decide for itself whether to authenticate it through Spring Security.
- **CLI-flag property parsing isn't automatic.** Boot turns `--modular.serve=...` into environment
  properties for free; plain Spring doesn't. Add a
  [`SimpleCommandLinePropertySource`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/env/SimpleCommandLinePropertySource.html)
  to the context's environment yourself if you want the same `--key=value` CLI convention.

`examples/example-plain-spring` demonstrates the minimal end of this — pure DI/location-transparency,
no HTTP — with `@EnableModularServices` plus an `AnnotationConfigApplicationContext`, no
`SpringApplication` anywhere:

```bash
./gradlew :examples:example-plain-spring:run
```

```
Hello, plain Spring!
Audit trail: [greeted:plain Spring]
```

For the HTTP-serving end (embedded Tomcat + `DispatcherServlet`, one process dispatching to
another over `/_modular/**`, entirely Boot-free), see `modular-spring`'s
[`ModularDispatchPlainSpringTest`](modular-spring/src/test/java/digital/demilich/henge/spring/ModularDispatchPlainSpringTest.java).

## Not in v1

Deliberately out of scope for now, to keep the core mechanism small and correct:

- Health-aware routing and load-weighted host choice. Hosts find each other through advertisements on
  the shared datastore (see "Service advertisements"), and a host that stops renewing drops out
  after its TTL, but calls simply rotate over whatever is advertised: nothing measures a host's
  health or load, and there is no circuit breaking. `--modular.serve` + `--modular.remote-url-template`
  (see "Fitting into an existing orchestrator") still lets the binary lean on an orchestrator's own
  discovery (k8s DNS, Consul DNS, ...) instead.
- Additional transports (e.g. gRPC) — more than a `ServiceTransport` implementation: the serving side and the
  bean wiring are REST-specific too, so this is a design exercise rather than a drop-in.
- mTLS between internal services — `/_modular/**` is expected to sit behind a network boundary
  (VPC / service mesh), not the public internet; the optional `modular.transport.secret` (see
  "How it works") is the only in-process protection built so far. (A separate listen port for `/_modular` was considered
  and deliberately rejected, not deferred — see "How it works": `/_modular` was never meant to be
  internet-facing, so splitting it onto its own port doesn't solve a problem this framework
  actually has.)
- Retrying a call that may have run, and circuit breaking, for `internal-rest`. (Retrying one that
  provably *didn't* run is built: see "Retries".)
- Async/streaming methods — calls are synchronous/blocking only.
- Overloaded methods on a `@ModularService` interface — rejected at compile time (RPC dispatch is by
  method name; use `@ServiceMethod(name = ...)` to disambiguate if you need two methods with the same
  name).

## Roadmap

What's actually planned next, roughly in priority order (as opposed to "Not in v1" above, which is
scope deliberately excluded rather than deferred):

- **Topology visibility.** The framework's entire value proposition is "config decides the
  topology" — right now the resolved result is invisible. Log a `service@version → mode → url`
  table at startup, and expose the same table from the Boot starter via an actuator endpoint.
- **Tracing/metrics propagation on the transport.** The hand-built `RestClient` bypasses Boot's
  observation instrumentation today, so traces stop dead exactly at the process boundary that
  matters most. Build it from Boot's auto-configured `RestClient.Builder` when available instead.
- **Shared-singleton detection.** Two services that both inject the same stateful singleton (a
  cache, a mutable holder bean) share one instance in the monolith and get silently independent
  copies the moment they're split — nothing surfaces this today. A startup-time bean-graph walk,
  heuristic and suppressible, would at least turn it into a loud warning instead of a silent
  production surprise.
- **Strict/isolated embedded mode — the flagship feature.** `modular.strict=true` would round-trip
  embedded calls through the same serialization internal-rest uses, so mutation bugs and
  non-serializable types reproduce on a laptop instead of after a production split. A further
  `isolated` tier would go all the way: each service gets its own child Spring context in dev, so
  shared in-memory state genuinely stops being shared, deterministically, with a debugger attached
  — the real answer to the shared-singleton problem above, not just a warning about it.
- **Publishing + CI**, and a **test slice** (`@ModularServiceTest`-style) that boots one service's
  module with its dependencies as strict-mode proxies once the isolation work above exists to build
  it on.

## License

[MIT](LICENSE)
