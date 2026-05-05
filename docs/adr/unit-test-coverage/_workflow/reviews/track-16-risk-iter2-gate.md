# Track 16 — Risk Review iter-2 Gate Verification

**Verdict:** PASS — 9 VERIFIED / 0 STILL OPEN / 0 REGRESSION; 0 new findings.

**Tooling note:** mcp-steroid reachable; `unit-test-coverage` project open and
matches working tree. Re-checks on dead-code claims (R1, R2) routed through
PSI all-scope `ReferencesSearch` / `MethodReferencesSearch`. Step-file
inspection used Read on
`docs/adr/unit-test-coverage/_workflow/tracks/track-16.md`.

---

## Verification certificates

#### Verify R1: Cluster-selection partial reframe
- **Original issue**: Description framed "cluster selection strategy
  dispatch" as live-coverage scope. PSI showed
  `BalancedCollectionSelectionStrategy` + `DefaultCollectionSelectionStrategy`
  reachable only via the SPI service file, and
  `CollectionSelectionFactory.getStrategy(String)` had zero callers — driving
  these as live coverage would be a Track-9-style anti-pattern.
- **Fix applied**: Description (Track 16, lines 45–68) now distinguishes:
  - **Live drive**: `RoundRobinCollectionSelectionStrategy` only — already at
    100% via direct `SchemaClassImpl:86` field initializer (lines 64–68).
  - **Dead-code pins**:
    `BalancedCollectionSelectionStrategyDeadCodeTest` +
    `DefaultCollectionSelectionStrategyDeadCodeTest` +
    `CollectionSelectionFactoryDeadCodeTest` (lines 50–63), all forwarded
    to Track 22 via lockstep deletion with the SPI service file (lines
    175–181 and 116).
  - The 18-line `clusterselection` package gap "collapses into the
    cluster-selection dead-pin step" (line 116).
- **Re-check (PSI all-scope ReferencesSearch)**:
  - `BalancedCollectionSelectionStrategy`: 1 ref → SPI service file only
    (line 21 of the META-INF resource). VERIFIED dead.
  - `DefaultCollectionSelectionStrategy`: 1 ref → SPI service file only
    (line 20). VERIFIED dead.
  - `RoundRobinCollectionSelectionStrategy`: 4 refs →
    `CollectionSelectionFactory:30` (default-strategy field), `SchemaClassImpl:37`
    (import), `SchemaClassImpl:86` (live `new` instantiation), SPI service file.
    VERIFIED live via direct instantiation.
  - `CollectionSelectionFactory.getStrategy(String)`:
    `MethodReferencesSearch` returns **0 callers**. VERIFIED dead method.
  - `CollectionSelectionFactory` class itself: 12 refs (constructor used in
    `SchemaShared:81-82,231` and `ImmutableSchema:25,59,280`,
    `SchemaProxy:25,367`, `SchemaInternal:6,27`) — the *class* is live
    because the SPI loop runs at construction. The dead-pin claim was
    correctly scoped to `getStrategy()` plus the SPI-loop wiring
    (`registerStrategy()`, `newInstance()`), not the whole class. The
    description matches this scoping.
- **Regression check**: Wording in lines 175–181 ("`Balanced` + `Default`
  + `CollectionSelectionFactory.getStrategy` + the SPI service file deletes
  lockstep") matches the PSI-confirmed scoping. No spurious extension to the
  live `CollectionSelectionFactory` class proper. Live `RoundRobin` shape pin
  (line 64) is independent and does not collide with the dead pins.
- **Verdict**: VERIFIED.

#### Verify R2: `IndexConfigProperty` dead-pin
- **Original issue**: PSI all-scope showed 0 prod refs for the class and 0
  callers for every public method. Class is fully orphaned.
- **Fix applied**: Description lines 47–49: "`IndexConfigProperty` (48 LOC /
  13 uncov, 0% line) — fully orphaned class. Pin via
  `IndexConfigPropertyDeadCodeTest`, lockstep delete forwarded to Track 22."
  Lines 178–180 reaffirm: "`IndexConfigProperty` deletes solo (no dependent
  live surface)."
