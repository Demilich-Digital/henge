# 2. Services and boundaries

**Rung 0: the monolith.** Everything here is useful in a single process, forever. It is also what
makes every later rung a configuration change instead of a rewrite.

## A service is an interface

```java
@HengeService
public interface InventoryService {

    void reserve(UUID orderId, ImmutableList<LineItem> items);

    void release(UUID orderId);

    int available(String sku);

    void restock(String sku, int quantity);
}
```

`@HengeService` marks an *internal* boundary between parts of your application. The service's name is
the interface's simple name in kebab case, `inventory-service`, or `@HengeService(name = ...)`; it is
the name configuration and the wire use, so it must be lowercase kebab case.

Your public API is not a `@HengeService`. It stays plain Spring MVC, and Henge never touches it:

```java
@RestController
@RequestMapping("/api")
class ShopController {

    private final OrderService orders;

    ShopController(OrderService orders, /* ... */) { ... }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Order place(@RequestBody PlaceOrder request) {
        return orders.place(request.customer(), ImmutableList.copyOf(request.items()));
    }
}
```

## An implementation declares its version

```java
@ServiceVersion(value = OrderService.class, version = 1)
public class OrderServiceImpl implements OrderService {

    public OrderServiceImpl(InventoryService inventory, NotificationService notifications) { ... }
}
```

`@ServiceVersion` takes the place of `@Service`. Henge registers the bean itself, so don't also annotate
it `@Service` or `@Component`: that would make a second instance, and both the processor and startup
reject it. Versions are positive integers. Most services have one version, forever; [chapter
3](03-versions.md) is about the ones that don't.

Callers inject the interface, as they would any bean. What they get is a proxy, never the implementation:
today it makes a method call in the same process, and after [chapter 4](04-splitting.md) the same proxy
may make an HTTP call instead. The caller can't tell, and isn't meant to. That is the whole point, and it
holds only if the two kinds of call behave the same. The rest of this chapter is what that takes.

## What crosses a boundary is a value

In one process, an argument is passed by reference: the caller and the service hold the same object. Over
the network it is passed by value, as JSON. A mutable object makes these differ. The service changes a
list it was handed, and the caller sees the change, but only while they share a process; split them, and
the change silently stops arriving. A JPA entity is worse: attached and lazy-loading in one process,
detached and half-populated over the wire.

So everything that crosses a `@HengeService` method, parameters and return values, recursively, must be
**provably immutable**, and the processor checks it at compile time:

- records and enums (records are checked component by component)
- primitives, `String`, `java.time.*`, `UUID`, `BigDecimal`, `BigInteger`
- `ImmutableBytes` (from `henge-core`) for binary data: an array is mutable, so `byte[]` is rejected. It's
  small payloads only; see [Compile-time checks](../reference/compile-time-checks.md#binary-data)
- `ImmutableList`, `ImmutableSet`, `ImmutableMap` (from `henge-core`, or Guava's) and `Optional`, of the
  above

```java
public record LineItem(String sku, int quantity) {
}

public record Order(UUID id, String customer, ImmutableList<LineItem> items, OrderStatus status, Instant placedAt) {
}
```

Plain `List`, `Set` and `Map` are rejected. Over the wire they would come back as a mutable `ArrayList`
or `HashMap`, and reopen exactly the gap the rule closes. A boundary that takes a `java.util.List` fails
to compile:

```
error: parameter 'items' java.util.List<...LineItem> of method 'reserve' on ...InventoryService
       is not a valid @HengeService boundary type (it's not a record, enum, or a recognized immutable
       value type). ...
```

The public API is outside this rule: `ShopController`'s request body is a plain record with a `List`,
copied into an `ImmutableList` at the call. The complete list of what the processor checks, including
the shape of an interface (no overloads, no generic methods, ...), is in [Compile-time
checks](../reference/compile-time-checks.md).

## Exceptions are unchecked, and carry their meaning

A checked exception on a `@HengeService` method is a compile error, because it can't behave the same way
over the wire. Throw unchecked exceptions, and give the ones a caller should handle a `(String)`
constructor:

```java
@ErrorStatus(409)
public class OutOfStockException extends RuntimeException {

    public OutOfStockException(String message) {
        super(message);
    }
}
```

In one process the caller gets the exception the implementation threw. Over the wire, Henge rebuilds the
same type on the caller's side, so `ShopController` catches `OutOfStockException` by type wherever
inventory runs:

```java
@ExceptionHandler
ProblemDetail outOfStock(OutOfStockException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
}
```

`@ErrorStatus` chooses the HTTP status the internal call answers with once the service is split;
without it, a business exception is a `500`. One status has a meaning of its own: **a `404` means the
call had no effect.** Henge may retry a `404` on another host (chapter 4), so an exception marked
`@ErrorStatus(404)` must be thrown before the method changes anything. `OrderNotFoundException` qualifies:
a lookup that found nothing did nothing.

The process that ran the method logs the stack trace, `ERROR` for a `5xx` and `DEBUG` for a `4xx`;
`@ErrorLogLevel` on the exception overrides it. The caller gets the type and the message.

## State lives somewhere you chose

Two of the shop's services keep state, in two different places, deliberately.

Inventory's stock is in a database. The implementation holds a connection pool and nothing else, so any
number of processes can host it at once and every one of them sees the same stock:

```java
@ServiceVersion(value = InventoryService.class, version = 1)
public class InventoryServiceImpl extends InventoryServiceSkeleton {

    public InventoryServiceImpl(@RequiresLease("inventory-db") DataSource database) { ... }
}
```

(`@RequiresLease` is how it gets that pool; [chapter 6](06-leases-and-rate-limits.md) explains it. For
now, it is a `DataSource`.)

Orders are in a `ConcurrentHashMap` inside `OrderServiceImpl`. That keeps the example short, and it is
correct for as long as one process hosts orders. Two processes hosting it would each know only their own
orders. Henge can't see inside your objects, so this is the one boundary rule it can't check for you:
**state that has to be shared belongs in something built to share it**, a database for business data.
Write it that way in the monolith and nothing has to change when you split. The same goes for any other
bean two services both inject: in one process they share an instance, and split they each have their own.

## What you have

One process, three services, calling each other directly, with boundaries the compiler enforces. That is
a perfectly good place to stay. When one of the services needs to change how it works without everything
else changing with it, read on.
