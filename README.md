# IntelligentRateLimiter

A rate limiter that sets its own limits by watching traffic, instead of enforcing
a number someone typed into a config file months ago.

---

## The problem with a normal rate limiter

A traditional limiter enforces a fixed number: `1000 requests/min per API key`.
Someone picked that number once. It never changes.

That single number has to be right in every situation at once, and it can't be:

| What's happening | What a fixed limiter does | Why that's bad |
|---|---|---|
| It's 3am, the system is idle | Still caps everyone at 1000 | You paid for capacity you're refusing to use |
| The database is struggling | Still admits 1000 | A slow dependency becomes a full outage |
| One customer floods you | Caps *everyone* at 1000 | Your good customers get punished for someone else's bug |
| Traffic doubles after a launch | Blocks real users | Someone has to edit config and redeploy, at 2am |

The number is either too low (you throw away capacity) or too high (you fall over).
Usually it's both, at different times of the same day.

**The real problem:** how many requests you can safely handle is not a constant.
It changes minute to minute with system health, who's calling, and what they're
asking for. A constant can't track that.

---

## What we did about it

We made the limit a **moving number that the system works out for itself**.

Three questions get asked continuously, and the answers are multiplied together:

1. **How much can the system take right now?** Measured from real latency and
   error rates — not declared in config.
2. **How many people are sharing it?** Counted from actual traffic.
3. **Is this particular caller behaving normally?** Compared against that caller's
   own history.

```
your limit = system capacity  x  your share of it  x  how normal you look
```

Nobody configures any of those three. The limiter measures them.

---

## How it works, in plain terms

### 1. It watches every request

Each request that goes through gets timed, and its success or failure recorded.
That's the raw material — how fast the system is answering, and how often it's
failing.

### 2. It learns what "normal" looks like here

This is the key idea, and it's what a traditional limiter can't do.

The limiter keeps a running average of how slow this service usually is, and how
much that varies. "Too slow" isn't a number in a config file — it means *slower
than this service normally is*.

A service that usually answers in 4ms and one that usually takes 800ms each end
up with a threshold that fits them. Neither needed anyone to write it down.

> In our test run, the limiter measured a service answering in ~2ms and settled
> on 16ms as "something is wrong". Nobody chose 16ms.

### 3. It finds the real capacity by carefully pushing

Borrowed from how the internet handles congestion (TCP):

- **Things are fine?** Give out more capacity. From cold, it doubles each second
  until it finds the edge.
- **Things are getting slow?** Cut the capacity in half, immediately.

Generous when it can afford to be, and quick to pull back when it can't. Over
time it settles around what the system can genuinely handle — which it discovered,
not guessed.

### 4. It splits capacity among whoever is actually here

If one client is using the service, it can have all the capacity. If ten show up,
they get a tenth each. When some leave, the rest get more.

There's no tenant list to maintain. The limiter just counts who it has seen
recently.

### 5. It judges each caller against its own history

A key that has quietly sent 2 requests/minute for a week, then suddenly sends 500
per second, is *obviously* wrong — even though 500/s might be well within the
system's limits.

A fixed limiter can't see this. It only knows one number, and 500/s is under it.

This limiter tracks each caller separately, so it can throttle the one that's
behaving strangely and leave everyone else alone. And the throttle is a squeeze,
not a ban — a suspected abuser is slowed to 10% of its share, never cut to zero,
because sometimes it's a real customer having a real spike.

### 6. Rejections explain themselves

When a request is refused, the response says *which* of the three factors caused
it:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 3
X-RateLimit-Limit: 420            # what the limit actually is right now
X-RateLimit-Remaining: 0
X-RateLimit-Reason: system-pressure
```

`system-pressure` (the service is struggling), `fair-share` (lots of tenants
right now), `client-deviation` (you're the one behaving oddly), or `quota` (you
simply used your allowance). A caller can tell whether the problem is them or us.

---

## The two rules that stop it going wrong

An adaptive system can adapt itself into a disaster. Two rules prevent the
obvious ones, and both are covered by tests:

**It only learns while things are healthy.**
If the limiter kept learning during an outage, "broken" would slowly become its
idea of normal, and it would stop protecting anything. So when things look bad,
it reacts but does not learn. The same applies per-caller: a sustained flood
never becomes that caller's accepted baseline.

**It only grows the limit while the limit is actually being used.**
Otherwise a quiet service would drift up to its ceiling overnight and then let a
flood straight through the moment traffic returned.

And a third, less clever but just as important: **the limiter fails open**. If
any of this machinery breaks, traffic is admitted. A rate limiter must never be
the thing that takes you down.

---

## Seeing it work

Real traffic against a running server. Nothing was reconfigured between phases —
the only thing that changed was how fast the service was responding:

| What's happening | Limit it set | What it had learned | Actual p99 |
|---|---|---|---|
| Just started up | 20 (the floor) | nothing yet | — |
| Healthy traffic, using all of it | **1280** | "normal is ~16ms" | 2ms |
| Dependency slows to 400ms | **20** | still 16ms | 418ms |
| Dependency recovers | **33** | still 16ms | 0ms |

It found capacity on its own, collapsed when the dependency degraded, and then
climbed back **gradually** rather than jumping straight to 1280 and re-breaking
whatever had just recovered.

---

## Running it

Needs JDK 21+ and Maven.

```bash
mvn test              # 77 tests
mvn spring-boot:run   # starts on :8080
```

Hit it and watch the headers:

```bash
curl -si -H "X-API-Key: demo" localhost:8080/api/ping | grep -i "x-ratelimit"
```

Ask the limiter what it currently believes:

```bash
curl -s localhost:8080/actuator/ratelimiter | python3 -m json.tool
```

`/api/slow?ms=400` pretends to be a struggling dependency, so you can watch the
limit collapse and recover:

```bash
# healthy load — the limit climbs
while true; do
  for i in $(seq 1 25); do curl -s -o /dev/null -H "X-API-Key: load" localhost:8080/api/ping & done
  wait
