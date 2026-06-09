<!-- MANIFEST
schema: review-file/v1
review: consistency-gate-verification
iter: 1
role: reviewer-plan
phase: 2
findings: 0
verdicts: {CR1: VERIFIED, CR2: VERIFIED, CR3: VERIFIED, CR4: VERIFIED}
overall: PASS
by_sev: {blocker: 0, should-fix: 0, suggestion: 0}
by_class: {mechanical: 0, design-decision: 0}
evidence_base: {refs: 4, flows: 0, invariants: 0, matches: 4, non_matches: 0}
tooling: grep+Read (markdown literal-text checks — identifier renames, line-number text, presence/absence of two named guards; PSI adds nothing and carries no polymorphism risk for markdown string verification, per spawn note)
index: []
-->

# Consistency-gate verification — YTDB-820 transaction-scoped query result cache (iteration 1)

BLUF: all four iteration-1 consistency findings are VERIFIED fixed in the plan and
track files. The two-guard re-entrancy model (CR1) is now described consistently
across track-1's four sections and the plan Component Map — `cacheCodeDepth`
(tx-level depth counter) and `inFlightLookup` (lookup-level boolean) both appear,
both labelled new, and both present in the track-1 Interfaces list. `STATEMENT_CACHE`
is gone from plan/track (CR2), `matchesFilters` carries the `(Identifiable,
CommandContext)` signature everywhere (CR3), and `beginInternal` is `164` in plan
and track-1 (CR4). design.md is unchanged, as required by the Phase-2 freeze — its
stale halves are the recorded Phase-4 reconciliation, not regressions. No new
plan-internal inconsistency was introduced. Overall PASS.

#### Verify CR1: two-guard re-entrancy model (cacheCodeDepth + inFlightLookup)
- **Original issue**: design/plan described the re-entrancy guard two ways — a non-existent `cacheCodeDepth` in three design passages vs. the `inFlightLookup` boolean elsewhere. User resolved to TWO guards: lookup-level `inFlightLookup` on `QueryResultCache` AND a new tx-level `cacheCodeDepth` depth counter on `FrontendTransactionImpl`; both new, neither in code today.
- **Fix applied**: plan Component Map and track-1 §Context/§Plan-of-Work(steps 7,10)/§Interfaces rewritten to describe both guards and their distinct roles.
- **Re-check**:
  - Search performed: `grep -rn "cacheCodeDepth|inFlightLookup"` across `implementation-plan.md` and `plan/track-*.md`; targeted Read of track-1 lines 41-160 and 199-239.
  - Code location (plan/track text, the surfaces under fix):
    - **Plan Component Map**: `implementation-plan.md:75` introduces the `cacheCodeDepth` tx-level counter on `FrontendTransactionImpl`; `:80-82` lists the `inFlightLookup` boolean on `QueryResultCache` and explicitly pairs it with `cacheCodeDepth` ("two guards", "per CR1").
    - **track-1 §Context and Orientation**: `:53-57` adds `cacheCodeDepth` to `FrontendTransactionImpl` as the "CR1 resolution — two re-entrancy guards", states it is distinct from `QueryResultCache.inFlightLookup` (the lookup-level boolean), and that it does not exist today.
    - **track-1 §Plan of Work**: step 7 (`:131-139`) names both — `inFlightLookup` boolean on the cache plus a "new tx-level `cacheCodeDepth` counter on `FrontendTransactionImpl`", with the depth>0 bypass for UDF-in-WHERE re-entry and "Both are created here — neither exists in the codebase today". Step 10 (`:146-153`) wires the `cacheCodeDepth` increment/decrement bracketing and the `cacheCodeDepth > 0` bypass.
    - **track-1 §Interfaces and Dependencies**: `:206-207` "In scope (modified)" lists `FrontendTransactionImpl (cache field, mutationVersion, cacheCodeDepth re-entrancy depth counter, getQueryResultCache, clear hooks)`.
  - Current state: both guards named, role-differentiated (tx-level depth counter vs. lookup-level boolean), flagged new, present in the Interfaces list. No section names one guard while omitting the other.
- **Regression check**: checked the track-1 Component Map Mermaid (gate node `:86` reads "re-entrancy" generically — no contradiction) and the §Interfaces "In scope (new)" `QueryResultCache` entry (`:201-204`) — `inFlightLookup` is an internal field of a new class, correctly not separately enumerated. design.md left frozen with its `cacheCodeDepth`/`inFlightLookup` internal drift intact (3 `cacheCodeDepth` matches) — recorded for Phase-4, not flagged. Clean.
- **Verdict**: VERIFIED

