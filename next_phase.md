# Next phase: execution plan

Ranked, dependency-ordered plan for the fixes identified in the 2026-07-12 critical review
(measuring the project against its own thesis — location transparency + versioning-in-the-binary —
and against Spring Modulith as the competing niche). Each item states the problem, the fix, and
how we know it's done. Work top to bottom; phases are ordered so that nothing later forces a
re-touch of something earlier.

Deliberately **not** in this plan (unchanged from README "Not in v1"): retries / circuit breaking /
load balancing, a grpc transport, async/streaming methods, full service discovery, mTLS. The
security items in Phase 3 are scoped to closing the *default-exposure* problem, not to building
real authn/authz.

---

## Phase 0 — Rename to `com.demilich.horde` — ✅ DONE (2026-07-12)

Do this first: every later phase touches files that would otherwise need a second pass.

**Executed as scoped below**, with one decision made at execution time: module directories
(`modular-core`, `modular-spring`, `modular-spring-boot-starter`, `modular-processor`) were
**kept as-is**, not renamed to `horde-*` — smaller diff for this pass, revisit later if desired.
`rootProject.name` is now `"horde"`; README title is `# Horde` and brand-level prose says "Horde"
while the module table / module-name references still correctly say `modular-*` (that's the
actual module name, not stale branding). Verified: `./gradlew clean test` green, both README demo
scenarios (monolith + two-terminal split) re-run manually and working.

Scope (settled decision — do not widen):
- Gradle `group` `io.modular` → `com.demilich.horde`; `rootProject.name` accordingly.
- Package roots `io.modular.core` / `io.modular.spring` / `io.modular.processor` /
  `io.modular.examples.*` → `com.demilich.horde.…` equivalents.
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (fully
  qualified class name changes with the package).
- README and all Javadoc references.
- **Annotation names do NOT change** (`@ModularService`, `@ServiceVersion`, `@AddedIn`,
  `@DeprecatedSince`, `@EnableModularServices`) — they describe function, not brand. Module
  directory names (`modular-core` etc.) may be renamed to `horde-*` as part of this, or left —
  decide at execution time, but whatever is chosen, `settings.gradle.kts` and the README module
  table must agree.
- The processor's `@SupportedAnnotationTypes` strings are fully-qualified — they must be updated
  or the processor silently stops matching. Add/keep a processor test that fails if the annotation
  FQN and the supported-types string drift.

**Done when:** `./gradlew build` is green, `grep -r "io\.modular" --include='*.java' --include='*.kts' --include='*.imports' .`
(excluding build dirs) returns nothing, and the two-terminal split-process demo from the README
still works.

---

## Phase 0.5 — Rebrand `com.demilich.horde` → `digital.demilich.henge` — ✅ DONE (2026-09-17)

"Horde" was a working name; "Henge" replaces it as the long-term brand — a henge is built from
individual megaliths (monoliths, plural) arranged into one structure, which is a more accurate
metaphor for this project's actual thesis than "horde" (a battle term) ever was, and directly
answers the README's own "why this isn't just a modulith" framing without touching the trademarked
word itself. `digital.demilich.henge` groupId chosen over `com.demilich.henge` because
`demilich.digital` is a domain the author already owns, clearing that namespace outright.

Same scope as Phase 0, mechanically: Gradle `group` `com.demilich.horde` → `digital.demilich.henge`,
`rootProject.name` `"horde"` → `"henge"`, package roots (`com.demilich.horde.*` →
`digital.demilich.henge.*`) across every module and example, the AutoConfiguration `.imports` file,
and README brand prose/title. `HordeCollectionsModule` (a brand-derived class name, unlike the
function-derived annotation names) renamed to `HengeCollectionsModule`. As with Phase 0, module
directory names (`modular-core` etc.) and annotation names (`@ModularService`, `@ServiceVersion`,
...) are unchanged by design — same reasoning as before, not revisited.

This document's own historical entries (Phase 0's `com.demilich.horde` mentions, Phase 2's
`HordeCollectionsModule` mention) are deliberately **left unrenamed** — they describe what was
literally true in this repo at the time those phases shipped, not the current state. Only the one
genuinely forward-looking mention (the "Publishing/CI" follow-on, groupId) was updated to
`digital.demilich.henge`.

