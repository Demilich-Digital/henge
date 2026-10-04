# Without Spring Boot

`henge-spring-boot-starter` is a convenience layer. The mechanism lives in `henge-spring`, which depends
only on Spring Framework (`spring-context`, `spring-web`). Without Boot, you do three things yourself.

**Import the configuration.** The starter wires `HengeTransportConfiguration` (what's needed to *call*
services) and, unless `henge.server.enabled=false`, `HengeDispatcherConfiguration` (what's needed to
*serve* them). Without Boot, import them: `HengeConfiguration` imports both, plus the topology endpoint;
`HengeTransportConfiguration` alone suits a process that serves nothing. Whether a process serves becomes
a choice in code instead of a property.

**Secure `/_henge` yourself, if you use Spring Security.** The dedicated chain described in
[Operating](guide/07-operating.md#with-spring-security) is part of the starter. Permit `POST
/_henge/**` (stateless, CSRF off for that path), and, with a secret, decide whether to authenticate it
through Spring Security.

**Supply configuration.** Boot reads `application.yml` and turns `--key=value` arguments into properties;
plain Spring doesn't. Add a `@PropertySource`, or a
[`SimpleCommandLinePropertySource`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/env/SimpleCommandLinePropertySource.html)
for the same command-line convention. The same goes for observability: declare the
[beans](reference/observability.md#switching-it-on) the starter would have.

## The example

`examples/shop-plain-spring` runs the shop's services, with their lease and rate limit, in an
`AnnotationConfigApplicationContext`, with no `SpringApplication` anywhere:

```java
@Configuration
@PropertySource("classpath:shop.properties")
@EnableHengeServices(basePackages = "digital.demilich.henge.examples.shop")
static class AppConfig {
}
```

```bash
./gradlew :examples:shop-plain-spring:run
```

```
Rope available: 20
Placed 75d0404c-...; rope available: 17
Cancelled it; rope available: 20
Sent to ada: 2 messages
```

It serves no HTTP. For a Boot-free process that serves and calls over `/_henge` (embedded Tomcat and a
`DispatcherServlet`), see `henge-spring`'s
[`HengeDispatchPlainSpringTest`](../henge-spring/src/test/java/digital/demilich/henge/spring/HengeDispatchPlainSpringTest.java).