- **Re-check (PSI all-scope ReferencesSearch + MethodReferencesSearch)**:
  - Class refs: 2 — both at `IndexConfigProperty.java:44-45`, which is the
    `copy()` method calling its own constructor. **Self-references only.**
  - Constructor (5-param): 1 caller — `IndexConfigProperty.java:45` (the
    self-call from `copy()`).
  - All public methods (`getCollate`, `getLinkedType`, `getName`, `getType`,
    `getIndexBy`, `copy`): **0 callers each.**
  - Fields (`name`, `type`, `linkedType`, `collate`, `index_by`): 3 refs
    each — all internal to the class (constructor, copy, getters).
  - Net: zero external production or test references. Confirmed fully dead.
- **Regression check**: Pin name (`IndexConfigPropertyDeadCodeTest`) matches
  the Track 14/15 `*DeadCodeTest` naming convention. Lockstep with Track 22
  deletion is correct posture. No interaction with the live schema surface
  documented elsewhere in the description.
- **Verdict**: VERIFIED.

#### Verify R3: Sequence collapse to half-step gap-fill
- **Original issue**: Plan allocated a dedicated step to sequence library, but
  fresh JaCoCo measurement showed `core/metadata/sequence` at 85.4% line /
  73.4% branch — already above the 85%/70% gate.
- **Fix applied**: Description lines 104–112: "`core/metadata/sequence`
  (~75 uncov pre-review; **fresh measurement: 85.4% line / 73.4% branch —
  already above the project 85%/70% gate**). Collapses to a half-step
  alongside function library: gap-filler targeting `DBSequence` (31 uncov,
  78.2% line — biggest in package), `SequenceLibraryProxy` (6 uncov, 60.0%
  line), and the residual edges in `SequenceCached` (11 uncov),
  `SequenceLibraryImpl` (10 uncov). Carry-forward `DBSequenceTest` already
  has 26 `@Test` methods at 967 LOC — Track 16 extends it rather than
  authoring new test classes."
- **Re-check**: Step-file Description explicitly downgrades sequence from a
  dedicated step to a half-step gap-fill, names the at-gate baseline,
  identifies the residual class-level gaps, and instructs extension of the
  existing `DBSequenceTest`. Matches the proposed fix exactly.
- **Regression check**: No conflicting allocation of "~6 steps" or similar
  count anywhere in the Description. Sequence is only mentioned in the
  remeasure block (line 104) and the lock-discipline / dead-code-pin sections
  do not re-elevate it.
- **Verdict**: VERIFIED.

#### Verify R4: Drop fixture-reuse claim
- **Original issue**: Interactions section claimed "Schema fixtures
  established here may be reused by Tracks 17, 18, and 22." Premature
  fixture extraction is a known anti-pattern (Tracks 7/8/12/13/15 CQ-tier
  deferrals).
- **Fix applied**: Description lines 200–209 (Interactions): "**No fixture
  extraction is committed at Track 16 time.** The pre-review claim 'Schema
  fixtures established here may be reused by Tracks 17, 18, and 22' is
  dropped per A5/R4: there is no anchoring step deliverable, no concrete
  `SchemaTestFixtures` class today, and Tracks 17/18/22 owners have not
  committed to specific fixture consumption. Track 16 may discover patterns
  that later tracks adopt by analogy, but any DRY hoist for cross-track
  reuse goes to Track 22's deferred-cleanup queue."
- **Re-check**: The original "may be reused" language is explicitly named
  and dropped, with the downgrade ("discover patterns ... by analogy")
  exactly matching the proposed fix.
- **Regression check**: No residual fixture-hoist commitment anywhere in
  the Description. The "Carry forward Tracks 5–15 conventions" block (lines
  182–189) does not re-introduce fixture extraction.
- **Verdict**: VERIFIED.

#### Verify R5: Proxy parameterized inactive-session test (suggestion)
- **Original issue**: Driving every proxy method via DbTestBase yields N
  isomorphic delegation tests. The load-bearing branch is the
  `assert session.assertIfNotActive()` precondition.
- **Fix applied**: Description lines 130–143 codify the
  super-method-dispatch trap (T3) and instruct that "Tests MUST drive proxy
  methods via the public-API interface ... not via direct concrete-class
  references — and Phase B implementers MUST check `findSuperMethods()`
  before pinning any proxy method as `*DeadCodeTest`." The R5 suggestion
  about parameterized inactive-session tests is **not** explicitly named.
