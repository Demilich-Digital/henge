# 3. Versions

**Still rung 0.** Versioning pays off in a single process, and it is what lets a split deployment roll
forward one process at a time instead of all at once.

The usual way to version a service API is at the network boundary: deploy two revisions and route
between them by header or path, in a gateway. Henge puts versions in the binary instead. Two versions of
a service are two classes in the same jar, both running, and which one a caller gets is decided by
dependency injection, checked at compile time.

## Adding a method in version 2

The storefront wants stock for several skus in one call. That's a new method, and a new version:

```java
@HengeService
public interface InventoryService {

    void reserve(UUID orderId, ImmutableList<LineItem> items);
    // ...

    @AddedIn(2)
    ImmutableMap<String, Integer> availability(ImmutableSet<String> skus);
}
```

Java would now force version 1 to implement `availability` too. `@AddedIn` is what lets it not: for an
interface with versioned methods, the processor generates `InventoryServiceSkeleton`, an abstract class
that implements every versioned method by throwing. Implementations extend the skeleton instead of
implementing the interface, and override what their version supports:

```java
@ServiceVersion(value = InventoryService.class, version = 1)
public class InventoryServiceImpl extends InventoryServiceSkeleton {
    // no availability(): calling it on version 1 throws ServiceVersionUnsupportedException
}

@ServiceVersion(value = InventoryService.class, version = 2)
public class InventoryServiceImplV2 extends InventoryServiceImpl {

    public InventoryServiceImplV2(@RequiresLease("inventory-db") DataSource database) {
        super(database);
    }

    @Override
    public ImmutableMap<String, Integer> availability(ImmutableSet<String> skus) { ... }
}
```

Version 2 extends version 1 here, because it is version 1 plus a method. Nothing requires that; it is
just the honest shape of most new versions. Both read the same database, so both see the same stock.

The processor checks it the other way too: a version whose range includes a method must really
implement it. Version 2 leaving `availability` to the throwing stub is a compile error, not a surprise in
production. `@DeprecatedSince(3)` is the mirror image: the method is optional from version 3 on.

## Choosing a version

A caller that doesn't care injects the interface, and gets the service's **default version**,
`@HengeService(defaultVersion = ...)`, which is `1` unless you say otherwise. Orders doesn't care:

```java
public OrderServiceImpl(InventoryService inventory, NotificationService notifications) { ... }
```

A caller that needs version 2 says so, with the same annotation, at the injection point:

```java
ShopController(OrderService orders,
        @ServiceVersion(value = InventoryService.class, version = 2) InventoryService inventory,
        NotificationService notifications) { ... }
```

Both versions run side by side in the same process. A caller pinned to version 1 that calls
`availability` gets `ServiceVersionUnsupportedException`, naming the version the method needs. Over the
wire it answers `501`, and the caller still gets the exception.

## Changing records

A record that crosses a boundary may **gain** components without a new version: a process that doesn't
know a component ignores it, and one that expects a missing component reads `null` or `0`. That's what
lets old and new processes talk during a rollout. **Renaming or removing** a component is a breaking
change. Ship it as a new version, with a new record if need be.

## Rolling a version out

Once services are split ([chapter 4](04-splitting.md)), processes are deployed one at a time, so for a
while old and new jars run together. Versions in the binary make that safe in steps:

1. **Ship version 2 next to version 1.** New processes run both; old ones run version 1. Nothing calls
   version 2 yet, except callers that pinned it, and those are new code, in new processes.
2. **Make it the default:** `defaultVersion = 2`, in a later release. Callers that don't pin move over
   as their processes are redeployed.
3. **Stop running version 1.** A process runs only the most recent versions of each service, two by
   default (`henge.recent-versions`): when version 3 ships, version 1 stops running, though its class stays
   on the classpath (where version 2 still extends it). Two is a deploy from the previous version plus a
   rollback of one; raise it for longer overlaps. A version that the default, `henge.serve` or
   `henge.services` asks for outside that window fails startup.

How a caller reaches the right version of a remote service is part of [splitting](04-splitting.md): each
version can be hosted and addressed on its own.
