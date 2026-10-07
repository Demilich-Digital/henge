# Internals

How Henge does what the guide describes. None of this is needed to use it; it's here for reviewing it,
debugging it, or extending it.

## Wiring

`@EnableHengeServices` imports `HengeServiceRegistrar`, which scans `basePackages` for `@HengeService`
interfaces, `@ServiceVersion` implementations and `@LeasedResource` providers. Implementations are
registered by the registrar, not by component scanning: `@ServiceVersion` carries no `@Component`
meta-annotation. For every (interface, version) in play, and within the `henge.recent-versions` window:

- **embedded**: the implementation is registered as a hidden bean, `<service>-<version>.impl`, not an
  autowire candidate. A version configured or expected as embedded with no implementation fails startup.
- **internal-rest**: no implementation is built; calls go to the transport.
- Either way, what callers inject is a JDK dynamic proxy (`java.lang.reflect.Proxy`), never the
  implementation. The version matching the interface's `defaultVersion()` is marked `@Primary`, and every
  proxy carries `@ServiceVersion` qualifier metadata, so Spring's own autowiring resolves an unqualified
  dependency to the default version, and a qualified one to the version asked for. There's no custom
  autowiring and no bytecode generation; the [registrar's
  Javadoc](../henge-spring/src/main/java/digital/demilich/henge/spring/HengeServiceRegistrar.java) has the
  exact mechanics.

An application that injected an implementation by its concrete class has to take the interface instead.

A service that needs leases is bound when its leases are granted or refused, at startup: built here if
granted, and reached remotely if not; a refused one stays a candidate of the `HengeLeasePoller`, which
switches its binding to the implementation if a later look finds room. The other way round, a lease whose
renewal the store refuses is given up: `HengeLeaseKeeper` runs each holder's eviction, which retires the
service (see `HengeServiceRegistry.retire`), hands the claim back, and makes it a candidate again. Each configured rate limit is one `RateLimiter` bean, qualified
`@RateLimited(name)`. The store is created before any service bean, whatever the application orders, so a
service can depend on it during construction.

## A call

Every call, embedded or remote, passes through the proxy, and through the application's
`ServiceCallInterceptor` beans, in `@Order`, around the call's target: the one place to observe or alter a
call without knowing how it is fulfilled (it's how `henge.call` is observed). An interceptor sees calls
made by this process's callers only: a call arriving over `/_henge` was already intercepted on the
process that made it, and goes straight to the implementation.

Remote calls go through `ServiceTransport` (in `henge-core`), the seam between the proxy and the wire.
`internal-rest` (HTTP and JSON) is the only transport, and it isn't an extension point today: the serving
side (`HengeDispatcherController`) and the wiring are REST-specific too, and exactly one transport bean is
registered and looked up by type. A second transport, gRPC say, would be a design exercise, not a
drop-in.

## Serving

`HengeDispatcherController` serves `POST /_henge/{service}/{version}/{method}` for every service version
this process hosts, looked up in a registry built at startup and keyed by bean name, never by type alone,
since several versions of one interface may be hosted at once. The secret, when set, is checked before
the body is read, so an unauthenticated caller can't make the process parse anything. Arguments and return
values are read and written by the transport's own `ObjectMapper`, typed by the method's declared types,
with no message converters involved. The [wire protocol](reference/wire-protocol.md) has the formats.

## The store

`HengeDatastoreInstaller` decides which `SystemEphemeralDatastore` the process uses: a bean the
application defined, else the adapter that `henge.store.type` selects through `ServiceLoader`, else the
in-process store, which it refuses (with the `service@version`s that make it a split) when the registrar
found any service reached over the network and nothing was configured. Everything Henge does with it (advertisements, lease claims, rate limits, routing
reads) goes through a metering wrapper when meters are on, which is where `henge.store.operations` comes
from. The [design
doc](design/self-orchestration.md) covers the store's model and where it's going.
