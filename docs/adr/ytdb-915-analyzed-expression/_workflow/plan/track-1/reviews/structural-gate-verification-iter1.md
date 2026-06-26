<!-- manifest
role: reviewer-plan
phase: 2
kind: verdict-producer
findings: 0
evidence_base: 0
verdicts:
  S1: VERIFIED
  S2: VERIFIED
  S3: VERIFIED
  S4: VERIFIED
overall: PASS
-->

# Structural gate-verification — Track 1 fan-out, iter1

Re-check of the four `should-fix` / `mechanical` DR-length-bloat findings (S1–S4)
after their fixes were applied. All four DRs were over the ~30-line soft cap; each
fix trimmed the absorbed long-form material out of the DR body while preserving the
four-bullet decision and pointing to where the full material now lives. This review
reads no codebase (`evidence_base` certs 0). No new finding surfaced (`findings: 0`).

## Verdicts

#### Verify S1: `track-4.md` `#### D11` over the DR soft cap
- **Original issue**: D11 ran ~45 lines — the `**Collation.**` / `**Session
  threading.**` / slow-path worked passages inflated the `**Rationale**` bullet past
  the ~30-line cap.
- **Fix applied**: removed the long-form worked passages; kept a condensed rationale
  that still names both nuances (Collation, Session threading) and the conclusion;
  added an explicit pointer to `design.md §"Comparison: replicate the AST sequence"`.
- **Re-check**:
  - Location: `plan/track-4.md` lines 60–88 (`#### D11`).
  - Current state: header 60 → `**Implemented in**` 87 = 28 content lines (29 incl.
    the trailing `<!-- Full design -->` pointer). The four bullets — Alternatives
    (60–65), Rationale (66–80), Risks/Caveats (81–86), Implemented in (87) — all
    stand. The condensed rationale names **Collation** (the `ci` `'Foo'`/`'foo'`
    case) and **Session threading** (EQ real session vs NE null session via
    `PropertyTypeInternal.convert`) and the parser-operator-instance conclusion;
    the pointer at line 80 resolves to a real `## ` heading (`design.md:673`)
    whose body (lines 686–727) contains both worked passages plus the four-step
    slow-path sequence.
  - Criteria met: DR within the ~30-line soft cap; decision content preserved;
    moved material genuinely reachable at the cited section.
- **Regression check**: scanned the Track-4 Decision Log and the cited design
  section — no dangling "as shown above" reference, no broken bullet, rationale
  still supports the decision. Clean.
- **Verdict**: VERIFIED

#### Verify S2: `track-2.md` `#### D17` over the DR soft cap
- **Original issue**: D17 ran ~39 lines — a fenced dispatch-chain code block plus a
  two-hop-label paragraph inflated the body.
- **Fix applied**: removed the fenced block + the two-hop-label paragraph; kept the
  perf-neutrality claim with an inline one-line chain summary; added a pointer to
  `design.md §"NumericOps: one shared promotion engine"`.
- **Re-check**:
  - Location: `plan/track-2.md` lines 84–111 (`#### D17`).
  - Current state: header 84 → `**Implemented in**` 110 = 27 content lines (28 incl.
    pointer). Four bullets — Alternatives (85–89), Rationale (90–103), Risks/Caveats
    (104–109), Implemented in (110) — all present. The inline summary
    (`operator.apply(Object,Object)` → shared widening entry `apply(Number,Operator,
    Number)` → typed `apply` overload, two virtual dispatches) matches the design's
    full chain at `design.md:644–655`; the pointer at line 103 resolves to a real
    heading (`design.md:591`).
  - Criteria met: within soft cap; perf-neutrality decision intact; full chain
    diagram reachable at the cited section.
- **Regression check**: scanned the Track-2 Decision Log and the cited section —
  inline chain faithful to the design, no dangling reference, no broken bullet.
  Clean.
- **Verdict**: VERIFIED

#### Verify S3: `track-1.md` `#### D8` over the DR soft cap
- **Original issue**: D8 ran ~34 lines — the depth-10 structural-sharing walk-through
  inflated the body.
- **Fix applied**: removed the depth-10 walk-through; kept the per-node rule and the
  by-reference-sharing summary; added a pointer to `design.md §"Transform passes and
  structural sharing"`.
- **Re-check**:
  - Location: `plan/track-1.md` lines 140–163 (`#### D8`).
  - Current state: header 140 → `**Implemented in**` 162 = 23 content lines (24 incl.
    pointer). Four bullets — Alternatives (141–143), Rationale (144–155),
    Risks/Caveats (156–161), Implemented in (162) — all present. The per-node rule
    ("recurse one level; same instance → return input; build new parent only when a
    child changed", reference identity `==`) and the off-path by-reference-sharing
    summary are kept; the pointer at line 155 resolves to a real heading
    (`design.md:320`) whose body (lines 329–361) holds the depth-10-of-50-node
    allocation rule and the `(1 + 2) * x` worked example.
  - Criteria met: within soft cap; per-node decision rule intact; full walk-through
    reachable at the cited section.
- **Regression check**: scanned the Track-1 Decision Log (D8/D9 share the same
  design section; D9 unchanged and still consistent) and the cited section — no
  dangling reference, no broken bullet. Clean.
- **Verdict**: VERIFIED

#### Verify S4: `track-2.md` `#### D5-R` over the DR soft cap
- **Original issue**: D5-R ran ~33 lines — an inlined four-part numbered engine
  enumeration inflated the body.
- **Fix applied**: replaced the numbered enumeration with a one-line in-prose summary
  referencing `## Context and Orientation` (which inventories the engine parts).
- **Re-check**:
  - Location: `plan/track-2.md` lines 57–82 (`#### D5-R`).
  - Current state: header 57 → `**Implemented in**` 81 = 25 content lines (26 incl.
    pointer). Four bullets — Alternatives (57–63), Rationale (64–74), Risks/Caveats
    (75–80), Implemented in (81) — all present. The one-line in-prose summary
    ("the five typed-pair `apply` overloads, the `apply(Object, Object)` fallback,
    the shared `apply(Number, Operator, Number)` widening entry, and the private
    `toLong` helper (inventoried in `## Context and Orientation`)") points to
    `track-2.md §"## Context and Orientation"` (line 116), whose body (lines 123–132)
    enumerates the same four engine parts.
  - Criteria met: within soft cap; whole-enum decision intact; engine-part inventory
    reachable in the same track file's Context section.
- **Regression check**: scanned the Track-2 Decision Log (D5/D5-R/D17 all cite the
  same design section; D5 and D17 unchanged and consistent) and the Context section —
  no dangling reference, no broken bullet. Clean.
- **Verdict**: VERIFIED

## Findings

<!-- No new findings. Pure-verdict pass: all four trims verified, no fix-shifted defect. -->

## Evidence base

<!-- Verdict-producer pass; reads no codebase. evidence_base certs 0. -->
