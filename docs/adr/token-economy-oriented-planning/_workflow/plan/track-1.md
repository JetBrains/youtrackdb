<!-- workflow-sha: a91143fb60e3040a0c2a8072e82158ab5665a3f9 -->
# Track 1: Overlap-aware packing as an advisory tie-breaker

## Purpose / Big Picture
After this track lands, the planner and decomposer have an explicit,
advisory, co-locate-first rule that prefers source-file overlap when packing
tracks and steps, with a Phase 2 structural-review criterion backstopping an
undocumented overlap-split.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Refine three workflow prose rules so packing prefers source-file overlap as a
co-locate-first tie-breaker. The planner track-sizing rule gains a
packing-order preference, a least-shared cut-seam rule with adjacent ordering,
and a justification requirement for an unavoidable overlap-split; the
decomposer step-fill rule gains an overlap-aware merge ordering plus a caveat
that step adjacency without a merge removes no implementer; the Phase 2
structural review gains one criterion that flags an undocumented non-adjacent
overlap-split as a `design-decision` finding. This is the whole change, so no
neighboring track exists to fold into.

## Progress
- [ ] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [ ] Track completion
- [x] 2026-06-08T15:10Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-08T15:25Z [ctx=safe] Step 1 complete (commit 30121a90cd)
- [x] 2026-06-08T15:48Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-08T15:53Z [ctx=safe] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (1 finding: 1 suggestion T1, accepted — SYNC line-range hint tightened from 56-90 to 56-68; no blockers, no should-fix)

## Context and Orientation

This is a workflow-modifying, prose-only track. It touches three files under
`.claude/workflow/`, each holding a rule the design refines. No Java, no code
diagram. The existing rules already minimize the number of fresh agent
contexts (the dominant token cost); this track adds source-file overlap as a
second, smaller lever that fits more change per capped agent.

Terminology (defined in design.md §"Core Concepts", used here without
re-definition):
- **Source-file overlap** — two units of work overlap when they edit at least
  one file in common, read from the in-scope file lists in each track file's
  `## Interfaces and Dependencies`. Not thematic relatedness.
- **Co-location** — placing overlapping changes in the same step or track, so
  the shared file is read once. The strong lever.
- **Adjacency** — ordering two overlapping units that cannot share a step or
  track next to each other. The weak fallback: it trims rebase distance and
  keeps the orchestrator's working knowledge fresh, but each sub-agent still
  cold-reads independently.
- **Cut seam** — the boundary between two tracks when a track must be split;
  the least-shared seam keeps overlapping work on one side.
- **Cold-read re-pay** — the existing `track-review.md` term: a file touched by
  K separate sub-agent sessions is read K times. Co-location lowers K.

Current state of the three edit sites:
- **`planning.md` §Track descriptions, "Track sizing rule"** (around lines
  432-472). Carries *Maximize first* (pack autonomous units up to the footprint
  ceiling, related or not; "Prefer a dependency boundary as the cut"), the
  two-sided clamp, and the argumentation gate (an out-of-bounds track without a
  written justification is a `design-decision` finding at Phase 2). This is
  where the track-level packing-order preference, the least-shared cut-seam
  rule, adjacent ordering, and the producer-side overlap-split justification
  requirement land.
- **`track-review.md` §Step Decomposition, "Fill ordinary steps toward ~12
  edited files"** (around lines 775-804). Decomposes `low`/`medium` work toward
  the largest change within ~12 edited files, merging available work related or
  not, with the closed two-reason under-fill `— size:` set. This is where the
  step-level overlap-aware merge ordering and the adjacency caveat land.
- **`prompts/structural-review.md`, TRACK SIZING** (around lines 193-220, with
  the SYNC comment around lines 56-68). Applies the two-sided footprint bound
  and routes an undocumented out-of-bounds track to a `design-decision`
  finding. This is where the one new overlap-split criterion bullet lands.

## Plan of Work

Three independent prose edits, one per file. The only ordering constraint is
S3: the `planning.md` producer requirement and the `structural-review.md`
consumer criterion ship together so the planner is never flagged for a
requirement it was not given.