**Done when:** `./gradlew build` is green, `grep -ri "horde" --include='*.java' --include='*.kts' --include='*.md' --include='*.imports' .`
(excluding build dirs) returns nothing outside this document's own historical entries above.

---

## Phase 1 — Correctness defects (small, independent, do in any order) — ✅ DONE (2026-07-12)

All six items landed together, each with its own tests; full `./gradlew clean build` green
(unit+integration tests across `modular-core`/`modular-processor`/`modular-spring`/
`modular-spring-boot-starter`/examples) and the README's two-terminal split-process demo re-run
manually and still working. Judgment calls made at execution time, flagged for anyone touching
this area later:
- **1.1/1.2 shared a fix:** new package-private `ModularTransportSupport` factory builds the
  transport's `ObjectMapper`/`RestClient` as plain (non-bean) internal details, used identically by
  `ModularTransportConfiguration`, `ModularDispatcherConfiguration`, and the starter's
  `ModularAutoConfiguration` — chosen over the qualifier-bean alternative because dispatcher-only
  and transport-only configs must each keep working standalone (no guaranteed bean to qualify
  against from the other side). This matches 3.4's own forward-looking note ("post-1.1 this stays
  an internal detail, just sourced from the builder"), so 3.4 should extend `ModularTransportSupport`
  rather than reintroducing a bean.
- **1.2 timeouts are plain milliseconds** (`modular.transport.connect-timeout`/`read-timeout`, long
  values, e.g. `2000`), not Boot-style duration strings (`2s`) — avoids depending on
  `ApplicationConversionService` (Boot-only; plain Spring's default `ConversionService` has no
  built-in String→Duration converter), keeping the property plain-Spring-compatible.
- **1.4's fix also changed bean-name derivation**: bean names are now `{name}-{version}` (the
  already-override-aware service name) instead of `{rawSimpleName}-{version}` — the old scheme
  meant the documented remedy (`@ModularService(name = ...)`) wouldn't actually have resolved a
  bean-name collision even after fixing the service-name one. Bean names are a pure Spring-registry
  implementation detail (never on the wire), so this is safe; no test asserted the old format.
- **1.6 diverged from the plan's literal fix.** Investigated the actual defect (see
  `ModularPropertiesTest`) rather than assuming Boot's `Binder` was required: plain Spring's own
  `SystemEnvironmentPropertySource` *already* relaxed-matches hyphens/dots in a single-key
  `Environment.getProperty(...)` lookup — verified by reading its bytecode and confirming with a
  throwaway probe — so `resolveMode`/`resolveUrl` needed zero changes. The one real bug was
  `explicitVersions()`'s enumeration-based prefix scan, which only ever saw literal dotted keys and
  so silently missed any version declared purely via an OS env var (env-var property *names*
  enumerate in raw `MODULAR_SERVICES_...` form; the dotted-form translation only happens lazily
  inside a single-key `getProperty` call, never during enumeration). Fixed by also matching the
  env-var-style prefix during that scan — no Boot dependency needed, works identically in Boot and
  plain-Spring, and is *more* capable than the plan's own "Done when" bar (which only asked for a
  single `mode` lookup to work, not full version discovery from env vars alone). Deliberately did
  **not** add a Boot-`Binder`-based path on top: `ModularProperties` is constructed manually inside
  `ModularServiceRegistrar` (an `ImportBeanDefinitionRegistrar`, no DI seam for the starter to
  override), and the module must stay Boot-free — see 3.4's note above for the established pattern
  if a real seam is needed later.

### 1.1 Stop leaking `ObjectMapper` / `RestClient` beans into the application context
**Problem:** `ModularTransportConfiguration` exposes a bare `new ObjectMapper()` as an unqualified
bean. In a Boot app this can make `JacksonAutoConfiguration` back off, silently stripping
`spring.jackson.*` config / `JavaTimeModule` from the *application's* own MVC JSON. Separately,
both dispatcher configurations inject an **unqualified** `ObjectMapper`, so the server side of the
wire can end up on a different mapper than the client side.
**Fix:** Make the transport's mapper and `RestClient` internal implementation details (constructed
inside the config, not exposed as beans), or expose them under a qualifier and inject them only by
that qualifier — including in `ModularDispatcherConfiguration` and the starter's
`ModularAutoConfiguration`. Client transport and dispatcher controller must provably share one
mapper configuration.
**Done when:** a Boot test proves (a) an app using the starter still gets Boot's customized
primary `ObjectMapper` (e.g. `spring.jackson.*` respected), and (b) a user-defined `ObjectMapper`
bean causes no ambiguity failure; existing dispatch integration tests stay green.

