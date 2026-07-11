# modular-spring

A Spring Boot add-on for building "modular services": define a service once as a Java interface
plus a `@ServiceVersion`-annotated implementation, and let deployment config — not code — decide
whether each service (and each *version* of it) runs in-process or is dispatched to over HTTP to
another process. One fat jar is both the full local-dev monolith and every individual microservice
it can be split into, switched purely via `--modular.services.<name>.mode=...` CLI flags or config.
Multiple implementations of the same interface can run side by side — each dependency pins which
version it wants, or gets the default automatically, so a version change never requires the whole
deployment to move in lockstep.

## Why this isn't just a "modulith"

The term "modulith" usually means a code-organization discipline: one deployable, forever,
internally organized into well-bounded modules (this is what Spring Modulith itself gives you).
That's not what this project does. modular-spring makes two specific, load-bearing claims that a
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
without a rewrite or a redeploy to move between them — is the actual goal. It's honestly not
fully there yet: modular-spring itself still tracks nothing about which instances are alive or
where (see "Not in v1" below) — `--modular.serve` and `--modular.remote-url-template` let the
binary lean on an existing orchestrator's own discovery (k8s DNS, Consul DNS, ...) rather than
requiring hand-configured hosts, but there's still no health-aware routing, retries, or auth on
the internal transport. The versioning-in-the-binary and deploy-time-topology mechanics are
solid; the operational maturity for scaling like a "real" microservice fleet isn't built yet.

## Concept

- **`@ModularService`** marks an *internal* service boundary interface. It is never used for a
  project's public-facing HTTP API — that stays plain Spring MVC (`@RestController`,
  `@GetMapping`, ...), fully developer-owned, and the framework never touches it.
- **`@ServiceVersion(value = TheInterface.class, version = "1")`** stands in for `@Service` on an
  implementation — the framework registers the bean itself, so don't also annotate it `@Service`/
  `@Component`. Multiple classes can implement the same interface as long as each declares a
  distinct version; they all coexist as separate beans in the same process.
- A dependency that doesn't care which version it gets just injects the interface normally —
  no annotation needed, resolves to the interface's `defaultVersion()` (default version) via
  Spring's `@Primary` mechanism. A dependency that needs a *specific* version puts the same
  `@ServiceVersion` annotation on the constructor parameter or field:

  ```java
  @ServiceVersion(value = GreetingService.class, version = "1")
  public class GreetingServiceImpl implements GreetingService {
      public GreetingServiceImpl(@ServiceVersion(value = AuditService.class, version = "1") AuditService auditService) { ... }
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
        url: http://localhost:8082 # required when mode=internal-rest
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
- **`@AddedIn("2")`** / **`@DeprecatedSince("3")`** on an interface method mark it as only
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
  value, matched positionally against the method's declared parameter types.
- Transport is pluggable behind the `ServiceTransport` SPI (`io.modular.core`). `internal-rest` is
  the only implementation today; a `grpc` transport can be added later without any change to
  `@ModularService` or generated proxies.
- The runtime wiring itself is just `BeanDefinitionRegistry` manipulation, `java.lang.reflect.Proxy`,
  and Spring's own `@Primary`/qualifier autowiring machinery — no bytecode generation there. See
  the Javadoc on
  [`ModularServiceRegistrar`](modular-spring-boot-starter/src/main/java/io/modular/spring/ModularServiceRegistrar.java)
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
    List<String> getEvents();

    @AddedIn("2")
    List<String> getRecentEvents(int limit);
}
```

For any `@ModularService` interface with at least one `@AddedIn`/`@DeprecatedSince` method, the
processor generates a companion abstract class, `{Interface}Skeleton`, in the same package, with a
throwing override of every such method. An implementation `extends {Interface}Skeleton` instead of
`implements {Interface}` directly, and only overrides the methods actually in range for its
declared version — everything else falls through to the generated stub, with zero hand-written
boilerplate:

```java
@ServiceVersion(value = AuditService.class, version = "1")
public class AuditServiceImpl extends AuditServiceSkeleton {
    // doesn't override getRecentEvents at all -- calling it throws
    // ServiceVersionUnsupportedException: "...AuditServiceImpl#getRecentEvents is not
    // supported by this implementation (requires version >= 2)"
}

@ServiceVersion(value = AuditService.class, version = "2")
public class AuditServiceImplV2 extends AuditServiceSkeleton {
    @Override
    public List<String> getRecentEvents(int limit) { ... } // version "2" actually supports it
}
```

The same processor also *validates* every `@ServiceVersion` implementation: if a method's
`[addedIn, deprecatedSince)` range includes the implementation's own declared version, it must be
genuinely overridden — silently relying on the generated throwing stub for a method the
implementation is actually supposed to support is a compile error, not a runtime surprise.

Two things worth knowing:
- Version strings used with `@AddedIn`/`@DeprecatedSince` (and the `@ServiceVersion` they're
  checked against) must be parseable as plain integers — that's how the processor orders
  versions to compute ranges. This is scoped to this feature only; dispatch elsewhere still
  matches version strings exactly and never needed ordering.