1. **`planning.md` §Track descriptions — track-level refinements (D1, D2, D3,
   S3 producer half).**
   - In *Maximize first*, add the packing-order preference: among candidate
     units that fit under the ceiling, prefer the one overlapping the track's
     current file set, because it spends less footprint and skips a later
     cross-track re-read. When no candidate overlaps, pack and maximize anyway,
     related or not, exactly as the standing rule says.
   - After "Prefer a dependency boundary as the cut", add the cut-seam
     tie-breaker: among otherwise-equal cuts, prefer the seam sharing the
     fewest files; when overlap cannot be co-located, order the two tracks
     adjacent. The dependency boundary stays primary and wins any disagreement.
   - Add the producer-side requirement: when the planner must split overlapping
     files across non-adjacent tracks, it writes the reason in the track file,
     the same written justification an out-of-bounds footprint already carries,
     and names the matching structural-review check.

2. **`track-review.md` §Step Decomposition — step-level refinements (D2, D4).**
   - In "Fill ordinary steps toward ~12 edited files", add the overlap-aware
     merge ordering: when several `low`/`medium` units are available, prefer
     the one overlapping the step's current file set, since it adds fewer files
     to the ~12 budget and removes a future re-read.
   - Add the adjacency caveat: two distinct steps each spawn their own
     implementer and Phase C reads the whole track diff regardless of step
     order, so step adjacency without a merge removes no implementer
     invocation, the dominant per-step cost. State it plainly so the rule does
     not drift into a false "make steps adjacent" token claim.

3. **`prompts/structural-review.md` TRACK SIZING — one new criterion (D5, S3
   consumer half).**
   - Add one criterion bullet alongside the existing out-of-bounds check: an
     overlap-split across non-adjacent tracks with no written reason is a
     `design-decision` finding, the same class and severity as the undocumented
     out-of-bounds track. No automated cross-track file intersection is
     computed; the reviewer applies judgment to the track files it already
     reads.
   - Check whether the SYNC comment (around lines 56-68) needs a touch: the
     criterion is an addition, not an edit to the paraphrased sizing rule, so
     the byte-identical paraphrase invariant (S2) should hold without changing
     it. Confirm during decomposition.