#### Verify CR2: STATEMENT_CACHE → YqlStatementCache
- **Original issue**: plan D2 and track-1 referred to the parser AST cache as `STATEMENT_CACHE`, a symbol that does not exist; the real class is `YqlStatementCache`. The `STATEMENT_CACHE_SIZE` knob name is correct and stays.
- **Fix applied**: `STATEMENT_CACHE` → `YqlStatementCache` in plan D2 and track-1.
- **Re-check**:
  - Search performed: `grep -n "STATEMENT_CACHE"` across `implementation-plan.md` and `plan/track-*.md` (catches both the bare symbol and `_SIZE` suffix); Read of plan D2 (`:117-131`).
  - Code location: zero matches for any `STATEMENT_CACHE*` string in plan/track. Plan D2 now reads "`YqlStatementCache` returns the same instance for identical text" (`:124`) and "deep-AST equality is new ground (`YqlStatementCache` keys by text)" (`:126`); track-1 uses `YqlStatementCache` at `:59` and `:69`.
  - Current state: no stray `STATEMENT_CACHE` remains. The `STATEMENT_CACHE_SIZE` knob is not referenced in plan/track (it was a design.md-only citation), so nothing correct was over-renamed.
- **Regression check**: confirmed the D12 identity-fast-path claim survives intact — plan D2 still asserts `CacheKey.equals` short-circuits on `==` because the cache returns the same instance for identical text (`:124-125`), now attributed to the correctly-named `YqlStatementCache`. Clean.
- **Verdict**: VERIFIED

#### Verify CR3: matchesFilters(record, ctx) → matchesFilters(Identifiable, CommandContext)
- **Original issue**: plan + track-1 + track-3 cited `SQLWhereClause.matchesFilters(record, ctx)`; the real overload binding for a `RecordAbstract` argument is `matchesFilters(Identifiable, CommandContext)`.
- **Fix applied**: signature corrected to `matchesFilters(Identifiable, CommandContext)` with a "`RecordAbstract` binds via `Identifiable`" note, in plan + track-1 + track-3.
- **Re-check**:
  - Search performed: `grep -rn "matchesFilters"` across `implementation-plan.md` and `plan/track-*.md`.
  - Code location: all four occurrences now carry the precise signature —
    - `implementation-plan.md:319` Integration Points: `SQLWhereClause.matchesFilters(Identifiable, CommandContext)` ... "(`RecordAbstract` binds via `Identifiable`)".
    - `plan/track-1.md:238` Key signatures: `SQLWhereClause#matchesFilters(Identifiable, CommandContext): boolean` (existing, reused; `RecordAbstract` binds via `Identifiable`).
    - `plan/track-3.md:50` and `:195`: both `matchesFilters(Identifiable, CommandContext)`, reused per alias.
  - Current state: no `matchesFilters(record, ctx)` or `matchesFilters(op.record, ...)` remains in plan/track. The imprecise rendering is gone everywhere it was flagged.
- **Regression check**: the `RecordAbstract`-binds-via-`Identifiable` note preserves the original call-site intent (the delta-build re-evaluates WHERE against `op.record`, a `RecordAbstract`), so no semantic drift. Clean.
- **Verdict**: VERIFIED

#### Verify CR4: beginInternal line 165 → 164
- **Original issue**: `beginInternal()` is declared at line 164 (line 165 is its first body statement); plan/track cited 165.
- **Fix applied**: standardized on `beginInternal` line 164 in plan + track-1.
- **Re-check**:
  - Search performed: `grep -rn "beginInternal"` across `implementation-plan.md` and `plan/track-*.md`.
  - Code location: every line-bearing citation is now 164 —
    - `implementation-plan.md:314` Integration Points: "`beginInternal` (164)".
    - `plan/track-1.md:49-50` §Context: "`beginInternal` (line 164)".
    - Two further `beginInternal` mentions carry no line number (plan Component Map `:78`; track-1 step 10 `:152`) — consistent, nothing to correct.
  - Current state: no `beginInternal` cited as 165 anywhere in plan/track.
- **Regression check**: cross-checked the sibling lifecycle citation `clearUnfinishedChanges (≈998)` (plan `:315`, track-1 `:50`) — unchanged and untouched by this fix. Clean.
- **Verdict**: VERIFIED

## design.md freeze confirmation (not a finding)

Per Phase-2 rules, design.md is frozen and was not mutated. Confirmed: design.md
still holds 8 `STATEMENT_CACHE` matches, 3 `cacheCodeDepth` matches, 1
`matchesFilters(op.record` match, and its `beginInternal` 165 text. These are the
recorded findings DEFERRED to the Phase-4 `design-final.md` reconciliation — the
expected end state (plan/track fixed, design.md unchanged), not regressions.

## Findings

No new findings. The re-scan of the modified plan/track sections (Component Map,
track-1 §Context/§Plan-of-Work/§Interfaces, plan Integration Points + D2, track-3
matchesFilters citations) surfaced no fix-shifted inconsistency: no guard is named
in one section and missing from another, no rename left a dangling reference, and
no corrected line number contradicts a sibling citation.

## Summary

PASS. CR1, CR2, CR3, CR4 all VERIFIED in the plan and track files. No remaining
blockers; no new findings. design.md correctly left frozen for Phase-4
reconciliation.
