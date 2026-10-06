# 4. Splitting

**Rung 1: the same jar, as separate services.** You add command-line flags and whatever DNS your
orchestrator already gives you. Henge tracks nothing about who runs where; Kubernetes (or ECS, Nomad,
Consul, ...) does that, as it does for everything else you run. What it does need is to be told where
the processes' shared state lives: see [A split needs a store](#a-split-needs-a-store).

## Embedded and internal-rest

Every version of every service is, in each process, one of two **modes**:

- **`embedded`** (the default): the implementation runs in this process, and a call is a method call.
- **`internal-rest`**: the implementation runs elsewhere, and a call is an HTTP request to
  `POST /_henge/{service}/{version}/{method}` on the process that hosts it.

The caller holds the same proxy either way. Every process also *serves* `/_henge` for whatever it hosts,
so a process that hosts everything is already a valid host for any one of its services.

## Say what a process is

`henge.serve` names what a process hosts; everything else it finds becomes `internal-rest`:

```bash
# Terminal 1: inventory only
java -jar examples/shop-app/build/libs/shop-app-0.1.0-SNAPSHOT.jar --server.port=8082 \
  --henge.serve=inventory-service --henge.store.type=in-process

# Terminal 2: the storefront: orders and notifications, and where to find inventory
java -jar examples/shop-app/build/libs/shop-app-0.1.0-SNAPSHOT.jar --server.port=8080 \
  --henge.serve=order-service,notification-service \
  --henge.services.inventory-service.url=http://localhost:8082 --henge.store.type=in-process
```