### 1.2 Default timeouts on the internal-rest transport
**Problem:** `RestClient.builder().build()` has infinite connect/read timeouts — one hung remote
service pins caller threads forever and cascades.
**Fix:** Default connect + read timeouts (e.g. 2s / 10s), configurable via
`modular.transport.connect-timeout` / `modular.transport.read-timeout`. Applies to the plain-Spring
config and the starter alike.
**Done when:** a test against a deliberately stalling endpoint fails fast with
`RemoteServiceException` naming the timeout, within the configured bound.

### 1.3 No silent swallow in classpath discovery
**Problem:** `ModularServiceRegistrar.resolveClass` catches `ClassNotFoundException | LinkageError`
and returns `null` — a service that fails to load silently vanishes (worst case under DevTools'
restart classloader, where *everything* can vanish).
**Fix:** Fail fast with a clear message naming the class and the underlying error (these classes
were just found by classpath scanning; failing to load them is never a benign condition). Also
resolve against the appropriate bean classloader rather than the registrar's own.
**Done when:** a test with an unloadable candidate asserts a descriptive startup failure instead
of silence.

### 1.4 Fail fast on name collisions
**Problem:** Both the bean name (`{shortName}-{version}`) and the default service name derive from
the interface's **simple** name — two `@ModularService` interfaces with the same simple name in
different packages collide silently or fail obscurely.
**Fix:** During discovery, detect two interfaces resolving to the same service name (and/or bean
name) and throw with both FQNs and the remedy (`@ModularService(name = ...)`).
**Done when:** a test with two same-simple-name interfaces asserts the descriptive failure.

### 1.5 `{version}` substitution in `remote-url-template`
**Problem:** The template only substitutes `{service}`; the per-version split the README showcases
can't use the template at all.
**Fix:** Also substitute `{version}` in `InternalRestTransport.resolveFromTemplate` (which needs
the version passed in). `{service}`-only templates keep working unchanged.
**Done when:** unit tests cover both template forms; README's orchestrator section mentions
`{version}`.

### 1.6 Environment-variable configuration story
**Problem:** `ModularProperties` reads exact keys via `Environment.getProperty`, bypassing Boot's
relaxed binding — `modular.services.audit-service.mode` is unreachable from an env var (hyphens),
which collides with the Kubernetes positioning.
**Fix (two steps):** (a) immediately: document the `SPRING_APPLICATION_JSON` workaround in the
README's orchestrator section; (b) properly: when Boot's `Binder` is on the classpath, use it for
`modular.*` lookup so relaxed binding works, keeping the current direct-`Environment` path as the
plain-Spring fallback. Keep `ModularProperties`'s public surface unchanged.
**Done when:** a starter test binds a service mode from a `SystemEnvironmentPropertySource`-style
property name; plain-Spring tests unchanged.

---

## Phase 2 — Compile-time guarantees (the cheap parts of the thesis) — ✅ DONE (2026-07-13)

All three items landed together; full `./gradlew build` green across every module. One scope
decision made at execution time, before writing any processor code:

- **2.3 was widened from a blocklist to an allowlist, deliberately.** The plan as written only
  rejected `@jakarta.persistence.Entity`-annotated types. Investigated whether to go further and
  concluded a positive rule is strictly stronger and no more code: `@ModularService` boundary
  types must be provably immutable (records/enums/primitives/well-known immutable JDK types, or
  `ImmutableList`/`ImmutableSet`/`ImmutableMap`/`Optional` thereof, recursively), which subsumes
  the `@Entity` case for free (a JPA entity can never be a record) while also catching plain
  mutable POJOs, which the blocklist would have missed entirely. Kept a JPA-entity-specific error
  message as a special case of the generic rejection, for a clearer remedy.
