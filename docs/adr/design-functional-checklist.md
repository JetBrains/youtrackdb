# Functional Plan Cache — Checklist

Speaker cheat-sheet. Glance at this during the talk to make sure nothing
gets dropped. Each line is one beat.

## The pitch (must say upfront)

- [ ] Problem: ~6% CPU on LDBC IS-1 spent copying plans on every cache hit
- [ ] Solution: stateless steps + immutable plans + zero-copy cache
- [ ] Size: ~120 files, 5 phases, each independently shippable
- [ ] Bonus: unlocks plan caching for queries that currently bypass cache (PR #880)

## How execution works today (background)

- [ ] Parser → AST (tree of Java objects)
- [ ] Planner → ExecutionPlan (chain of steps)
- [ ] Engine → ExecutionStream (lazy pull-based)
- [ ] Plan cache memoizes the planner output (key = SQL text)

## Why we copy today

- [ ] Steps embed per-execution state: `prev`, `next`, `ctx`, `alreadyClosed`
- [ ] Two threads sharing one step instance would race on those fields
- [ ] Deep-copy on cache hit isolates each execution — correct but ~130 allocations

## The three hotspots (LDBC IS-1)

- [ ] `toGenericStatement` — **3.6% — dead code, zero callers**
- [ ] `SQLSuffixIdentifier.copy` — 1.5% — recursive AST deep-copy
- [ ] `SelectExecutionPlan.copyOn` — 1.0% — step chain copy

## The proposal in three sentences

- [ ] Steps become pure functions: `execute(upstream, ctx) → stream`
- [ ] Plans become immutable lists of those steps
- [ ] Cache returns the same plan object to every caller — no copy

## Allocation impact

- [ ] Before: ~130 allocations per cache hit
- [ ] After: ~13 (just stream decorators)
- [ ] Plus: `genericStatement` removal alone = clean 3.6%, phase 1, no semantic change

## Migration taxonomy — 4 categories

- [ ] **3.1 Stream transformers** (~45 classes) — mechanical rename
- [ ] **3.2 Source steps** (~20 classes) — `upstream.consume(ctx)` then create stream
- [ ] **3.3 Stateful steps** (6–7 classes) — fields → local vars + closures + onClose
- [ ] **3.4 Control-flow** (4 classes) — left alone (script plans, never cached)

## Stateful step recipe (the interesting one)

- [ ] State as local variable inside `execute()`
- [ ] Captured in stream closure (lambda)
- [ ] Cleanup via `stream.onClose(callback)`
- [ ] Each call to execute → fresh stack frame → fresh state, no sharing
- [ ] **No instance fields, no close override, no shared mutable**

## MatchStep special case

- [ ] `EdgeTraversal.cache` is per-execution (HashMap, not thread-safe)
- [ ] New `EdgeTraversal.withFreshCache()` — wrapper sharing config, fresh empty cache
- [ ] One small allocation per match step per execution

## Three subtleties (the gotchas)

### Backward → forward initialization order

- [ ] Today: terminal step starts first, recurses backward via `prev.start()`
- [ ] After: forward loop, source step initializes first
- [ ] Same final stream chain, different init order

### The FilterStep landmine (silent wrong results)

- [ ] FilterStep registers WHERE expr in ctx **before** calling upstream
- [ ] GlobalLetQueryStep reads that registration to decide whether to materialize
- [ ] In forward order, GlobalLetQueryStep runs first, sees empty registration → wrong materialization
- [ ] **Fix: pre-registration phase** — two-pass `start()`: register all, then execute all
- [ ] Grep confirms FilterStep is the only step with this pattern

### AST thread safety

- [ ] AST nodes have lazy-cache fields (e.g. `aggregate` in `SQLProjectionItem`)
- [ ] Today: deep-copy isolates → no race
- [ ] After: AST shared → concurrent lazy-init = race
- [ ] On x86: benign (strong memory model)
- [ ] On ARM: real bug — unsafe publication, NPE possible
- [ ] **Fix #1:** `volatile` on every lazy field — proper publication
- [ ] **Fix #2:** pre-compute lazy fields at cache-put time — no writes at runtime
- [ ] Why both: pre-compute can be missed; volatile is safety net + needed for genuinely-runtime fields (Collate, locale-dependent)

### Runtime plan mutation

- [ ] One spot in production: `MaterializedLetGroupStep.replaceFirstStep`
- [ ] Today works because cache returns a copy
- [ ] After: `replaceFirstStep` → `withFirstStepReplaced` (returns new plan)
- [ ] Cached plan stays pristine, derivative is ephemeral, doesn't go back to cache
- [ ] Caller must use returned plan, not original variable

## Removed dead code (independent wins)

- [ ] `genericStatement` — 3.6% CPU, zero callers, biggest hotspot
- [ ] `sendTimeout()` — backward propagation chain, 3 no-op overrides, real timeout uses stream-level mechanisms
- [ ] `reset()` — never called on cached plans
- [ ] `prev`/`next` pointers — topology lives in plan's step list
- [ ] `setSteps()` — no callers in main code

## Cache lifecycle changes

- [ ] **Construction:** `chain()` → builder ArrayList + final ctor with `List.copyOf`
- [ ] **Cache put:** + `precomputeLazyCaches(db)` for AST fields
- [ ] **Cache get:** zero copy, return same instance
- [ ] **Plan close:** removed; cleanup propagates through stream decorators
- [ ] **Mutation:** `replaceFirstStep` → `withFirstStepReplaced` (immutable derivative)

## Profiling stays correct (reassure the audience)

- [ ] `StepStats` lives in `CommandContext`, not in step
- [ ] Each thread has own ctx → own stats map
- [ ] Same step instance as key, but different maps → no collision
- [ ] `getCost()` takes `ctx` parameter (was `this.ctx`)

## Deserialize API change

- [ ] Today: instance method `deserialize(Result, session)` mutates fields
- [ ] After: deserializing constructor `new Step(Result, session)`
- [ ] Why: lets fields be truly `final`
- [ ] 10 step classes affected + plan classes + 2 reflection call sites
- [ ] Mechanical, internal only

## Implementation phases (5 PRs)

- [ ] **Phase 1:** Dead code + scaffolding (toGenericStatement, new interface, fallback) — clean 6%, low risk
- [ ] **Phase 2:** ~45 stream transformers (mechanical)
- [ ] **Phase 3:** ~20 source steps + MaterializedLetGroupStep call-site
- [ ] **Phase 4:** 7 stateful steps (the careful one) — hash joins, MatchStep, IndexOrderedEdgeStep
- [ ] **Phase 5:** Cleanup — remove fallback, sendTimeout, reset, prev/next; add volatile + pre-compute; deserialize → ctor
- [ ] Each phase reversible until phase 5 removes legacy fallback

## Coexistence with in-flight PRs

- [ ] **PR #863** (lazy RID iteration) — merged, no design impact
- [ ] **PR #880** (`IndexOrderedEdgeStep`) — open, **headline beneficiary** — `canBeCached()` flips false → true
- [ ] **PR #946** (back-ref hash join, `BackRefHashJoinStep`) — merged, three new stateful steps already accommodated

## Risks (close the talk by addressing them)

- [ ] FilterStep correctness — silent wrong results — pre-registration phase + targeted tests
- [ ] ARM concurrency — silent — volatile + pre-compute + stress tests on Graviton/M-series
- [ ] Deserialize API break — internal only, mechanical, ~12 call sites
- [ ] `replaceFirstStep` call-site — one spot, well-tested, code-review checklist
- [ ] Change size (~120 files) — phased delivery, each PR independent

## Take-aways (close strong)

- [ ] **The hot path is dead code** — biggest hotspot builds a string nobody reads
- [ ] **Stateful steps are the real cost** — 6 classes, the rest is mechanical
- [ ] **Threading solved by the model, not by locks** — pure functions + per-execution stack
- [ ] **The biggest win may not be the 6%** — plan-cache eligibility for previously uncacheable queries

## Things NOT to forget if asked

- [ ] *"Why functional, not just immutable + non-copy?"* — Once steps are shared and stateless, `prev`/`next` must leave them; once they leave, plan must drive iteration; once plan drives, signature is naturally `(upstream, ctx) → stream`. The functional shape is the *consequence*, not a separate decision.
- [ ] *"Why a `List`, not a linked list?"* — Topology must live outside steps (sharing); `List.copyOf` is cheap to copy-on-write; sequential read-heavy access favors arrays; LinkedList's only advantage (middle insert) is unused.
- [ ] *"Why `volatile` AND pre-compute?"* — Pre-compute eliminates the race; volatile catches the few fields that can't be pre-computed (locale-dependent) and serves as safety net for missed pre-computation.
- [ ] *"What about INSERT/UPDATE plans?"* — Not cached, keep old execution model, untouched by this design.
- [ ] *"Index reference live in step — is that safe?"* — Yes, under YTDB's storage-level read concurrency contract; schema changes invalidate plan cache via `MetadataUpdateListener`.

## One-liner if you have 30 seconds

> *We're spending 6% of CPU deep-copying plans on every cache hit because*
> *steps embed mutable state. We make steps pure functions, plans immutable,*
> *and the cache returns the same object to every caller. The biggest hotspot*
> *is dead code we can delete in phase 1. The biggest performance win may not*
> *even be the 6% — it's that index-ordered MATCH queries become cacheable*
> *for the first time.*