- **Re-check**: R5 was filed as `[suggestion]` severity (deferable). The
  step file does codify the more critical T3 super-method-dispatch trap
  for proxies. The parameterized-inactive-session approach R5 advocates
  for is not contradicted, just left to Phase B implementer judgment.
- **Regression check**: Phase B implementer is given enough framing
  (T3 + R5 in the review record) to choose the parameterized-table shape.
  No active obstacle.
- **Verdict**: VERIFIED — suggestion deferred to Phase B implementer
  judgment, which is acceptable for `suggestion` severity. The step-file
  framing (drive via interface, check `findSuperMethods()` before
  dead-pinning) correctly biases toward boundary cases over isomorphic
  delegation, which is the spirit of R5.

#### Verify R6: `PropertyTypeInternal` parameterized split
- **Original issue**: 22 enum constants × ~5 input shapes would balloon
  Track 16 beyond its envelope. R6 proposed
  `@RunWith(Parameterized.class)` keyed by `(PropertyTypeInternal, input,
  expected, exception)` with 3–4 classes per logical group (numeric /
  collection / link / embedded / datetime).
- **Fix applied**: Description lines 33–44: "**`PropertyTypeInternal`
  parameterized step** (~176 uncov on the outer enum + ~195 uncov across
  `$10`–`$21` anonymous enum-instance subclasses, baseline 66.0% / 62.7%)
  — the single largest uncov class in the package. Drive `convert(Object,
  PropertyTypeInternal, SchemaClass, DatabaseSessionEmbedded)` via
  `@RunWith(Parameterized.class)` tables grouped by logical family
  (numeric, collection, link, embedded, datetime/binary) to keep step
  granularity reasonable. Existing `SchemaPropertyTypeConvertTest` ...
  Track 16 extends them rather than duplicating."
