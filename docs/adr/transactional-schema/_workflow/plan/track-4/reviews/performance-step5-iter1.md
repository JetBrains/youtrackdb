<!--MANIFEST
dimension: performance
prefix: PF
step: "Track 4 Step 5 — selective per-class write keyed on getChangedClasses + F59 root-omission guard"
commit_range: 920fa2aa00298238abf4bfa2eaca520a6b453cb4~1..920fa2aa00298238abf4bfa2eaca520a6b453cb4
verdict: PASS
blocker_count: 0
should_fix_count: 0
suggestion_count: 1
findings_total: 1
high_water_mark: 1
evidence_base: present
cert_index: [C1, C2, C3]
flags: []
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-suggestion-rootpayloaddiffersfrom-allocates-a-string-signature-per-slot-on-the-commit-path"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:218-234"
    cert: C3
    basis: "PSI find-usages: exactly one production caller (AbstractStorage#applyCommitOperations), schema-commit-only path"
-->

## Findings

### PF1 [suggestion] `rootPayloadDiffersFrom` allocates a string signature per slot on the commit path

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 218-234)
- **Issue**: The slot-by-slot global-property-table comparison builds a `name + "|" + type` string for both the tx-local slot and the committed slot on every iteration (`globalPropertySignature`), allocating up to `2 × tableSize` transient strings (plus the `StringBuilder`/char-array each concatenation implies) per schema commit. The same equality can be decided by comparing the two slots' `getName()` and `getTypeInternal()` fields directly, with no allocation, returning early on the first mismatch. The string-signature form was a readability choice (one null-padding branch handles both sides), not a correctness requirement.
- **Evidence**:
  - COST TRACE for `rootPayloadDiffersFrom` at `SchemaShared.java:205-226`: OPERATION = linear scan over `properties` with two string concatenations per slot; COMPLEXITY = O(tableSize) per invocation; DATA SCALE = `properties` is a deduplicated `List<GlobalPropertyImpl>` holding one slot per distinct `(name,type)` across the whole schema (tens to low thousands at the high end); ALLOCATIONS = up to `2 × tableSize` `String` + the concatenation scaffolding per call; I/O = none; LOCK HOLD TIME = computation-only, but it executes inside the held `stateLock.writeLock()` commit window (C1), so the allocations are on the critical section.
  - SCALE CHECK: AT SMALL SCALE (a handful of global properties) = negligible; AT MEDIUM SCALE (hundreds of slots) = a few hundred short-lived strings per schema commit, swamped by the WAL/page work the same commit does; AT PRODUCTION SCALE (thousands of slots) = low-thousands of transient strings per schema commit. VERDICT = MATTERS AT SCALE only weakly — the path is gated to schema-carrying commits (D19), whose frequency is the plan's load-bearing low-rate premise, so even the worst case is a rare, GC-young-gen-only blip, not steady-state pressure.
- **Impact**: Eliminates up to `2 × tableSize` transient `String` allocations per schema commit and shortens the held-write-lock window slightly via early-return on the first differing field. Effect is small and on a low-frequency path; this is a micro-optimization, not a regression fix.
- **Suggestion**: Replace `globalPropertySignature` with a direct two-field comparison in the loop, e.g. compare `a == null && b == null` / both non-null with `a.getName().equals(b.getName()) && a.getTypeInternal() == b.getTypeInternal()` (mirror whatever null-padding contract the slots carry), returning `true` on the first mismatch. Keep the existing size and counter/blob-set short-circuits ahead of it. Optional — the readability of the current form is a fair trade given the call frequency; flagging it only because it is the one net-new allocation the step adds to the commit window.

## Evidence base

The step's central performance question — does the selective write reduce write amplification without adding a new per-commit cost that negates it — resolves cleanly. The write-amplification win is real and the two candidate regressions flagged for scrutiny (the unchanged-class warm-load read and the per-commit bookkeeping) are refuted as regressions. Detail below; CONFIRMED claims compressed to one line per the YTDB-1069 roster rendering, refuted/non-passing claims in full.

#### C1 — Changed code runs once per schema-carrying commit, not on any data hot path
CONFIRMED: PSI find-usages gives exactly one production caller each for `toStream(session, Set, boolean)` and `rootPayloadDiffersFrom` — `AbstractStorage#applyCommitOperations`, inside the `schemaContext != null` branch (D19 write-lock window); the other name matches are javadoc `{@link}` self-references in `SchemaShared` and the white-box test. Frequency is bounded by the schema-change rate, the plan's stated load-bearing low-rate premise.

#### C2 — The unchanged-class `session.load(boundRid)` warm-load is NOT a new per-commit cost (review-focus question, refuted)

The review focus asks whether warm-loading every unchanged class on every schema commit is an O(total classes) read regression versus the prior full-write. It is not, and the refutation is decisive rather than a judgement call.

- CURRENT (this step), unchanged-class branch at `SchemaShared.java:1019-1029`: `session.load(boundRid)` — read only, no `c.toStream(...)`, no record write.
- PRIOR (commit `…cb4~1`), `else` branch of the same loop: `classRecord = session.load(boundRid)` followed unconditionally by `c.toStream(session, classRecord)`. The full-write **already** performed the identical `session.load(boundRid)` for every unchanged class, and *then* serialized and wrote it.
- Therefore the new path's read set is identical to the prior path's read set; the step removes the serialize + record write for unchanged classes and keeps nothing extra on the read side. The warm-load is "the reads were already happening," exactly the negligible case the focus anticipated. The comment at `:1022-1028` (loading inside the active atomic operation so the post-commit promotion re-parse serves from cache) explains a correctness requirement for the read, not a newly-introduced cost.
- VERDICT: NEGLIGIBLE as a regression; the step is a net reduction (same reads, fewer writes). The D6 write-amplification win holds: an unchanged class contributes no WAL units and no page write.

#### C3 — Net-new per-commit allocations are O(changed)/O(table)/O(live classes), all on the schema-commit-only path
CONFIRMED: `getChangedClasses()` returns the live backing set (PSI: javadoc says "the returned set is the live backing set; callers must not mutate it"), so the `changedLower` copy is required, not redundant, and is O(changed classes) — typically 1–few. The per-live-class `getName().toLowerCase(Locale.ENGLISH)` in the selective filter is O(live classes) of short-lived strings. The `rootPayloadDiffersFrom` slot scan is O(table size) — see PF1. All three execute once per schema-carrying commit (C1), a path whose frequency is the low schema-change rate; none is on a data-record or query hot path. No lock is held longer than the computation, and the comparison correctly reads the committed schema before promotion runs (the before-state the diff needs).
