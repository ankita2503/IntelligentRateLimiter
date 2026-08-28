# IntelligentRateLimiter

An adaptive, traffic-aware rate limiter. Instead of enforcing a fixed number that
someone guessed months ago, it continuously derives the limit from what the system
and its callers are actually doing right now.

---

## Why not a traditional rate limiter?

A classic limiter (fixed window, sliding window, token bucket) enforces a **static**
budget — `1000 req/min per API key`. That number is a compromise, and it is wrong in
both directions:

| Situation | Static limiter | Cost |
|---|---|---|
| System is healthy and idle at 3am | Still caps at 1000 | Throws away capacity you paid for |
| Downstream DB is degraded | Still admits 1000 | Turns a slow dependency into an outage |
| One client bursts, everyone else is normal | Everyone hits the same cap | Noisy neighbor punishes good tenants |
| Legitimate traffic doubles after a launch | Blocks real users | Manual config change + redeploy |

The limit is a control problem, not a constant. `IntelligentRateLimiter` treats it
as one.

---

## Approach

### 1. The limit is computed, not configured

Every client gets an **effective budget** recalculated on a short interval
(default: 1s):

```
effective_limit = base_limit
                × health_factor      # how much headroom the system has
                × fairness_share     # this client's slice of scarce capacity
                × reputation_factor  # how well-behaved this client has been
```

Config supplies the *floor* and *ceiling* (`min_limit`, `max_limit`) and the SLO to
protect. The controller picks the value in between.

### 2. Closed-loop control on an SLO, not on a request count

A feedback controller (AIMD by default, PID optional) drives the global admission
budget toward a target signal — typically `p99 latency ≤ SLO` and `error_rate ≤ ε`:

- **Healthy** (p99 well under target): additive increase — hand capacity back, let
  traffic through.
- **Stressed** (p99 crossing target, errors climbing, queue depth growing):
  multiplicative decrease — shrink the budget fast.

This is the same shape as TCP congestion control: slow to grant, fast to protect.
The system finds the real capacity instead of trusting a hardcoded one.

### 3. Traffic shape awareness

Raw counts hide intent. Each caller is tracked as a short-horizon profile:

- **EWMA baseline** of request rate, per client and per route.
- **Burstiness** — variance against that baseline, so a spiky-but-small client is
  not treated like a sustained flood.
- **Cost weighting** — a request is charged by its actual expense (latency,
  payload, downstream fan-out), not counted as `1`. A search query and a health
  check should not consume the same budget.
- **Deviation detection** — throttling triggers when a client departs from *its own*
  established pattern, so a 10× spike from a normally-quiet key is caught even
  though it is nowhere near the global cap.

### 4. Fairness under scarcity

When capacity is short, the limiter does not shed uniformly. It allocates by
weighted fair share across tenants and priority tiers, so the client causing the
pressure absorbs it. Well-behaved tenants keep their normal service level.

### 5. Graceful degradation ladder

Rejection is the last step, not the first:

```
admit fully
  → admit, shed optional work (skip cache warm, drop enrichment)
    → queue briefly with a deadline
      → reject low-priority tiers
        → reject with 429 + Retry-After
```

`Retry-After` is derived from the current recovery trajectory, so clients back off
by a useful amount instead of a made-up constant.

---

## Architecture

```
        request
           │
           ▼
   ┌───────────────┐      ┌──────────────────┐
   │  Enforcement  │◄─────│ Decision Engine  │
   │  (middleware) │      │  limit resolver  │
   └───────┬───────┘      └────────┬─────────┘
           │                       │ reads
           │ emits                 ▼
           │              ┌──────────────────┐
           │              │  State Store     │
           │              │  counters, EWMA, │
           │              │  budgets (Redis) │
           │              └────────▲─────────┘
           ▼                       │ writes
   ┌───────────────┐      ┌────────┴─────────┐
   │  Telemetry    │─────►│  Controller      │
   │  latency,errs │      │  AIMD / PID loop │
   └───────────────┘      └──────────────────┘
```

- **Enforcement** — hot path only. One lookup, no computation. Must stay
  sub-millisecond and fail open.
- **Decision Engine** — resolves the effective limit for a given key from the
  current budget, share, and reputation.
- **State Store** — shared counters and rolling stats. Atomic via Lua scripts so
  multiple instances agree.
- **Telemetry** — samples latency, error rate, and queue depth from real traffic.
- **Controller** — the only component allowed to move the budget. Runs off the hot
  path on a fixed tick.