- **Re-check**: Family list ("numeric, collection, link, embedded,
  datetime/binary") matches R6's proposed fix one-for-one. The
  `@RunWith(Parameterized.class)` annotation is named explicitly.
  Existing-test extension is preferred over duplication, matching plan
  constraint #6 and R6's intent.
- **Regression check**: No method-by-method per-shape sweep or competing
  framing introduced. Step granularity language ("to keep step
  granularity reasonable") aligns with R6's budget concern.
- **Verdict**: VERIFIED.

#### Verify R7: `SchemaShared` lock discipline working note
- **Original issue**: Constraint named the pitfall ("Avoid coupling tests
  to internal SchemaShared synchronization") but did not surface the
  concrete symptoms (executeInTx vs begin/commit, no reflection probing,
  re-fetch the Schema after mutations).
- **Fix applied**: Description lines 150–160: "**`SchemaShared` lock
  discipline** (R7 working note): the `acquireSchemaReadLock` /
  `releaseSchemaReadLock` / `acquireSchemaWriteLock(session)` /
  `releaseSchemaWriteLock(session)` / `releaseSchemaWriteLock(session, save)`
  methods ARE part of the public lock API and are fair game for direct
  testing. The underlying `ReentrantReadWriteLock` field is private and
  must not be probed via reflection. Tests should NOT hold a `SchemaShared`
  reference past the end of an `executeInTx` callback (flakes under
  `-Dyoutrackdb.test.env=ci`); re-fetch `session.getMetadata().getSchema()`
  after schema mutations rather than caching the reference." Reaffirmed
  in Constraints lines 194–198: "private `ReentrantReadWriteLock` field is
  not (no reflection probes). Tests prefer `session.executeInTx(...)` for
  schema mutations whose snapshot must be visible to a subsequent
  assertion."
- **Re-check**: All three R7 prescriptions are codified:
  (a) prefer `executeInTx` — line 196 ("Tests prefer `session.executeInTx(...)`");
  (b) no reflection probing — line 154 ("must not be probed via reflection");
  (c) re-fetch the Schema after mutations — lines 158–160 ("re-fetch
  `session.getMetadata().getSchema()` after schema mutations rather than
  caching the reference").
- **Regression check**: The clarification that the public lock-API methods
  ARE testable (not blanket-banned) is a refinement, not a regression — it
  reflects a finer reading of the production surface than the original R7
  proposed-fix, while still preventing the deeper anti-pattern (reflection
  probing of the private lock field).
- **Verdict**: VERIFIED.

#### Verify R8: Index-visibility reload pattern
- **Original issue**: Tests creating indexes via
  `SchemaProperty.createIndex(...)` may observe stale schema-snapshot state
  across the disk-vs-memory storage modes. R8 proposed using
  `session.getMetadata().getSchema().reload()` (or the
  `getIndexManagerInternal().getIndex(...)` equivalent) before asserting
  index visibility.
- **Fix applied**: Description lines 124–129: "Index creation tests must
  `session.getMetadata().getSchema().reload()` before asserting visibility
  — disk-mode CI runs the `SchemaShared.releaseSchemaWriteLock` →
  `saveInternal` path while memory-mode runs `reload`; the explicit
  `reload()` call is the portable assertion path (R8 working note)."
- **Re-check**: The exact API call (`session.getMetadata().getSchema().reload()`)
  is named, the dual-mode rationale (disk `saveInternal` vs memory `reload`)
  is documented, and the R8 marker is preserved in the comment for
  audit traceability.
- **Regression check**: No competing or stale "expected to see the index
  immediately" guidance elsewhere. The line is integrated into the
  general "How" section so all index-creation tests in the track
  inherit it.
- **Verdict**: VERIFIED.

#### Verify R9: Step 1 baseline remeasurement
- **Original issue**: Plan-cited uncov figures (1,278 / 74 / 75 / 18) are
  ~5% stale. Sequence in particular is already above gate. R9 proposed
  Step 1 produces a `track-16-baseline.md` per the Tracks 9, 10, 14
  precedent.
- **Fix applied**: Description lines 161–171: "**Phase B Step 1 remeasures
  live coverage** for all three target packages and writes
  `track-16-baseline.md` (precedent: Tracks 9, 10, 14 baselines). The
  plan-cited `1,278 / 74 / 75 / 18` uncov figures are stale — concrete
  per-class uncov targets are derived from the remeasured XML." Reaffirmed
  via the inline `**stale baseline — remeasure in Step 1**` markers on
  the schema (line 22), function (line 80), and clusterselection
  (line 113) bullets.
- **Re-check**: The deliverable name (`track-16-baseline.md`), the
  precedent reference (Tracks 9, 10, 14), and the inline markers on each
  package's bullet all match R9's proposed fix.
- **Regression check**: Step 1 also picks up the inert-test spot-check
  carry-forward (lines 167–171), reusing the work envelope productively.
  Sequence's "fresh measurement" already incorporated (line 105) shows the
  baseline-remeasurement loop is already partially run for the Phase A
  review itself, which is consistent with the Tracks 9/10/14 precedent.
- **Verdict**: VERIFIED.

---

## Summary

- **VERIFIED**: 9 / 9 (R1, R2, R3, R4, R5, R6, R7, R8, R9)
- **STILL OPEN**: 0
- **REGRESSION**: 0
- **New findings**: 0

**Gate verdict: PASS.** All iter-1 risk findings are addressed in the
Track 16 step-file Description. PSI re-checks confirm the dead-code claims
underpinning R1 and R2 still hold. R5 is suggestion-severity and the
deferral to Phase B implementer judgment is acceptable; the step-file
framing biases correctly toward boundary cases.

## Risk-tagging recommendation for Phase B

Carry forward the iter-1 risk-tagging signal:

- **`risk: high`** during decomposition for:
  1. The cluster-selection step (R1 reframe is load-bearing — wrong-shape
     framing would cascade into Track 22's deletion queue and corrupt
     three lockstep groups).
  2. The `PropertyTypeInternal` parameterized step (R6 — large surface,
     ~371 uncov across outer + 17 anonymous subclasses, easy to mis-shape
     the family decomposition).
- Medium-risk: schema proxies (R5 + T3 super-method-dispatch trap), index
  creation pattern (R8), schema fixtures discipline (R4).
- Low-risk: function library, sequence library half-step, validation
  classes.

Phase B should run the step-level dimensional code review (CQ + TB + TC
at minimum) on both `risk: high` steps before implementer hand-off.