- **New scope beyond the plan, decided mid-implementation:** plain `java.util.List`/`Set`/`Map`
  don't actually guarantee immutability (Jackson deserializes to mutable `ArrayList`/`HashMap`;
  nothing stops post-return mutation of a reference held from an embedded call), so they are
  *not* in the allowlist. Added three new genuinely-immutable types to `modular-core`
  (`ImmutableList`/`ImmutableSet`/`ImmutableMap`, backed by `List.copyOf`/etc., mutators throw
  `UnsupportedOperationException`) as the accepted collection boundary types instead, plus a
  Jackson deserialization module (`HordeCollectionsModule` in `modular-spring`) since these types
  don't fit Jackson's default construct-then-`add()` deserialization shape — kept out of
  `modular-core` so that module stays Jackson-free, registered into
  `ModularTransportSupport.objectMapper()` so both ends of the wire agree. Guava's
  `ImmutableList`/`ImmutableSet`/`ImmutableMap` are also accepted, matched by FQN string with no
  actual Guava dependency added (same mechanism as the `@Entity` check) — a consumer already on
  Guava doesn't need a second immutable-collection type. `examples/example-contracts`'s
  `AuditService` (previously `List<String>`) and its two impls were updated to match; proved with
  a real HTTP round-trip in `ModularDispatchPlainSpringTest` (`EchoService.upperCaseAll`), not
  just a Jackson-module unit test.
- **2.2 executed exactly as scoped**, no surprises: `example-app`'s `build.gradle.kts` now
  declares `implementation(example-contracts)` + `runtimeOnly(example-services)`; `DemoController`
  already only referenced interface types, so it compiled unchanged.

### 2.1 Reject checked exceptions on `@ModularService` methods
**Problem:** Embedded calls propagate checked exceptions; the remote path wraps them — a silent
semantic divergence. Reconstruction is RuntimeException-only *by design*, so make the constraint
visible instead of latent.
**Fix:** `ServiceVersionProcessor` (or a sibling check in the same processor) emits a compile
error for any `@ModularService` interface method declaring a checked exception in `throws`,
message explaining the transport rationale and suggesting an unchecked wrapper.
**Done when:** processor tests cover the error case and the clean case; README documents the rule
under the location-transparency discussion.

### 2.2 Compiler-enforced boundary pattern: `runtimeOnly` impls
**Problem:** Nothing stops a consumer from referencing an impl class directly — the monolith
builds, the split breaks. This is the gap vs. Spring Modulith's `verify()`.
**Fix:** (a) Change `examples/example-app/build.gradle.kts` to
`implementation(example-contracts)` + `runtimeOnly(example-services)` — `DemoController` already
only touches interfaces, so this should compile as-is and now *proves* the boundary. (b) Document
this as **the** recommended consumption pattern in the README (a short "enforcing the boundary"
section). (c) Stretch (optional, may defer): a processor warning when a class references a
`@ServiceVersion`-annotated type that isn't itself — catches same-module violations the Gradle
split can't.
**Done when:** example-app builds with impls off the compile classpath and both README demo
scenarios still run.

### 2.3 Reject persistence entities on `@ModularService` boundaries
**Problem (shared state, part 1):** JPA entities in service signatures work embedded (attached,
lazy-loadable) and break split (serialization of lazy proxies, detached-state surprises). More
broadly: state shared through the persistence layer must not leak through service contracts.
**Fix:** `ServiceVersionProcessor` emits a compile error when a `@ModularService` method
parameter or return type is annotated `@jakarta.persistence.Entity` (matched by annotation FQN
string — no JPA dependency added), message pointing at the DTO-boundary rule. Alongside it, add
the **state-ownership doctrine section** to the README that this check (and 3.5) enforces: state
is owned by exactly one `service@version` and reached only through its interface; DTOs, never
entities, at boundaries; embedded co-location is a performance detail, never a semantic. Note the
reframing that motivates the rule: in-memory shared state is already broken by *replication*,
before any split — the split just makes the latent bug fire deterministically.
**Done when:** processor tests cover entity-in-signature (error) and DTO (clean) cases; README
doctrine section exists and 2.1/2.3 both link to it.

---

## Phase 3 — Operational table stakes

### 3.1 Close the default-exposure problem on `/_modular/**` — ✅ DONE (2026-07-13), scoped down

**Problem:** The dispatcher is unauthenticated, enabled by default, and shares `server.port` with
the public API — the default monolith exposes every internal method publicly and can't even be
firewalled separately.