---

## Design principles

1. **Fail open.** If the limiter, the store, or the controller is unavailable,
   traffic is admitted at the last known good limit. A rate limiter must never be
   the outage.
2. **The hot path does no thinking.** All adaptation happens asynchronously; the
   request path reads a precomputed number.
3. **Bounded authority.** The controller can only move within `[min_limit,
   max_limit]`. Adaptation cannot run away in either direction.
4. **Explainable decisions.** Every response carries why it was limited — which
   factor dominated, what the effective limit was, when to retry.
5. **Observable by default.** Effective limit, health factor, and shed rate are
   first-class metrics. An adaptive system you cannot see is an adaptive system you
   cannot trust.

---

## Response contract

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 3
X-RateLimit-Limit: 420          # effective limit right now, not the configured max
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1712345678
X-RateLimit-Reason: system-pressure   # or: client-deviation | fair-share | quota
```

---

## Configuration sketch

```yaml
limiter:
  slo:
    p99_latency_ms: 250
    max_error_rate: 0.01
  bounds:
    min_limit: 50          # never throttle below this
    max_limit: 5000        # never grant above this
  controller:
    strategy: aimd         # aimd | pid | static
    tick_ms: 1000
    increase_step: 25      # additive, per tick
    decrease_factor: 0.5   # multiplicative, on breach
  fairness:
    strategy: weighted_fair_share
    tiers: { premium: 3, standard: 1, free: 0.25 }
  cost:
    enabled: true          # charge by measured cost, not request count
  fail_mode: open
```

---

## Roadmap

- [x] Core token bucket, per-client, in-memory
- [ ] Distributed atomic counters (shared state across instances)
- [ ] Telemetry collection and rolling SLO signals
- [ ] AIMD controller and bounded budget adjustment
- [ ] Per-client EWMA profiles and deviation detection
- [ ] Weighted fair share across tiers
- [ ] Cost-weighted accounting
- [ ] Degradation ladder + derived `Retry-After`
- [ ] PID controller as an alternative strategy
- [ ] Load-test harness: burst, sustained flood, noisy neighbor, degraded downstream
- [ ] Reference middleware adapters

---

## Running it

Requires JDK 21+ and Maven.

```bash
mvn test                                  # 15 tests, unit + integration
mvn spring-boot:run                       # starts on :8080
```

Try the limit:

```bash
for i in $(seq 1 4); do
  curl -s -o /dev/null -D - -H "X-API-Key: demo" http://localhost:8080/api/ping \
    | grep -Ei "^(HTTP/|X-RateLimit|Retry-After)"
done
```

The fourth call returns `429` with `Retry-After` and a problem-details body.
Override any setting on the command line:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--ratelimiter.limit=10
```

---

## What exists today

The **enforcement layer** is built; the **adaptation layer** is not. Concretely:

| Piece | Status |
|---|---|
| Token bucket with continuous refill, per client key | Done |
| Servlet filter, response headers, `429` + problem details | Done |
| Cost-weighted accounting (`tryAcquire(key, cost)`) | Plumbed through, always charged 1 |
| Fail-open on limiter failure | Done |
| Idle bucket eviction so memory stays bounded | Done |
| Limit derived from SLO feedback | Not started |
| Per-client EWMA profiles, fair share, degradation ladder | Not started |
| Shared state across instances | Not started — buckets are per-process |

### Where the intelligence plugs in

`LimitResolver` is the seam. It answers one question — *what is this client's limit
right now?* — and today `StaticLimitResolver` returns the configured constant. Making
the limiter adaptive means replacing that one bean with a resolver fed by the
controller. The enforcement path does not change.

```
ai/assistiv/ratelimiter/
├── core/
│   ├── RateLimiter.java             # admission check, cost-aware
│   ├── TokenBucketRateLimiter.java  # lock-free CAS bucket, per key
│   ├── LimitResolver.java           # <- the adaptive seam
│   ├── StaticLimitResolver.java     #    today: a constant
│   ├── RateLimitDecision.java       # allowed, limit, remaining, retry-after, reason
│   ├── LimitReason.java             # quota | system-pressure | client-deviation | fair-share
│   └── TimeSource.java              # injectable clock, so refill is testable
├── config/                          # properties, bean wiring, idle-bucket sweep
└── web/                             # filter, key resolver, demo endpoint
```

---

## Status

Early. The approach above is the target; a static token bucket is what runs today.
See *What exists today* for the line between the two.
