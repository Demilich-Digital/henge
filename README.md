# Henge

Run this jar with no flags, and it's a monolith. Run the *exact same jar*, twice, each with one flag, and
it's two independently deployable services talking to each other over HTTP: nothing recompiled, nothing
rewritten, same `.jar` both times. Henge is what makes that true. Whether a service call happens
in-process or over the network is a deployment-time decision, not a design-time one, and API versions
live in the compiled binary instead of at the network boundary.

Henge is a Spring add-on, and a set of patterns for building a system that can grow, in complexity and in
scale, without being rewritten on the way. You define a service once, as a Java interface and a
versioned implementation. Configuration decides where it runs. The traps between one process and many
(shared mutable state, exceptions that change meaning over the wire, lockstep deploys, connection pools
that multiply past what a database allows) are caught on the first day, most of them by the compiler,
while fixing them is cheap.

## A ladder, not a leap

Every layer is opt-in, and every rung is a place to stop:

| Rung | You add | You get |
|---|---|---|
| 0. Monolith | `@HengeService` boundaries, the annotation processor | Monolith ergonomics, boundaries the compiler enforces, versions in the binary |
| 1. Split | Flags, your orchestrator's DNS, and a configured store | The same jar as independently deployed services, routed by Kubernetes (or whatever you run) |
| 2. Shared store | A fast ephemeral store (Redis) | Processes that find each other and fail over, with no addresses configured |
| 3. Shared limits | Leases, rate limits | Connection caps and rate limits that hold across the whole cluster |
| 4. Self-management | *Not built yet* | A cluster that decides its own topology, by emergent behavior rather than central control |

## Two minutes

The example is a small shop: orders, inventory and notifications. Build it (Java 21):

```bash
./gradlew :examples:shop-app:bootJar
JAR=examples/shop-app/build/libs/shop-app-0.1.0-SNAPSHOT.jar
```

As a monolith, every call between the services a method call:

```bash
java -jar $JAR
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customer": "ada", "items": [{"sku": "rope", "quantity": 2}]}'
```

The same jar as two services: inventory in one process, the storefront in another:

```bash
java -jar $JAR --server.port=8082 --henge.serve=inventory-service --henge.store.type=in-process
java -jar $JAR --server.port=8080 --henge.serve=order-service,notification-service \
  --henge.services.inventory-service.url=http://localhost:8082 --henge.store.type=in-process
```

A process that hosts only part of the services is one of several, and refuses to start without a store
configured, since the default in-process one is private to one process. `--henge.store.type=in-process`
says these two share nothing, which is true of this demo; a real deployment points them at a shared store
([chapter 5](docs/guide/05-the-ephemeral-store.md)).

```bash
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customer": "ada", "items": [{"sku": "chisel", "quantity": 1000}]}'
# 409 Conflict: inventory threw OutOfStockException in its process,
# and the storefront caught it by type in its own
curl 'localhost:8082/api/stock?sku=rope'
```

The code calling inventory is the same in both runs, and doesn't know which one it's in.

## Documentation

- **[Philosophy](docs/philosophy.md)**: why Henge is built this way, and where it's going
- **The guide**, building the shop one rung at a time:
  1. [Project setup](docs/guide/01-project-setup.md)
  2. [Services and boundaries](docs/guide/02-services-and-boundaries.md)
  3. [Versions](docs/guide/03-versions.md)
  4. [Splitting](docs/guide/04-splitting.md)
  5. [The ephemeral store](docs/guide/05-the-ephemeral-store.md)
  6. [Leases and rate limits](docs/guide/06-leases-and-rate-limits.md)
  7. [Operating](docs/guide/07-operating.md)
  8. [Channels](docs/guide/08-channels.md)
  9. [Scheduled jobs](docs/guide/09-scheduled-jobs.md)
- **[Gotchas](docs/gotchas.md)**: every trap on the way from one process to many, and how each is caught
- **Reference**: [configuration](docs/reference/configuration.md),
  [compile-time checks](docs/reference/compile-time-checks.md),
  [observability](docs/reference/observability.md), [wire protocol](docs/reference/wire-protocol.md),
  [modules](docs/reference/modules.md)
- **More**: [the ephemeral store's contract](docs/ephemeral-store.md), [internals](docs/internals.md),
  [without Spring Boot](docs/plain-spring.md), [scope and roadmap](docs/scope.md),
  [design: self-orchestration](docs/design/self-orchestration.md),
  [design: scheduled jobs](docs/design/scheduling.md)

## Building

```bash
./gradlew build
```

The Redis-backed tests, including the shop's `SharedStoreTest`, run against Redis in a container through
Testcontainers, so they need Docker. Without it they're skipped, unless the `CI` environment variable is
set, in which case the build fails instead of passing without having tested Redis.

## License

[MIT](LICENSE)
