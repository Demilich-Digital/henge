# 1. Project setup

This guide builds a small shop, the one in [`examples/`](../../examples), and climbs Henge's ladder with
it: a monolith first, then the same jar split into services, then a cluster that shares state through a
fast ephemeral store. Every rung is optional. Stop at the one that solves your problem; the code you
wrote for the rung below doesn't change.

The shop has three services:

- **inventory**: stock, in a database, with a version 2 that adds a batch lookup
- **orders**: places and cancels orders, calling inventory and notifications
- **notifications**: messages to customers, rate limited per customer

## Three modules

A Henge application is three kinds of module, and the split between them is what keeps the services
separable later:

```
examples/
  shop-contracts/   the @HengeService interfaces, and the records and exceptions that cross them
  shop-services/    their @ServiceVersion implementations
  shop-app/         the Spring Boot application: the public API, configuration, main()
```

The app depends on the contracts at compile time and on the services only at runtime:

```kotlin
// shop-app/build.gradle.kts
dependencies {
    implementation(project(":examples:shop-contracts"))
    runtimeOnly(project(":examples:shop-services"))
    implementation(project(":henge-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

So the app *cannot* reference `InventoryServiceImpl`: the class isn't on its compile classpath. Nothing in
the Java language stops code from reaching past an interface to the class behind it, and that code works
fine until the day the service runs in another process. The build is where you stop it, on the first
day, for free.

A larger application grows this shape rather than replacing it: a contracts module and a services module
per team or per domain, and one or more app modules that assemble them.

## Getting Henge

> **TODO:** Henge is not yet published to a Maven repository. Publishing (with `maven-publish`, from CI)
> comes with the public repository. Until then, build against this repository: include it in your build
> as a Gradle composite build (`includeBuild`), or work inside it, as the examples do.

The modules you depend on:

| Module | Who needs it |
|---|---|
| `henge-core` | Contracts and services: the annotations, the immutable collections, the exceptions. Its only dependency is `spring-beans`. |
| `henge-processor` | Contracts and services, as an **annotation processor**: it checks the boundary rules at compile time and generates version skeletons. |
| `henge-spring-boot-starter` | The app. |
| `henge-redis` | The app, once you share state through Redis ([chapter 5](05-the-ephemeral-store.md)). |

Without Spring Boot, the app takes `henge-spring` instead; see [Without Spring Boot](../plain-spring.md).

## The annotation processor

Gradle doesn't pass annotation processors along with dependencies, so every module that compiles
`@HengeService` interfaces or `@ServiceVersion` implementations declares it itself:

```kotlin
// shop-contracts/build.gradle.kts
dependencies {
    api(project(":henge-core"))
    annotationProcessor(project(":henge-processor"))
}
```

```kotlin
// shop-services/build.gradle.kts
dependencies {
    api(project(":examples:shop-contracts"))
    annotationProcessor(project(":henge-processor"))
}
```

Leave it out and the code still compiles, but nothing checks it: the mistakes the processor exists to
catch become failures at startup, or once a service is split. If a version skeleton (chapter 3) is
missing, the processor isn't running on the contracts module.

## The application

```java
@SpringBootApplication
@EnableHengeServices(basePackages = "digital.demilich.henge.examples.shop")
public class ShopApp {

    public static void main(String[] args) {
        SpringApplication.run(ShopApp.class, args);
    }
}
```

`@EnableHengeServices` finds every `@HengeService` interface and `@ServiceVersion` implementation under
`basePackages`. They live in other modules' packages, not under the app's own, so name the root that
covers them.

Build it and run it:

```bash
./gradlew :examples:shop-app:bootJar
java -jar examples/shop-app/build/libs/shop-app-0.1.0-SNAPSHOT.jar
```

```bash
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customer": "ada", "items": [{"sku": "rope", "quantity": 2}]}'
curl 'localhost:8080/api/stock?sku=rope&sku=lantern'
```

That is one process, hosting all three services, calling each other with plain method calls. The
[next chapter](02-services-and-boundaries.md) writes them.

The tests in `shop-app` run the shop at every rung of this guide, as real processes:
`MonolithTest`, `SplitTest` and `SharedStoreTest`.
