# modular-spring

A Spring Boot add-on for building "modular services": define a service once as a Java interface
plus a `@ServiceVersion`-annotated implementation, and let deployment config — not code — decide
whether each service (and each *version* of it) runs in-process or is dispatched to over HTTP to
another process. One fat jar is both the full local-dev monolith and every individual microservice
it can be split into, switched purely via `--modular.services.<name>.mode=...` CLI flags or config.
Multiple implementations of the same interface can run side by side — each dependency pins which
version it wants, or gets the default automatically, so a version change never requires the whole
deployment to move in lockstep.

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

- Callers always just `@Autowired` the interface (optionally qualified with `@ServiceVersion`).
  They never know or care which mode is in effect — that's the location transparency the whole
  framework exists for.

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
- No bytecode generation, no annotation processing — just `BeanDefinitionRegistry` manipulation,
  `java.lang.reflect.Proxy`, and Spring's own `@Primary`/qualifier autowiring machinery. See the
  Javadoc on
  [`ModularServiceRegistrar`](modular-spring-boot-starter/src/main/java/io/modular/spring/ModularServiceRegistrar.java)
  for the exact bean-wiring mechanics.

## Modules

| Module | Contents |
|---|---|
| `modular-core` | `@ModularService`, `@ServiceVersion`, `@ServiceMethod`, the `ServiceTransport` SPI, `RemoteServiceException`. The only Spring dependency in this module is `spring-beans`, for `@ServiceVersion`'s `@Qualifier` meta-annotation — nothing else. |
| `modular-spring-boot-starter` | `@EnableModularServices`, the bean-wiring registrar, the internal-rest transport, the dispatcher controller, autoconfiguration. |
| `examples/example-contracts` | `GreetingService` / `AuditService` — the two `@ModularService` interfaces used by the demo. |
| `examples/example-services` | Their `@ServiceVersion` implementations, including a second `AuditService` version purely to demonstrate multi-version wiring. |
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

Terminal 1 — hosts `AuditServiceImpl`, treats `greeting-service` as remote (unused in this demo,
but pointed somewhere so a stray call doesn't silently misconfigure to itself):

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar \
  --server.port=8082 \
  --modular.services.greeting-service.mode=internal-rest \
  --modular.services.greeting-service.url=http://localhost:1
```

Terminal 2 — hosts `GreetingServiceImpl`, dispatches `audit-service` calls to terminal 1 over HTTP:

```bash
java -jar examples/example-app/build/libs/example-app-0.1.0-SNAPSHOT.jar \
  --server.port=8080 \
  --modular.services.audit-service.mode=internal-rest \
  --modular.services.audit-service.url=http://localhost:8082
```

```bash
curl http://localhost:8080/api/greet/Bob   # -> "Hello, Bob!" (crossed processes for the audit call)
curl http://localhost:8082/api/audit       # -> ["greeted:Bob"], recorded by the *other* process
```

Same code, same jar, two independently deployable processes — the only difference is three CLI
flags.

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

## Not in v1

Deliberately out of scope for now, to keep the core mechanism small and correct:

- Service discovery/registry (Eureka, Consul, DNS-based) — URLs are static config today.
- A `grpc` `ServiceTransport` implementation (the SPI is ready for it).
- Auth / mTLS between internal services — `/_modular/**` endpoints are unauthenticated and are
  expected to sit behind a network boundary (VPC / service mesh), not the public internet.
- Retries, load balancing, circuit breaking for `internal-rest`.
- Async/streaming methods — calls are synchronous/blocking only.
- Overloaded methods on a `@ModularService` interface (RPC dispatch is by method name; use
  `@ServiceMethod(name = ...)` to disambiguate if you need two methods with the same name).