`--henge.store.type=in-process` is explained [below](#a-split-needs-a-store); leave it off and neither
process starts.

```bash
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customer": "ada", "items": [{"sku": "chisel", "quantity": 1000}]}'
# 409: "chisel: 1000 wanted, 2 available", thrown in the other process, caught by type in this one
curl 'localhost:8082/api/stock?sku=rope'
```

Same jar, two processes, and the only difference is what each says it *is*. Both have the whole public
API, but the inventory process was told nowhere to find orders, so its `/api/orders` fails, saying so.
Route public traffic to the processes that can serve it, or give every process every url (next section).

`henge.serve` takes `name` or `name@version`, comma-separated or as a YAML list, so a process can host
version 2 of a service and not version 1. A name that matches no service fails startup.

## A service that must be everywhere

Some services only make sense on every process: a frontend that holds clients' connections, a local load
balancer. By default a process hosts everything and `henge.serve` narrows that, so such a service is one
deployment flag from being reached over the network, which defeats it. Say so on the interface:

```java
@HengeRunOnEveryNode
@HengeService
public interface GatewayService { ... }
```

Henge then **refuses to start** a process that wouldn't host every version of it: one whose `henge.serve`
leaves it out, one that configures it `internal-rest`, or one whose implementation needs a `@RequiresLease`,
since a refused lease means it is reached remotely. List it in `henge.serve` (`henge.serve=gateway-service,
inventory-service`), or drop the annotation if it needn't be everywhere. The annotation is about placement
only: it doesn't make the service stateless, or its instances aware of each other.

## A split needs a store

A process that reaches any service over the network is one of several, and **refuses to start unless a store
is configured**:

```
This process hosts only part of the services (it reaches inventory-service@1 over the network), which
makes it one of several processes, but no ephemeral store is configured. The default in-process store is
private to one process, so each process would be a cluster of one: leases, rate limits, advertisements and
scheduled jobs would not be shared. Set henge.store.type (redis, with henge.store.redis.uri), or define a
SystemEphemeralDatastore bean. If these processes really share nothing (a demo, a test), say so with
henge.store.type=in-process.
```

The in-process store is a map in one process. It is correct for a process that is the whole cluster, a
monolith, which is why that needs no configuration. Split it, and every lease, rate limit and scheduled job
would quietly be enforced per process instead of per cluster, which is the very trap Henge exists to
surface. So the default is refused, and the way out is to say something:

- **A shared store** ([chapter 5](05-the-ephemeral-store.md)): `henge.store.type=redis`, or a
  `SystemEphemeralDatastore` bean of your own.
- **`henge.store.type=in-process`**, naming the default on purpose: these processes share nothing. That is
  the truth of a demo on a laptop, or a test, and it is what the commands above say. Nothing is shared, so
  nothing is enforced across them.

It is the same bargain as `@HengeAcknowledgeThisRunsOnEveryNode`: the dangerous thing is possible, and
must be typed.

The check sees only what one process can: that it reaches a service remotely. A **replicated monolith**, the
whole jar run twice with no flags, reaches nothing remotely and looks like any other monolith, so it starts
on the in-process store and shares nothing. Share a store between replicas.

## Let the orchestrator route

Spelling out every other service's url gets old fast. `henge.remote-url-template` fills in the url of
every `internal-rest` service from a pattern, `{service}` being its name and `{version}` its version, to
match the DNS names your orchestrator already has:

```bash
--henge.serve=order-service,notification-service \
--henge.remote-url-template=http://{service}:8080
```

On Kubernetes, that is a Deployment per role, and a Service named after each Henge service in front of
it (abbreviated):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-service
spec:
  selector:
    matchLabels: { app: inventory-service }
  template:
    metadata:
      labels: { app: inventory-service }
    spec:
      containers:
        - name: shop
          image: shop:1.0
          args:
            - --henge.serve=inventory-service
            - --henge.remote-url-template=http://{service}:8080
---
apiVersion: v1
kind: Service
metadata:
  name: inventory-service
spec:
  selector: { app: inventory-service }
  ports:
    - port: 8080
```

The Service load-balances across however many replicas the Deployment runs. To route each version to
its own Deployment, put the version in the name: `http://{service}-v{version}:8080`.

The url of an `internal-rest` service is resolved in order:

1. `henge.services.<name>.url` (or `.versions.<n>.url`), always, when set
2. `henge.remote-url-template`
3. whoever advertises the service on the shared store ([chapter 5](05-the-ephemeral-store.md))

With none of these, a call fails, saying no url is configured and nobody advertises the service.

The mode is resolved the same way: `henge.services.<name>.mode` when set, else `embedded` if `henge.serve`
names the service (or is unset), else `internal-rest`. Naming a service in `henge.serve` and also setting
its mode to `internal-rest` fails startup rather than picking one.

## Per service, per version

`henge.services` configures one service, or one version of it, explicitly:

```yaml
henge:
  services:
    inventory-service:
      mode: internal-rest          # every version, unless overridden below
      url: http://inventory:8080
      versions:
        "2":
          mode: embedded           # version 2 runs here; version 1 is remote
```

A key that names no service, or isn't one Henge knows (say `.mdoe`), fails startup instead of being
ignored. Environment variables work too: `HENGE_SERVICES_INVENTORY_SERVICE_MODE`,
`HENGE_SERVICES_INVENTORY_SERVICE_VERSIONS_2_URL`, or the whole tree as one `SPRING_APPLICATION_JSON`.
Every property is in the [configuration reference](../reference/configuration.md).

## When a call fails

An internal call has a 2 s connect timeout and a 10 s read timeout by default
(`henge.transport.connect-timeout`, `henge.transport.read-timeout`), so one hung process can't hold a
caller's thread forever.

A call that **provably never ran** is retried, up to 3 attempts in all: the connection couldn't be made,
or the answer was a `404`, which means the process doesn't serve that service version (or isn't a Henge
process at all, like a load balancer's default backend). Behind a Kubernetes Service, the retry goes to
the same url, and so usually to another replica. Nothing that may have started is retried: not a read
timeout, not a `5xx`, not an exception the method threw. Whether repeating those is safe depends on the
method, and Henge doesn't guess. See `henge.transport.retry.*` in the [configuration
reference](../reference/configuration.md).

## Before you deploy this

`/_henge` shares the port of your public API, and with no further configuration it is open to anyone who
can reach that port. It is meant for a private network, which is what you have between pods in a
cluster. Read [Operating](07-operating.md) before exposing a process to anything else.

## What you have

Independently deployed, independently scaled services, from one codebase and one artifact, routed by the
platform you already run. Many systems never need more. The next rung is for when you want the processes
themselves to know about each other: to find each other with no DNS conventions, and to share limits
across the whole cluster.
