# Design: hot keys

How Henge keeps any one key in its [ephemeral store](../ephemeral-store.md) from limiting the cluster. A sharded
store spreads keys over servers, but a key lives wholly on one, so a key whose cost grows with the cluster sets a
ceiling that adding shards can't lift. [Store capacity](store-capacity.md) measures the three that do:

| Key | Grows with | Today's ceiling, one shard |
|---|---|---|
| `rate:<name>` bucket | the demand on the limit, refused calls included | ~50,000 attempts per second at cloud speed |
| `rate:<name>` membership | subscribers², every heartbeat | ~2,400 subscribers |
| `adv:<service>@<version>` | callers × hosts, every refresh | ~2,600 callers × 2,600 hosts |

This design makes each one's cost grow at most linearly, so that sharding carries the rest.

**Status.** Not built. [Decisions](#decisions) were made in discussion; [open questions](#open-questions) come
with a recommendation.

## Decisions

- **Keep the atomicity a key is relied on for.** `claim` and `tryAcquire` stay atomic on one key. Nothing here
  splits a key whose operations are checked and written at once.
- **No coordination, and nothing that has to be agreed.** Every mechanism is a node acting on what it reads and what
  it saw itself. A design that needs every host and caller of a service to agree on a number is out.
- **A bucket is relieved by refusing locally,** for as long as the bucket itself says nothing can fit, and a little
  longer under overload.
- **Contract additions are allowed** where they are small and every store can implement them. Each one is listed
  under [the contract](#the-contract).

## The bucket: refusing locally

A `tryAcquire` costs the same whether it admits or refuses, so a limit under overload spends nearly all of its
store load on refusals. A node that has just been refused already knows the next attempt will be refused for a
while. The question is how long.

### The exact window

When a bucket refuses, its script holds the level, the capacity and the leak rate, so it can return **how long
until one permit fits**:

```
wait = ceil((level + period − capacity × period) / permits)   milliseconds

level is in the bucket's own units, 1/period of a permit, which leak at `permits` a millisecond
```

Until then nothing fits for anyone. The level only falls at the fixed leak rate, and another node's take only
raises it. So a node that refuses every call locally for `wait` refuses **nothing the store would have admitted**,
and the contract's "never refuses a call it should allow" holds as written.

- It is one permit, not the amount asked. A refusal of five permits when one would fit returns zero, and the next
  call goes to the store. A window for one permit is exact for every amount.
- It is relative, on the store's clock, and the node times it on its own monotonic clock from when the reply
  arrives. The window ends at most one reply's latency late, a fraction of a millisecond.
- A request larger than the bucket's capacity never fits. It is refused locally without asking, as it is refused
  by the store today.

### Stretching it under overload

The exact window doesn't relieve a bucket under sustained overload. A full bucket frees a permit every
`period / permits`, so `wait` is that long, and every refused node comes back at the same moment. One wins, and the
rest are refused again. The store sees (*subscribers* × *limit*) attempts per second: a limit of 1,000 per second,
with 50 nodes that want more, is still 50,000.

So a refused node waits **the longer of the exact window and its fair share**, jittered:

```
window = clamp( max(wait, share × jitter(0.5 … 1.5)), wait, drain )

share  = n × period / permits     how often this node would get a permit if n nodes split the rate evenly
drain  = capacity × period / permits   how long a full bucket takes to empty
```

*n* is the limiter's subscriber count, which [membership](#membership-counting-without-reading) already keeps.

- **Store load becomes about the limit itself.** Each node asks about once per share, so *n* nodes ask about
  *permits / period* times a second between them, whatever the demand.
- **No throughput is lost.** Past the exact window this does refuse calls that would fit. But the permits nobody
  takes stay in the bucket as room, and the next takers spend them as a burst. That holds while the bucket hasn't
  drained to empty, which is why the window is capped at `drain`: a full bucket can't empty while every node waits.
- **One node alone** has `share` equal to the exact wait, so the stretch does nothing, as it should.
- **Fewer permits than nodes.** When the capacity is below *n*, `drain` is below `share` and the cap wins. That is
  the same case in which the degraded 1/N share can't be divided exactly; here it costs only some of the relief.

This is a change of promise, and it is stated: **within its window a limiter may refuse a call that would have
fit, and admits one later in its place.** The rate admitted over any window longer than `drain` is unchanged.

### Where it lives

- **The store contract** returns the wait with a refusal ([below](#the-contract)). The in-process store computes
  it the same way, so the degraded local share has it too.
- **`RateLimiter`** (`henge-core`) keeps a refusal deadline per bucket key, subjects included, and checks it
  before asking the store. It holds no other state, so non-Spring users get the exact window with nothing to
  configure.
- **The fair-share stretch** needs *n*, so it lives where the subscription is: the Spring side's wrapper around
  the limiter's store (`RateLimiters.Reporting`), which turns the store's wait into the stretched window.
- **Deadlines expire.** A subject's deadline is dropped once it has passed, and the map is swept when it grows, so
  the refused subjects it holds are those inside a window, never more than `drain` old.
- **Metrics** count a local refusal as its own outcome, `refused-locally`, so the store's refusals and the
  limiter's can be told apart.
- **The wait is the caller's too.** `RateLimiter` returns it with a refusal, so an application can send
  `Retry-After` with its `429`.

## Membership: counting without reading

A limiter's subscribers renew their membership and then read every member back, only to count them. That is one
read of *n* members by each of *n* nodes, every beat.

**Mechanism.** A `count(key)` operation returns the number of live members and the key's epoch, in O(1). The
heartbeat becomes a `put` and a `count`. The wipe rule (a smaller count from a new epoch isn't believed until the
next beat) works unchanged, since the count carries the epoch.

Redis's `HLEN` counts only live fields: measured on Redis 8, fields whose TTL has passed and that haven't been
reclaimed yet are not counted, in the small and the large encoding alike. The
[epoch token](lease-healing.md#every-redis-loss-changes-the-epoch) field, when built, is subtracted.

The cost falls from O(*n*²) to O(*n*) per beat on one key, which ends this ceiling for good: 10,000 subscribers is
about 2,000 small operations a second.

## Advertisements: a sample, sized by the callers

A caller re-reads every host of every service it calls, each refresh, to choose among them. It needs a few of
them, not all of them, but enough callers have to see each host for the load to reach every host.

**Mechanism.**

1. **Callers register.** A node that calls a service version through its advertisements writes itself as a member
   of `call:<service>@<version>`, renewed on the refresh. That is one small write per caller per refresh, linear,
   and it tells everyone how many callers a service has: *C*. It is also something the topology report can show.
2. **A caller reads a sample.** A `sample(key, k)` operation returns up to *k* live members at random, how many
   live members there are, and the key's epoch. The caller asks for

   ```
   k = min( H, max( k_min, ceil(α × H / C) ) )
   ```

   where *H* is the host count, which the sample returns with its members, and *C* the caller count. With α around 4, each host is in
   about four callers' samples, so every host gets traffic and the load spreads. `k_min` (around 32) keeps a
   caller's failover choice wide.
3. **The rest is as now.** Calls rotate through the sample, a host that fails is skipped, and an empty read from a
   new epoch is not believed for one interval. A sample is empty only when the key is. If every host in a sample
   has failed, the caller reads a new sample at once instead of waiting for the refresh.

**What it costs.** A key's reads cost about *C* · (fixed + *k*) per refresh, which is
*C* · fixed + α · *H* + *C* · `k_min`: linear in callers and hosts, not their product. In the extreme of one caller
and every other node a host, *C* = 1 and the caller reads everyone, which is one read. In the two-tier case at
10,000 nodes, a service's key costs about 2% of a core, where it would cost 83% today.

Redis's `HRANDFIELD` with a count returns distinct live fields only: measured on Redis 8, expired fields are never
returned. The epoch token is asked for and dropped (`k + 1`, minus the token).

**A sample is better routing, not only a cheaper read.** It would be worth having at any size:

- **Fewer connections, reused more.** A caller spreads its calls over *k* hosts, not all of them. It keeps fewer
  connections open, and each sees enough traffic to stay warm.
- **New hosts ramp up.** A host that starts is taken into samples as callers re-draw, a refresh at a time, rather
  than being handed a share of every caller's traffic at once.
- **A failure is contained.** A host that dies is in the samples of about α callers, not every caller, and those
  re-draw at once.
- **Load spreads by construction.** Each caller re-draws every refresh, so who sends to whom keeps changing, and no
  caller's ordering of the full list makes some hosts its favorites.

That makes α the load-balancing parameter as well as a cost. Each host takes traffic from about α callers at a
time, so callers whose rates differ a lot make hosts' loads differ by about as much, smoothed over the refreshes.
A larger α smooths it, at a cost linear in *H*.

**Why not partition the key.** Splitting `adv:<service>@<version>` into *P* keys, hosts and callers each hashing
to one, also cuts a key's cost and spreads it over shards. But every host and caller of a service has to agree
on *P*. A rollout that changes it splits the service's callers from its hosts for the length of the rollout, and
choosing *P* from the host count would be coordination. Once a key's cost is linear, spreading it over shards
isn't needed, so the sample does the same job with nothing to agree on.

### Other readers of advertisements

- **The trunk pool** (channels) reads the same keys to pick a backend, and takes a sample the same way.
- **The topology report** reads the whole key, on request. That is one read per request, not per refresh.
- **The `advertisers` metric** comes from the count, not from the size of what was read.

## The contract

Three changes to `SystemEphemeralDatastore`, each implemented by every store:

| Change | Returns | Redis |
|---|---|---|
| `tryAcquire` returns a result, not a boolean | granted, or refused with the wait until one permit fits | the wait is computed in the existing script |
| `count(key)`, new | the number of live members, and the epoch | `HLEN` |
| `sample(key, k)`, new | up to *k* live members at random, the number of live members, and the epoch | `HRANDFIELD key k WITHVALUES` and `HLEN`, in one script |

`read(key)` stays for what needs everyone: leases, the topology report, and job runs.

The decorators (`GuardedDatastore`, the metered store, `RateLimiters.Reporting`) pass the new operations
through. The in-process store implements all three directly.

## Testing

- **The exact window**: a bucket refused at a known level returns the wait the arithmetic gives. A node that
  refuses locally for it, then asks, is admitted. One that asks a millisecond before is refused by the store.
- **The stretch**: *n* simulated nodes under overload send about *permits / period* attempts a second to the
  store between them. The admitted rate over a window longer than `drain` equals the limit.
- **Count**: matches `read(key).members().size()` for keys with expired, renewed and removed members, and its epoch
  matches the read's.
- **Sample**: never returns an expired member, never more than *k*, and is empty only when the key is. Over many
  draws every member appears.
- **Sized samples**: *C* callers and *H* hosts in a simulation: every host is in at least one sample, and the
  store's member reads per refresh are linear in *C* + *H*.
- **The measuring loop** of [store capacity](store-capacity.md#reproducing-the-numbers), before and after, at
  1,000 simulated members per key.

## Phasing

Each phase is useful alone.

1. **The refusal window**: the `tryAcquire` result, the exact window in `RateLimiter`, the stretch in the Spring
   wrapper, and the metric. It relieves the one hot key that grows with traffic rather than nodes.
2. **`count`**, and membership using it. Small, and it ends the limiter's ceiling.
3. **`sample`, caller registration and sized samples** for advertisements and the trunk pool.

## Open questions

- **α and `k_min`** are first guesses. The simulation should set them: the smallest α at which no host is left out
  of every sample, and at which hosts' loads stay within a set spread when callers' rates are skewed, over a range
  of *C* and *H*.
- **Callers' registration cadence.** *C* changes slowly, so the count could be read every few refreshes rather than
  every one. Recommended: every refresh until measured, since the operation is O(1).
- **Jitter range** of the stretch. 0.5 to 1.5 of the share spreads returns over a share's length, which is enough
  to avoid a herd at the end of the window. Recommended as is.
