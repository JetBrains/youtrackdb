<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: S3, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Verdicts

#### Verify S3: D19's "never re-read by phase machinery" clause contradicts plan invariant S2
- **Original issue**: D19's Rationale claimed the log is "consumed at
  authoring and never re-read by phase machinery", while plan invariant
  S2 (as resolved through the user-approved S1 fix) sanctions
  post-authoring phase-machinery reads: the Phase-2 consistency
  cross-check (decision content) and the Phase-4 verdict fold. Track 1
  step 1 directs the implementer to write the staged §1.6(f) entry
  "with the append-only/replay-immune rationale (D19)", giving the
  inaccurate clause a concrete propagation path into staged conventions
  text.
- **Fix applied**: The Rationale bullet was reworded to rest the
  replay-immunity conclusion on what holds: "the log is an append-only
  ledger that no §1.6(h) walk enumerates and no phase machinery
  rewrites or re-derives (its post-authoring reads, the S2 sites,
  consume content, never format), so it is replay-immune by
  construction — the same exclusion rationale §1.6(f) already records
  for `design-mutations.md`."
- **Re-check**:
  - Plan location: `implementation-plan.md` `#### D19` Rationale bullet
    (lines 392-397).
  - Current state: the bullet matches the proposed fix. It no longer
    denies reads that plan invariant S2 enumerates; instead it
    acknowledges them by pointer ("the S2 sites") and characterizes
    them as content-consuming, format-independent — exactly the
    property replay-immunity needs.
  - Criteria met: the contradiction criterion clears — no plan
    statement now asserts a read-site rule incompatible with plan
    invariant S2 (lines 409-414). Each leg of the reworded claim
    checks out against the rest of the plan: "no §1.6(h) walk
    enumerates" matches `plan/track-1.md` Context ("§1.6(f) enumerates
    exactly the four stamped artifact types that the frozen
    `workflow-startup-precheck.sh` walk hardcodes") and D19's own
    Risks bullet ("the presence check only fires on enumerated
    types"); "no phase machinery rewrites or re-derives" is consistent
    with D5 (Phase-4 cleanup deletes the log — deletion is neither);
    and the §1.6(f) analogy now lands cleaner than before the fix —
    the live entry for `design-mutations.md` (conventions.md lines
    677-679) records "the append-only contract makes the file
    replay-immune by construction", which is the reworded bullet's
    reasoning, not the old "never re-read" claim.
- **Regression check**: Checked the D19 area and every D19 reference.
  D19's Alternatives and Risks bullets remain coherent with the new
  Rationale (both argue from enumeration scope, untouched by the
  reword). Track 1 step 1 (`plan/track-1.md` lines 107-112) cites the
  rationale by pointer — "the append-only/replay-immune rationale
  (D19)" — so the staged §1.6(f) entry now inherits the corrected
  clause with no track-side text to update; the shorthand still
  summarizes the corrected bullet accurately, and the target anchor
  ("Explicitly NOT stamped", conventions.md line 671) exists. The
  remaining D19 references (plan Component-Map bullet line 106, Track 1
  scope bullet line 471; `plan/track-1.md` lines 83-84 precedent note
  and line 293 "created unstamped (D19)") are all pointer-style and
  restate nothing from the old clause. A grep across the plan, both
  track files, and `design.md` finds no surviving "never re-read"
  text. Two near-misses examined and judged clean: (1) the apposition
  "its post-authoring reads, the S2 sites" sweeps the Step 4a/4b
  authoring-time seeds under "post-authoring" — a mild timing
  looseness, but the apposition pins the referent set to S2 explicitly
  and the content-not-format property holds for every S2 site, so no
  contradiction arises; (2) the §1.6(f) `design-mutations.md` entry
  carries a stamp-equality clause ("whose stamp would always equal
  `design.md`'s") that does not transfer to the log, but D19 and the
  step-1 instruction claim only the shared replay-immunity rationale,
  not that clause. D5's "Consumed by later artifacts, never referenced
  by them" is about cross-linking (design.md's "consumed, not
  referenced" story), not reads — no conflict. `design.md` lines
  343-344 ("read in exactly two places") remain on the frozen
  design-side deferral to Phase 4 established at iteration 1 —
  expected, not a regression. Clean.
- **Verdict**: VERIFIED

## Findings

## Evidence base

No certificates: this review reads no codebase, per the
structural-review contract. The verdict cites plan, track-file,
design, and live-conventions text directly, cross-checked against the
working-tree diff of the applied fix.