Invariants to preserve (S1, S2):
- Do not change the file-footprint sizing metric or the ~12 / ~20-25 bounds.
- Leave byte-identical every synchronized copy of the sizing rule named in the
  `prompts/structural-review.md` SYNC comment: `conventions.md` §1.1 glossary
  and §1.2 plan-file Planning rule summary, the create-plan Step 4 sizing rule,
  and the Track terminology paraphrase in all five review prompts (technical,
  risk, adversarial, consistency, and structural-review.md's own bullet). The
  change adds a criterion and refines packing order; it does not edit the rule
  those sites paraphrase. structural-review.md is edited only in its TRACK
  SIZING check region, so its paraphrase bullet stays byte-identical.
- The tie-breaker never moves a step or track out of bounds or past a
  dependency; it is subordinate to coherence, high-isolation, mergeability,
  dependency order, and the bounds.

## Concrete Steps

1. Refine the three overlap-aware packing rules together — `planning.md` §Track descriptions (packing-order preference, least-shared cut-seam, adjacent ordering, and the producer-side overlap-split justification: D1/D2/D3 + S3 producer half), `track-review.md` §Step Decomposition Fill bullet (overlap-aware merge ordering + adjacency-is-not-a-merge caveat: D2/D4), and `prompts/structural-review.md` TRACK SIZING (one new `design-decision` overlap-split criterion: D5 + S3 consumer half), holding S1 (subordination) and S2 (metric/bounds + byte-identical SYNC set) intact — risk: medium (workflow machinery: behavioral-but-bounded multi-file advisory prose — changes the planner's packing order, the decomposer's fill ordering, and one structural-review criterion, but touches no auto-executing hook/script/settings and no load-bearing control-flow gate or shared schema) — size: ~3 files; entire single-track scope, no other low/medium work exists to merge (closed-set reason a: the step already holds the whole track)  [x] commit: 30121a90cd

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 30121a90cd, 2026-06-08T15:25Z [ctx=safe]
**What was done:** Added an advisory, co-locate-first overlap tie-breaker to
three workflow prose rules, each routed to the staged subtree via
copy-then-edit on first touch. `planning.md` §Track descriptions gained three
sub-paragraphs after *Maximize first*: a packing-order preference for an
overlapping candidate at the tie (D1/D2), a least-shared cut-seam rule with
adjacent ordering for a forced non-co-locatable split (D3), and a
producer-side requirement to justify a non-adjacent overlap-split that names
the matching structural-review check (S3 producer half). `track-review.md`
§Step Decomposition gained an overlap-aware merge ordering on the Fill bullet
(with a worked Foo/Bar/Baz example) plus a "step adjacency is not a merge"
caveat (D2/D4). `prompts/structural-review.md` TRACK SIZING gained one
criterion bullet flagging an undocumented non-adjacent overlap-split as a
`design-decision` finding, same class and severity as an out-of-bounds track
(D5, S3 consumer half). Every addition declares subordination to the hard
constraints (S1); the sizing metric, the ~12 / ~20-25 bounds, and every
SYNC-set paraphrase site stayed byte-identical (S2, verified by diff against
the live files).

**Key files:** (all new staged copies under
`_workflow/staged-workflow/.claude/workflow/`)
- `planning.md` (new)
- `track-review.md` (new)
- `prompts/structural-review.md` (new)

## Validation and Acceptance

Track-level behavioral acceptance (the rule edits are correct and complete):
- `planning.md` §Track descriptions states the overlap packing-order
  preference, the least-shared cut-seam tie-breaker, adjacent ordering for a
  forced non-co-locatable split, and the producer-side justification
  requirement, each subordinate to the dependency boundary and the hard
  constraints.
- `track-review.md` §Step Decomposition states the overlap-aware merge ordering
  and the adjacency-is-not-a-merge caveat, without touching the closed
  two-reason under-fill `— size:` set.
- `prompts/structural-review.md` TRACK SIZING carries exactly one new criterion
  bullet for the undocumented non-adjacent overlap-split, classed
  `design-decision`, with no automated intersection computation introduced.
- The metric, the ~12 / ~20-25 bounds, and every synchronized sizing-rule copy
  named in the `prompts/structural-review.md` SYNC comment are unchanged (verify
  by diff): the §1.1 glossary, the §1.2 plan summary, the create-plan Step 4
  rule, and the Track terminology paraphrase in all five review prompts
  (technical, risk, adversarial, consistency, and structural-review.md's own
  bullet).
- The `planning.md` producer requirement and the `structural-review.md`
  consumer criterion both appear in this track's diff (S3).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

In-scope files (all under `.claude/workflow/`):
- `planning.md` — §Track descriptions, "Track sizing rule".
- `track-review.md` — §Step Decomposition, "Fill ordinary steps" bullet.
- `prompts/structural-review.md` — TRACK SIZING checks.

Out-of-scope, must stay byte-identical (S2) — the full synchronized set named
in the `prompts/structural-review.md` SYNC comment (the authoritative
enumeration Phase A pins against before editing):
- `conventions.md` §1.1 glossary row and §1.2 plan-file Planning rule summary.
- The create-plan skill Step 4 sizing rule.
- The Track terminology paraphrase in all five review prompts under
  `.claude/workflow/prompts/`: `technical-review.md`, `risk-review.md`,
  `adversarial-review.md`, `consistency-review.md`, and `structural-review.md`'s
  own Track terminology bullet (distinct from the TRACK SIZING check region this
  track edits).

Inter-track dependencies: none. This is a single-track plan; nothing supplies
a prerequisite and nothing downstream consumes its output.

Staging: this is a workflow-modifying plan (the `### Constraints` marker is
present), so Phase B routes writes through the staged subtree under
`_workflow/staged-workflow/.claude/` with copy-then-edit on first touch
(conventions.md §1.7(e)); live `.claude/workflow/**` stays at develop state
until the Phase 4 promotion.

## Base commit
98c5dd4719febe5f372e2b55b98940a312afc956