**Scope decision made at execution time, on request: dropped the separate-port sub-fix
entirely**, not deferred. The plan's premise — a public "frontend" API and `/_modular` sharing one
port, needing to be split apart by this framework — doesn't hold: `/_modular` was never intended
to be externally reachable at all, in the "same port as something the internet talks to" sense.
The actual expectation is that a deployer puts their own public-facing API on whatever port/process
boundary they choose (their own choice entirely, nothing to do with `modular.server.port`), and
`/_modular` sits behind a network boundary regardless — a separate listen port would have added
real complexity (a second embedded Tomcat connector, since this project doesn't depend on
actuator's management-port machinery) for a problem that isn't actually this framework's to solve.
Shipped scope is the two sub-fixes that do carry their weight:

- **Shared-secret header**: `modular.transport.secret` — when set, the transport sends it as a
  `Modular-Internal-Secret` header (`InternalRestTransport`) and the dispatcher requires it via
  `MessageDigest.isEqual` (constant-time; `403` otherwise), enforced in
  `ModularDispatcherController.requireValidSecret`. Wired through both the plain-Spring path
  (`ModularDispatcherConfiguration`, which builds its own `ModularProperties` from the injected
  `Environment` to stay standalone-capable) and the Boot starter (`ModularAutoConfiguration`,
  which already has a guaranteed `ModularProperties` bean via its unconditional `@Import`).
- **Startup warning**: fires from `ModularDispatcherController`'s constructor (single source of
  truth — both configuration classes route through it) whenever no secret is configured, via
  `commons-logging`'s `LogFactory` (already a transitive dependency of every `spring-*` module, so
  this needed nothing new and stays plain-Spring-compatible). Verified at runtime, not just by
  test: booted the actual `example-app` jar with and without `--modular.transport.secret` and
  confirmed the warning appears/is silent as expected, and that a real HTTP call is 403'd without
  the header and 200s with it.

Integration test coverage: `ModularDispatchPlainSpringTest` (`matchingSecretIsAccepted`,
`missingOrWrongSecretIsRejected`, plain-Spring/Tomcat) and
`ModularServiceRemoteDispatchIntegrationTest` (`matchingTransportSecretIsAccepted`,
`missingTransportSecretIsRejected`, Boot starter) — both prove the 403 surfaces client-side as
`RemoteServiceException` (no `exceptionType` in the error body, so `RemoteExceptionReconstructor`
correctly falls back rather than fabricating a type). README's "How it works" and "Not in v1"
sections updated to state the separate-port non-goal directly rather than as a "not yet built" gap.

### 3.2 Contract fingerprint against rolling-deploy skew — ✅ DONE (2026-07-13)
**Problem:** Positional JSON args + "same version string" across two different *builds* of the jar
can mis-bind silently (e.g. two swapped `String` params).
**Fix:** Compute a fingerprint per (service, version) from the interface's method signatures
(names, parameter types, return types — stable ordering). Client sends it as a header; the
dispatcher compares against its own and fails loudly (409 + both fingerprints) on mismatch.
Off-switch (`modular.transport.verify-contract=false`) for deliberate mixed-build windows.
**Done when:** unit test proves the fingerprint changes on parameter reorder and is stable across
JVM runs; integration test proves the 409 path and the reconstructed client-side error.

**Executed as scoped, with judgment calls made at execution time:**
- **Generic types, not erased ones**, went into the fingerprint (`ModularServiceDescriptor.fingerprint`,
  reused by both `ModularServiceDescriptor.of` on the server side and `InternalRestTransport` on
  the client side via a per-interface cache). Erased types would miss a `List<String>` ->
  `List<Integer>` skew, which is exactly the shape of bug this exists to catch — the dispatcher
  itself binds against `getGenericParameterTypes()`, so the fingerprint needed to match that.
  Signatures are sorted lexicographically before SHA-256 hashing, deliberately not relying on
  `Class.getMethods()`'s unspecified iteration order (a real correctness requirement here, not
  paranoia — see the "Done when" bar's "stable across JVM runs").
- **A missing fingerprint header is accepted, not rejected** — this check exists to catch an
  *actively wrong* fingerprint (two different builds disagreeing), not to require every caller to
  participate. Makes the feature non-breaking for anything not yet using it (existing tests that
  predate this change needed zero updates), and keeps the off-switch genuinely independent per
  side rather than needing coordinated rollout.
