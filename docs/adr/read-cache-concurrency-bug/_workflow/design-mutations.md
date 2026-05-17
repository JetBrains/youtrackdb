# design.md mutation log

## Mutation 1 — 2026-05-11 — content-edit (design.md)

**Diff summary**: rewrote §"Allocation discovery surface" during the
inline-replan that retired the original Track 3. The TL;DR now
describes a dual approach (logical surface where one exists + Track 5
gated helpers otherwise); the per-component table gained a
`PaginatedCollectionStateV2` row and a post-table note stating that
`FreeSpaceMap`, `CollectionDirtyPageBitSet`, and `IndexHistogramManager`
have no EntryPoint at all (PSI-verified); Migration shape was
restructured from a 2-group split (9 pure-sizing vs 7 probes) into a
4-group partition (1 logical migration + 9 probe collapses + 6
stay-on-physical via gated helper + 1 backup); Why addPage is deletable
gained a parenthetical noting the 2 growth-loop probes absorbed from
the retired Track 3; Edge cases / Gotchas gained four new bullets for
the EP-less components, FSM-rebuild recovery, chicken-and-egg
bootstrap, and the IndexHistogramManager defensive presence probe;
References footer marks D2 and D4 as revised after the Track 3 audit.
Triggered by the Phase A audit finding that 3 of 7 in-scope components
have no EntryPoint and 4 sites are physical-by-design (technical /
risk / adversarial reviews unanimous; user-approved ESCALATE →
inline replan; plan + backlog already updated, design.md follows).

**Mechanical checks** (target=design): PASS — 0 findings.
**Cold-read** (scope: bounded — "Allocation discovery surface"
+ "Cache primitive: loadOrAdd" (preceding) + "Concurrency model"
(following) + Overview): PASS — 0 blockers, 0 should-fix, 2
suggestions.

**Findings**:
- suggestion: "≥ 7" → "7" in Migration shape probe-collapse bullet
  header (the named-site enumeration that follows is exact).
  **Applied** as a follow-up edit after the cold-read PASS.
- suggestion: in §"Why `addPage` is deletable", a short clarifier
  distinguishing the "19 external addPage sites" axis from the "2
  growth-loop pure-sizing collapses" axis would prevent a reader
  from double-counting on first read. **Deferred** — comprehension
  survives without the clarifier; the next mutation-pass (or a
  Phase 4 distillation if a `*-final` artifact rewrites the
  section) can pick it up.

**Iterations**: 1 of 3 (PASS).

## Mutation 2 — 2026-05-11 — content-edit (design.md)