done

# now make it slow — the limit collapses
while true; do
  for i in $(seq 1 25); do curl -s -o /dev/null -H "X-API-Key: load" "localhost:8080/api/slow?ms=400" & done
  wait
done
```

Shorten `window`, `baseline-half-life`, and `warmup-ticks` to see it react in
seconds instead of minutes.

---

## Configuration

The interesting part is what's **not** here. No requests-per-minute. No latency
target. No tenant list. No per-customer quotas. Those are all worked out from
traffic.

What's left is three kinds of thing:

```yaml
ratelimiter:
  adaptive:
    enabled: true

    # 1. Safety rails — the limiter may move freely between these, and nowhere else.
    min-budget: 50          # never throttle below this, however bad things look
    max-budget: 5000        # never grant above this, however good things look
    min-client-limit: 5     # never squeeze one caller below this

    # 2. Time constants — how fast to react, how long to remember.
    control-interval: 1s
    window: 30s
    baseline-half-life: 5m
    warmup-ticks: 20

    # 3. Sensitivity — how far from normal counts as "something's wrong".
    deviation-sigma: 3.0

    # Optional: a hard latency limit, if you genuinely have an SLO. Zero means
    # "just compare against what this service normally does".
    latency-ceiling: 0s
```

The rails are deliberate. An adaptive system with no bounds is an outage
generator — it should be free to be clever *within* limits you can reason about.

Set `adaptive.enabled: false` to fall back to a plain fixed limit, which is handy
for comparison.

---

## How it fits together

```
   request
      │
      ▼
 ┌──────────┐   asks "what's the limit for this caller?"   ┌──────────────┐
 │  Filter  │ ──────────────────────────────────────────►  │   Resolver   │
 │          │ ◄────────────────────────────────────────── │ capacity ×   │
 └────┬─────┘            a number, and why                  │ share ×      │
      │                                                     │ reputation   │
      │ times the request, records success/failure          └──────▲───────┘
      ▼                                                            │
 ┌──────────┐        every second        ┌────────────────┐        │
 │ Metrics  │ ─────────────────────────► │  Control loop  │ ───────┘
 │ p99, errs│                            │ learn, adjust  │
 └──────────┘                            └────────────────┘
```

The request path stays dumb and fast: it looks up a number that's already been
worked out. All the thinking happens once a second, off to the side. If the
thinking stops, the last known number keeps being enforced.

```
ai/assistiv/ratelimiter/
├── core/         enforcement — the fast path
│   ├── TokenBucketRateLimiter    counts requests, allows or denies
│   ├── LimitResolver             "what's the limit?" — the seam between layers
│   └── ResolvedLimit             the number, plus which factor set it
├── adaptive/     the intelligence — all off the fast path
│   ├── SlidingWindowTrafficMetrics   what's been happening recently
│   ├── EwmaBaseline                  learns normal, spots deviation
│   ├── CapacityController            finds capacity, backs off under pressure
│   ├── ClientProfileRegistry         per-caller history and fair shares
│   ├── AdaptiveLimitResolver         multiplies the three factors together
│   └── AdaptiveControlLoop           runs once a second
├── config/       settings and wiring
└── web/          filter, key resolution, state endpoint, demo endpoints
```

---

## What's built, and what isn't

Working today:

- Token bucket enforcement, per caller, with continuous refill
- Latency, error rate, throughput, and in-flight measurement
- Learned health thresholds — no SLO required
- Capacity discovery, and backing off under pressure
- Per-caller profiles and deviation detection
- Fair sharing across whoever is currently active
- Self-explaining rejections and a state endpoint
- Fails open, and forgets idle callers so memory stays bounded

Not built yet:

- **Priority tiers.** Everyone shares equally right now; premium customers can't
  yet be told to absorb less of the pain.
- **Cost weighting.** A search query and a health check currently cost the same.
  The plumbing accepts a cost, but everything charges 1.
- **The gentler steps before rejection.** Shedding optional work, or briefly
  queuing, before a 429.
- **Shared state across instances.** Each process has its own view, so two
  replicas enforce two independent budgets.
- **A PID controller** as an alternative to the current approach.
- **TO-DO** Other algorithms references needs a cleanup
- FixedWindow, LeakyBucket, TokenBucket

---