- **`ModularDispatcherController`'s constructor was refactored to take the whole `ModularProperties`**
  object instead of just the extracted secret string (as 3.1 had left it) — avoids parameter creep
  now that there are two independent property-driven behaviors (secret, contract verification) and
  keeps both configuration classes' construction sites simple.
- **409 body reuses the existing `{"error": "..."}` shape** (both fingerprints embedded in the
  message text) rather than adding new JSON fields to `ModularDispatchException` — that class's
  `remoteExceptionType`/`remoteExceptionMessage` fields are specifically for exception
  reconstruction and don't fit this case semantically; the reconstructed client-side error ends up
  a `RemoteServiceException` (no `exceptionType` field present, so `RemoteExceptionReconstructor`
  correctly falls back rather than fabricating a type — matches how 3.1's 403 case already works).
- **Unit test coverage** (`ModularServiceDescriptorTest`, 8 tests) covers parameter reorder,
  return-type change, generic-argument change, cross-interface shape equality, and repeatability
  within a JVM (the practical proxy for "stable across JVM runs" — the sorted-then-hashed design is
  what actually guarantees the JDK-unspecified-iteration-order risk doesn't leak through, not
  something a single test run can independently verify by spawning a second JVM).
- **Integration 409 coverage required a raw HTTP call**, bypassing `InternalRestTransport`
  entirely: the plain-Spring and Boot-starter dispatch tests both run client and server against the
  exact same `EchoService` `Class` object in one JVM, so the real transport always sends a matching
  fingerprint — there's no way to simulate "two different builds disagreeing" without manually
  setting a wrong header value via a raw `RestClient` call (`ModularDispatchPlainSpringTest`'s
  `mismatchedContractFingerprintIsRejected`/`missingContractFingerprintHeaderIsAccepted`/
  `verifyContractDisabledOnServerIgnoresMismatchedFingerprint`, and the Boot-starter mirror).
- README's "How it works" section documents the header, the off-switch, and the missing-header
  pass-through behavior.

### 3.3 Topology visibility
**Problem:** The framework's entire value is "config decides the topology," but the resolved
topology is invisible.
**Fix:** (a) One startup log block from the registrar: each `service@version → embedded | internal-rest → resolved URL/template`.
(b) Starter-only: an actuator contribution (info contributor or dedicated endpoint) exposing the
same table.
**Done when:** log output asserted in an existing wiring test; actuator endpoint covered by a
starter test.

### 3.4 Tracing/metrics propagation on the transport
**Problem:** The hand-built `RestClient` bypasses Boot's observation instrumentation — traces stop
dead at the process boundary, exactly where they matter most here.
**Fix:** In the starter, build the transport's client from the auto-configured
`RestClient.Builder` when available (post-1.1 this stays an internal detail, just sourced from the
builder); plain-Spring path unchanged. Note the interaction with 1.2: timeouts must still apply.
**Done when:** a starter test with Micrometer tracing on the classpath observes a propagation
header (`traceparent`) on a dispatched call.

### 3.5 Shared-singleton detection (shared state, part 2)
**Problem:** Two modular services both injecting the same stateful singleton (a cache, a registry,
a mutable holder bean) share one instance in the monolith and get independent copies when split —
writes from the other process are silently "not found." Nothing surfaces this today.
**Fix:** At startup, walk the bean dependency graph from each embedded `@ServiceVersion` impl; if
two services that could be split apart both reach the same singleton bean, log a prominent warning
naming the bean and both services. This is heuristic by nature, so it needs:
- an **infrastructure whitelist** (deliberately-shared types: `DataSource`, `ObjectMapper`,
  `MeterRegistry`, transaction managers, the framework's own beans, ...) — start conservative,
  expand from real noise;
- a **suppression mechanism** for intentional sharing (config list `modular.analysis.shared-bean-allowlist`
  or a marker annotation on the bean);
- an escalation switch (`modular.analysis.fail-on-shared-state=true`) for CI use.
Known blind spots to document, not solve: static fields, and state shared via the filesystem.
**Done when:** a test with two service impls sharing a plain singleton asserts the warning (and
the failure under the CI switch); a whitelisted/suppressed bean asserts silence; README doctrine
section (2.3) links here.

---

## Phase 4 — Flagship feature: strict embedded mode → context-per-service isolation

**Problem (the thesis gap):** Embedded calls pass references inside one shared bean universe;
internal-rest passes JSON values between isolated processes. Mutation, non-serializable types,
subtype slicing, transaction participation, *and shared in-memory state* all behave differently —
the dev monolith does not faithfully predict the split deployment. Built in two steps that ship
independently; step 1 is not throwaway (it catches by-value divergence that step 2's isolation
alone would not).

### Step 1: `modular.strict=true` — value-semantics simulation
Dev/CI-oriented, off by default: embedded dispatch round-trips arguments and return values
through the modular `ObjectMapper` (serialize → deserialize against the declared parameter/return
types) before/after the real in-process call. Serialization bugs, mutation-dependence, and slicing
then reproduce on a laptop instead of in production. Thrown exceptions cross the strict boundary
the same way they cross the wire (type + message, reconstructed) so exception-shape divergence
surfaces too.

Implementation sketch: the registrar wraps each embedded bean in the same JDK-proxy machinery
used for internal-rest, but backed by a `StrictEmbeddedTransport` (serialize → local reflective
invoke via the existing descriptor table → serialize result) instead of HTTP. Reuses
`ModularServiceDescriptor`, the argument-binding code, and `RemoteExceptionReconstructor` —
minimal new surface.

**Done when:** a test shows a call that passes vanilla-embedded but fails under strict mode for
each class of divergence (mutated argument, non-serializable type, subtype slicing); README gets a
"strict mode" section positioning it as the guarantee behind the thesis; example app runs clean
under `--modular.strict=true`.

### Step 2: context-per-service isolation (shared state, part 3 — the real answer)
Where 3.5 *warns* about shared in-memory state heuristically, this makes the isolation real in
dev: instead of one flat `ApplicationContext`, each service impl module gets its own **child
context**; deliberately-shared infrastructure lives in the parent; cross-service calls go through
the step-1 proxy machinery even though everything is one JVM. Shared singletons genuinely aren't
shared anymore — "surprise not found" reproduces on the laptop, deterministically, with a
debugger attached, instead of after the first production split.

Design notes / open questions to resolve during implementation:
- **Partition source:** the Gradle module structure from 2.2 — one child context per impl module.
  Parent-vs-child placement of any given bean is the boundary question made explicit; a bean
  needed by two children must be *promoted* to the parent deliberately (config), which is exactly
  the decision 3.5's whitelist was approximating. Misplacement fails loudly at child-context
  startup ("no such bean"), which is the point.
- **Parent context contents:** MVC / the public API, `DataSource`-class infrastructure, the
  framework's own transport/dispatcher beans.
- **Per-child concerns:** `@Transactional` proxying and other BeanPostProcessor-driven machinery
  must apply inside each child; verify against a child that uses transactions.
- **Activation:** a stricter tier of the same flag (e.g. `modular.strict=isolated`), still
  dev/CI-oriented, off by default. Production topology behavior is unchanged by this phase.

**Done when:** a fixture where two services share a mutable singleton passes vanilla-embedded,
fails (state not visible across the boundary) under isolated mode, and the same fixture rewritten
to the ownership doctrine (state behind one service's interface) passes in *all* modes; example
app runs clean under isolated mode; README's strict-mode section describes both tiers.

**Explicit non-goals recorded while here (document in README, don't build):** transaction
propagation across the boundary (strict mode intentionally does not simulate REQUIRES_NEW-style
isolation — document that `@Transactional` never crosses a `@ModularService` boundary in either
mode as *the rule*), and cross-process Spring events (state the position: events are process-local;
a bridge is a possible future feature, not implied by the current model).

---

## Follow-ons (after the above, not blocking anything)

- **Test slice** (`@ModularServiceTest`-style): boot one service's module with its dependencies as
  strict-mode proxies — with Phase 4 step 2 in place this is just "start one child context, proxy
  the rest," so design it after context-per-service isolation exists rather than as a separate
  mechanism.
- **Publishing/CI:** `maven-publish` setup under the `digital.demilich.henge` group, plus a CI build.
  Prerequisite to any external consumption; content-free until then.
- **Processor-level boundary check** (2.2c) if the `runtimeOnly` pattern proves insufficient in
  practice.