- Any module that compiles a `@ModularService` interface with versioned methods, or a
  `@ServiceVersion` implementation, needs `modular-processor` on its `annotationProcessor` (or
  `testAnnotationProcessor`) configuration explicitly — Gradle does not propagate annotation
  processors transitively. See `examples/example-contracts` and `examples/example-services`'s
  `build.gradle.kts` for the pattern.

## Modules

| Module | Contents |
|---|---|
| `modular-core` | `@ModularService`, `@ServiceVersion`, `@ServiceMethod`, `@AddedIn`, `@DeprecatedSince`, the `ServiceTransport` SPI, `RemoteServiceException`, `ServiceVersionUnsupportedException`. The only Spring dependency in this module is `spring-beans`, for `@ServiceVersion`'s `@Qualifier` meta-annotation — nothing else. |
| `modular-processor` | The `@AddedIn`/`@DeprecatedSince` annotation processor: generates `{Interface}Skeleton` classes and validates `@ServiceVersion` implementations against them. Depends only on `modular-core` — no Spring. |
| `modular-spring-boot-starter` | `@EnableModularServices`, the bean-wiring registrar, the internal-rest transport, the dispatcher controller, autoconfiguration. |
| `examples/example-contracts` | `GreetingService` / `AuditService` — the two `@ModularService` interfaces used by the demo; `AuditService` has an `@AddedIn("2")` method. |
| `examples/example-services` | Their `@ServiceVersion` implementations, including a second `AuditService` version to demonstrate multi-version wiring and the generated-skeleton mechanism. |
| `examples/example-app` | One Spring Boot application tying it together, runnable as the monolith or as either half of a split deployment. |

## Quickstart

```bash
./gradlew build
```

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
  default. Leaving it unset (the default) changes nothing — today's "everything embedded unless
  configured otherwise" behavior is exactly as before.
- **`--modular.remote-url-template=http://{service}.default.svc.cluster.local:8080`** fills in a
  `url` for anything that ends up `internal-rest` without one, substituting `{service}` with the
  service's name — matching whatever DNS convention an orchestrator already hands you for free (a
  Kubernetes `Service`, an ECS Cloud Map namespace, Consul DNS, ...).

Precedence, per service: an explicit `modular.services.<name>.url` always wins (the escape hatch
for anything that doesn't fit the convention) → else derived from `--modular.remote-url-template`
→ else the call fails, naming exactly which config key is missing. Mode works the same way:
explicit `modular.services.<name>.mode` always wins → else `embedded` if `--modular.serve` names
this (service, version) or `--modular.serve` is empty → else `internal-rest`. Declaring a service
in `--modular.serve` while also explicitly setting its mode to `internal-rest` is a contradiction
and fails fast at startup rather than silently picking one.

There's no real DNS on localhost, but the fallback itself is fully exercisable there too —
equivalent to terminal 2 above, just via the template instead of an explicit `.url`:

```bash
--modular.serve=greeting-service --modular.remote-url-template=http://localhost:8082
```

This is deliberately *not* service discovery — modular-spring never tracks "who is currently
running where." It just makes the binary a well-behaved, single-purpose replica so whatever's
already scheduling and load-balancing containers can do that job, instead of this framework
reinventing it. See "Why this isn't just a 'modulith'" above.

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

`AuditService.getRecentEvents(int limit)` is `@AddedIn("2")`. Version "1"'s implementation
(`AuditServiceImpl`) never overrides it; version "2"'s (`AuditServiceImplV2`) does:

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar --server.port=8080
```

```bash
curl http://localhost:8080/api/audit/v2/hello
curl http://localhost:8080/api/audit/v2/world
curl http://localhost:8080/api/audit/v2/recent/2   # -> ["v2:hello","v2:world"] -- version 2 really implements it
curl -i http://localhost:8080/api/audit/recent/2    # -> 500 -- default (version "1") never overrode it
```

The 500 comes from `ServiceVersionUnsupportedException`, thrown by the method
`modular-processor` generated on `AuditServiceSkeleton`, naming exactly which version is required
— visible in the server log even though the HTTP response body itself is Spring Boot's generic
error JSON.

## Not in v1

Deliberately out of scope for now, to keep the core mechanism small and correct:

- Service discovery/registry — `--modular.serve` + `--modular.remote-url-template` (see "Fitting
  into an existing orchestrator") let the binary lean on whatever discovery an existing
  orchestrator already provides (k8s DNS, Consul DNS, ...), but modular-spring itself still tracks
  nothing about which instances are actually alive or where — no health-aware routing, no dynamic
  membership.
- A `grpc` `ServiceTransport` implementation (the SPI is ready for it).
- Auth / mTLS between internal services — `/_modular/**` endpoints are unauthenticated and are
  expected to sit behind a network boundary (VPC / service mesh), not the public internet.
- Retries, load balancing, circuit breaking for `internal-rest`.
- Async/streaming methods — calls are synchronous/blocking only.
- Overloaded methods on a `@ModularService` interface (RPC dispatch is by method name; use
  `@ServiceMethod(name = ...)` to disambiguate if you need two methods with the same name).