**Diff summary**: two focused edits inside §"Allocation discovery
surface", both arising from Phase 2 autonomous consistency review of
the post-replan plan (iteration 1, findings CR1 + CR2). §"Migration
shape" stay-on-physical bullet re-categorized from "4 EP-equipped + 2
EP-less = 6" to "3 EP-equipped + 3 EP-less = 6" —
`IndexHistogramManager.readSnapshotFromPage:1833` moved into the
EP-less group (where the same section's "Logical-size surface per
component" footer already places IHM); total site count unchanged.
§"Why `addPage` is deletable" growth-loop framing rewritten — removed
the "plus 2 sites previously labelled as pure-sizing reads" addendum
that double-counted FSM:227/CDPB:194 as additional addPage call sites
on top of the verified 19; the corrected split is ~9 fresh-file + ~8
reuse-or-extend + 2 growth-loop = 19, with the growth-loops' separate
`getFilledUpTo` reads at FSM:227/CDPB:194 collapsing alongside the
addPage deletion. This also resolves the deferred suggestion from
Mutation 1 (the double-count clarifier).

**Mechanical checks** (target=design): PASS — 0 findings.
**Cold-read** (scope: bounded — "Allocation discovery surface" +
"Cache primitive: loadOrAdd" (preceding) + "Concurrency model"
(following) + Overview): PASS — 0 blockers, 0 should-fix, 2
suggestions.

**Findings**:
- suggestion: split the §"Migration shape" stay-on-physical bullet's
  "EP-equipped: …" and "EP-less: …" into two sub-bullets so the 3+3
  grouping is visually parallel to the header. **Deferred** —
  readability nit, not a rule violation.
- suggestion: §"Why `addPage` is deletable" growth-loop arithmetic
  uses "~9 fresh-file + ~8 reuse-or-extend + 2 growth-loop = 19" —
  the "~" implies uncertainty in the first two terms. **Deferred**
  to Phase A of Track 4, which will pin the exact split.

**Iterations**: 1 of 3 (PASS).

## Mutation 3 — 2026-05-11 — content-edit (design.md)

**Diff summary**: rewrote the Overview's third paragraph to remove a
universality claim contradicted by §"Logical-size surface per
component". Previously said "every storage component already
maintains a logical page count..." — now reads "most storage
components maintain a logical page count... Three components without
an EntryPoint plus a handful of chicken-and-egg / recovery-rebuild
sites route through Track 5's package-private gated helpers instead
(see §'Allocation discovery surface' for the per-site breakdown).
Together these two surfaces eliminate the only path by which a
reader could learn about an in-flight pageIndex." While trimming
back to the 40-line Overview cap, the cascade paragraph also lost
an internal parenthetical about the 20th PSI hit being the
recursive call inside `StorageComponent.loadOrAddPageForWrite`
(that detail still appears verbatim in §"Why `addPage` is
deletable"). Resolves Phase 2 structural review finding S6.

**Mechanical checks** (target=design): PASS — 0 findings (first run
flagged a should-fix on Overview length, 45/40 lines; resolved by
the trim pass before cold-read).
**Cold-read** (scope: bounded — "Overview" + "Class Design" +
"Workflow" + "Allocation discovery surface"): PASS — 0 blockers, 0
should-fix, 1 suggestion.

**Findings**:
- suggestion: open the third paragraph with "The enabling structure
  on the read side is two pre-existing surfaces…" so the dual-surface
  model is signalled before the "Most storage components…" sentence
  rather than only at the closing "Together these two surfaces…"
  sentence. **Deferred** — optional polish; current text is
  technically correct and the §"Allocation discovery surface"
  cross-reference compensates.

**Iterations**: 1 of 3 (PASS, after one pre-cold-read mechanical trim).

## Mutation 4 — 2026-05-11 — content-edit (design.md)

**Diff summary**: rewrote the §"Class Design" paragraph below the
class diagram to soften "every storage component already has" to
"most storage components already carry" and added an explicit
enumeration of the three EP-less components (`FreeSpaceMap`,
`CollectionDirtyPageBitSet`, `IndexHistogramManager`) routed through
Track 5's gated helpers. Resolves Phase 2 structural gate
verification finding S8 — the sibling of S6 (Overview universality)
that the iteration-1 structural review didn't surface but the
iteration-2 gate caught. After this fix Overview, Class Design, and
§"Allocation discovery surface" speak a single coherent two-surface
story.

**Mechanical checks** (target=design): PASS — 0 findings.
**Cold-read** (scope: bounded — "Class Design" + "Overview" anchor +
"Allocation discovery surface" reference): PASS — 0 blockers, 0
should-fix, 0 suggestions.

**Findings**: none.

**Iterations**: 1 of 3 (PASS).




## Mutation 5 — 2026-05-14 — content-edit (design.md)

**Diff summary**: two staleness-reconciliation edits arising from
Phase 2 autonomous consistency review (iteration 1) of the
post-replan plan (inline replan commit `0a003588f7` added D6 / I6 /
Track 7). (a) §"Crash safety" §"Edge cases / Gotchas" — first bullet
rewritten from the stale "orphan disk pages from scenario A leak
space until a vacuum / repack. Bounded today; a separate ticket
tracks adding a post-replay alignment pass." to a full description of
the D6 / Track 7 recovery-time pass: names the four EP-equipped
components in scope, the `AbstractStorage.recoverIfNeeded()` insertion
point between `restoreFromWAL()` and `flushAllData()`, the new
`WriteCache.shrinkFile(fileId, targetBytes)` primitive, the I6
invariant, and the EP-less + IHM out-of-scope set per the Non-Goals.
(b) §"Allocation discovery surface" §"Logical-size surface per
component" — appended one sentence to the IndexHistogramManager
bullet noting the page-1 discriminator
(`op.filledUpTo > 1 ? load : allocate`) also justifies IHM's
exclusion from Track 7's EP-driven pass, with a cross-reference to
Non-Goals in `implementation-plan.md`. Iteration 1 cold-read passed
the comprehension check but flagged two should-fix footer-cite gaps:
§"Crash safety" References missing D6 + I6, §"Allocation discovery
surface" References missing D6. Both fixed in iteration 2; verified.

**Mechanical checks** (target=design): PASS — 0 findings (both runs).
**Cold-read** (scope: whole-doc — Mutation 5 → periodic whole-doc
check fired): PASS — iteration 1 returned 2 should-fix footer-cite
findings; iteration 2 verification PASS, 0 findings.

**Findings**:
- iter 1: should-fix — §"Crash safety" References footer missing D6
  and I6. **Resolved** in iter 2 by appending `D6 (recovery-time
  orphan truncation for EP-equipped components)` and `I6 (post-
  recovery 'logical == physical' for EP-equipped components)`.
- iter 1: should-fix — §"Allocation discovery surface" References
  footer missing D6. **Resolved** in iter 2 by appending `D6
  (recovery-time orphan truncation — anchors the IHM / EP-less
  carve-out in the gotcha bullet)`.

**Iterations**: 2 of 3 (PASS).




## Mutation 6 — 2026-05-17 — content-edit (design.md)

**Diff summary**: two mechanical fixes from State 0 consistency
review (iteration 1) of the post-replan plan — both classified
`mechanical` (current-state claim, single unambiguous correct
rendering, no design-intent change). (a) §"Crash safety" §"Edge cases
/ Gotchas" — rewrote the Post-WAL-replay file truncation bullet to
match the inline-replan placement correction (commit `fc9b448e02`):
previously the bullet said the recovery-time pass "wires into
`AbstractStorage.recoverIfNeeded()` between `restoreFromWAL()` and
`flushAllData()`"; that placement was PSI-audited to iterate empty
`collections` / `indexEngines` / `linkCollectionsBTreeManager` lists,
since the catalogue load happens at `AbstractStorage.java:797-800`,
AFTER `recoverIfNeeded` returns at `:764`. New text names the new
`AbstractStorage.truncateOrphansAfterRecovery()` invoked from `open()`
after the catalogue load (around `:801`) and from
`DiskStorage.postProcessIncrementalRestore` between `:1671` (after
`openIndexes`) and `:1673` (before `flushAllData()`). (b) IHM line-
number drift at two sites — `IndexHistogramManager.readSnapshotFromPage:1833`
→ `:1819` (method declaration line) with `discriminator at :1843`
parenthetical added. Both sites at line ~396 and line ~483.

**Mechanical checks** (target=design): PASS — 0 findings.
**Cold-read** (scope: whole-doc): PASS — 0 blockers, 0 should-fix, 2
suggestions (both formatting polish; not retried per skill rules).

**Findings**:
- suggestion: Crash-safety Edge-cases bullet uses two side-by-side
  parenthetical line-anchors ("`:1671` (after `openIndexes`) and
  `:1673` (before `flushAllData()`)") — reads slightly dense. Could be
  tightened in a follow-up polish pass.
- suggestion: bullet-label at line ~484 has `**bold-close**.` followed
  by parenthetical addendum styled `(discriminator at :1843)` — the
  bold-close-period-bold-period reads slightly awkwardly. Could be
  smoothed for sibling consistency.

**Iterations**: 1 of 3 (PASS).




## Mutation 7 — 2026-05-17 — content-edit (design.md)

**Diff summary**: two batched mechanical fixes from State 0 structural
review iter-1 — both classified `mechanical`, no design-intent change.
(a) **S3e (rename propagation)** — Track 5's IDE Rename renamed the
allocator helper from `loadOrAddPageForWrite` → `allocatePageForWrite`
across all production code; design.md still carried the pre-rename
name at 8 active sites (Overview narrative line 32; Class Design
diagram lines 67, 73; Workflow sequence diagram line 135; Allocation
discovery surface lines 386, 441, 444; Cache primitive recovery
section line 637). All 8 sites renamed. Line 441's call-site narrative
gained a clarifying parenthetical noting the rename history. (b) **S4
(visibility framing)** — `WriteCache.getFilledUpTo` was framed as
package-private at 5 sites total; Track 5 episode records that JLS
§9.4 forbids a literal package-private downgrade on an interface
abstract method, so the audit-grep contract is enforced by `@Deprecated`
+ Javadoc + helper-set naming rather than the access modifier. Five
sites updated: Class Design diagram member (line 49: `~` package-
private sigil → `+` public sigil); Class Design narrative bullet
(lines 93-99) fully rewritten to state `@Deprecated(forRemoval=false)`
+ JLS §9.4 reason + helper-set naming + storage-quiesced caller;
Allocation discovery surface TL;DR (line 336) replaced "non-public"
with the JLS §9.4 + helper-set framing; plus iter-2 fix at Overview
line 25 and Allocation TL;DR line 329 (both said "Track 5's package-
private gated helpers" → "Track 5's named, audit-gated helpers" —
caught by cold-read at iter-1 as self-contradictory with line 336).

**Mechanical checks** (target=design): PASS — 0 findings (both
iterations).
**Cold-read** (scope: whole-doc): iter-1 found 2 blockers (stale
"package-private gated helpers" at Overview line 25 and Allocation
TL;DR line 329, self-contradictory with the freshly-rewritten line
336). Iter-2 PASS — 0 findings.

**Findings**:
- iter-1 blocker (resolved): Overview line 25 carried "Track 5's
  package-private gated helpers" contradicting the same-mutation
  rewrite of line 336.
- iter-1 blocker (resolved): Allocation discovery surface TL;DR line
  329 carried the same stale phrasing.
- Both fixed in iter-2 by replacing "package-private gated helpers" →
  "named, audit-gated helpers" (one-word swap, no other prose change).

**Iterations**: 2 of 3 (PASS).
