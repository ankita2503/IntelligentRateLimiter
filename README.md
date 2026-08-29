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

## Configuration

The interesting property is what is **absent**. There is no request-per-minute
number, no latency SLO, no tenant list, no per-client quota — those are derived
from traffic. What remains is of three kinds: safety rails, time constants, and
one sensitivity dial.

```yaml
ratelimiter:
  refill-period: 1m
  fail-mode: OPEN
  key-header: X-API-Key
  adaptive:
    enabled: true
    # Time constants: how fast to react, how long to remember.
    control-interval: 1s
    window: 30s
    baseline-half-life: 5m
    warmup-ticks: 20
    # Safety rails: the controller moves freely between these and nowhere else.
    min-budget: 50
    max-budget: 5000
    min-client-limit: 5
    # Sensitivity: deviations above the learned baseline that count as anomalous.
    deviation-sigma: 3.0
    # Optional hard SLO. Zero means judge latency only against what it has been.
    latency-ceiling: 0s
```

Setting `adaptive.enabled: false` falls back to a fixed `ratelimiter.limit`,
which is useful for comparison and for the enforcement tests.

---

## Roadmap

- [x] Core token bucket, per-client, in-memory
- [x] Telemetry: sliding-window p99 latency, error rate, throughput, in-flight
- [x] Learned baselines — the limiter discovers its own thresholds
- [x] Slow-start + AIMD controller with bounded budget adjustment
- [x] Per-client EWMA profiles and deviation detection
- [x] Fair share across the observed client population
- [x] Explainable denials (`X-RateLimit-Reason`) and a state endpoint
- [ ] Weighted priority tiers on top of equal fair share
- [ ] Cost-weighted accounting (the plumbing exists; every request charges 1)
- [ ] Degradation ladder: shed-optional and queue steps before rejection
- [ ] Distributed state, so instances share one budget
- [ ] PID controller as an alternative strategy
- [ ] Load-test harness: burst, sustained flood, noisy neighbour, degraded downstream

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

### Watching it adapt

`/api/slow?ms=400` stands in for a degraded dependency, and
`/actuator/ratelimiter` reports what the limiter has learned:

```bash
mvn spring-boot:run

# what it currently believes
curl -s localhost:8080/actuator/ratelimiter | python3 -m json.tool

# healthy load: the budget climbs
while true; do
  for i in $(seq 1 25); do curl -s -o /dev/null -H "X-API-Key: load" localhost:8080/api/ping & done
  wait
done

# then degrade the dependency and watch the budget collapse
while true; do
  for i in $(seq 1 25); do curl -s -o /dev/null -H "X-API-Key: load" "localhost:8080/api/slow?ms=400" & done
  wait
done
```

Shorten `window`, `baseline-half-life`, and `warmup-ticks` to see it react within
seconds rather than minutes.

---

## What exists today

Both layers are built. Enforcement is a token bucket; the limit it enforces is
produced by the adaptive layer on a one-second control loop.

| Piece | Status |
|---|---|
| Token bucket with continuous refill, per client key | Done |
| Servlet filter, response headers, `429` + problem details | Done |
| Fail-open on limiter failure, idle bucket eviction | Done |
| Sliding-window telemetry: p99, error rate, throughput, in-flight | Done |
| Learned latency/error baselines — no configured SLO required | Done |
| Slow-start + AIMD budget controller, bounded by rails | Done |
| Per-client EWMA profiles and deviation scoring | Done |
| Fair share across the observed client population | Done |
| Explainable denials + `/actuator/ratelimiter` state endpoint | Done |
| Weighted priority tiers | Not started — every client shares equally |
| Cost-weighted accounting | Plumbed through `tryAcquire(key, cost)`, always 1 |
| Shed-optional and queue steps of the degradation ladder | Not started |
| Shared state across instances | Not started — buckets are per-process |

### What is learned, and what is configured

This is the part that differs from a conventional limiter.

| Quantity | Where it comes from |
|---|---|
| How much capacity exists | Discovered: slow start doubles the budget while healthy, AIMD from there |
| What counts as "too slow" | Learned: EWMA mean + σ of this service's own p99 |
| What counts as "too many errors" | Learned: same mechanism on error rate |
| A client's normal request rate | Learned: EWMA per key, judged against itself |
| How many clients share the budget | Observed: distinct keys seen in the activity window |
| Floor and ceiling on the budget | **Configured** — bounded authority, by design |
| Reaction speed and memory | **Configured** — control interval, window, half-life |
| Anomaly sensitivity | **Configured** — one σ value |

### How the limit is produced

```
limit = budget          discovered by the controller from latency and errors
      x fair share      1 / observed active clients
      x reputation      1.0, or σ/z when this client deviates from its own baseline
```

clamped to `[min-client-limit, max-budget]`. The smallest of the three factors is
reported as `X-RateLimit-Reason`, so a rejected caller learns whether the system
was under pressure, the tenant pool was crowded, or its own behaviour was the
problem.

### Two rules that keep it stable

Most of the subtlety in an adaptive limiter is in what it refuses to do:

1. **Learn only while healthy.** Baselines are updated on healthy ticks only. A
   limiter that learns during an incident normalises the degradation and stops
   protecting anything — the same trap applies per-client, where a sustained
   flood would otherwise become the attacker's accepted normal.
2. **Probe only while saturated.** The budget grows only when traffic is actually
   using most of it. Otherwise an idle service drifts to its ceiling and admits a
   flood the moment traffic returns.

### Seeing it work

Driving real traffic through a healthy service, then a degraded one, then
recovery — with nothing reconfigured at any point:

| Phase | Budget | Learned threshold | Observed p99 | State |
|---|---|---|---|---|
| Cold start | 20 (floor) | — | — | `SLOW_START` |
| Fast traffic, saturating | **1280** | 16ms | 2ms | `SLOW_START` |
| Dependency slows to 400ms | **20** | 16ms | 418ms | pressured, z=137 |
| Dependency recovers | **33** | 16ms | 0ms | probing back additively |

Nobody chose 16ms. The limiter measured what this service normally does and
derived the threshold from it — and on recovery it climbs back by 5% steps rather
than jumping to 1280 and re-breaking what just healed.

```
ai/assistiv/ratelimiter/
├── core/                            enforcement — cheap, no thinking
│   ├── RateLimiter.java             admission check, cost-aware
│   ├── TokenBucketRateLimiter.java  lock-free CAS bucket, per key
│   ├── LimitResolver.java           the seam between the two layers
│   ├── ResolvedLimit.java           the limit, and which factor set it
│   └── LimitReason.java             quota | system-pressure | client-deviation | fair-share
├── adaptive/                        adaptation — all off the hot path
│   ├── SlidingWindowTrafficMetrics  p99 / errors / throughput over a ring of slots
│   ├── LatencyHistogram.java        fixed-memory percentiles
│   ├── EwmaBaseline.java            learns normal, scores deviation
│   ├── CapacityController.java      slow start, AIMD, bounded authority
│   ├── ClientProfileRegistry.java   per-client baselines, fair-share population
│   ├── AdaptiveLimitResolver.java   budget x share x reputation
│   └── AdaptiveControlLoop.java     the tick
├── config/                          properties, wiring, maintenance
└── web/                             filter, key resolver, state endpoint, demo
```

---

## Status

The adaptive core is built and tested: 77 tests, including a closed-loop
simulation that drives a service through health, degradation, and recovery on a
fake clock. What remains is listed in the roadmap — priority tiers, real cost
weighting, the middle steps of the degradation ladder, and shared state across
instances.
