<!-- MANIFEST
review_type: risk
role: reviewer-risk
phase: 3A
track: track-1
iteration: 1
verdict: NEEDS-REVISION
findings: 4
blockers: 0
should_fix: 2
suggestions: 2
index:
  - id: R1
    sev: should-fix
    anchor: "### R1 [should-fix]"
    loc: "track-1.md §Plan of Work step 2 / §Interfaces / D9; design-review.md §Prose AI-tell additions"
    cert: "Exposure: de-warm of design-review.md (shared prompt)"
    basis: "grep design-review.md callers; create-plan/SKILL.md step 9 target=tracks; readability-feedback §Rule sync map"
  - id: R2
    sev: should-fix
    anchor: "### R2 [should-fix]"
    loc: "track-1.md D13/D14 / §Plan of Work step 3; design.md §Cost levers for the fan-out"
    cert: "Assumption: fan-out cache warm-up timing and byte-identical-prompt cache sharing"
    basis: "grep warm-up/cache-TTL/byte-identical precedent in .claude/ — none found"
  - id: R3
    sev: suggestion
    anchor: "### R3 [suggestion]"
    loc: "track-1.md §Plan of Work / §Validation and Acceptance; all four steps"
    cert: "Testability: workflow-prose steps under an 85%/70% code-coverage target"
    basis: "step-implementation coverage gate is a code metric; prose steps have no JaCoCo line"
  - id: R4
    sev: suggestion
    anchor: "### R4 [suggestion]"
    loc: "track-1.md §Plan of Work (by-reference) / D19 / §Interfaces inter-track dep"
    cert: "Assumption: author spawns can return a thin summary (by-reference orchestration)"
    basis: "grep 'return only the thin manifest' / 'partial-fetch' across .claude/workflow — abundant precedent"
evidence_base:
  exposure: 2
  assumption: 2
  testability: 1
-->

# Risk Review — Track 1 (iteration 1)

Track 1 carries no Java symbols: every in-scope file is `.claude/**` workflow
prose, the branch is `§1.7(b)` workflow-modifying (`phase-ledger.md` `s17 =
workflow-modifying`), and nothing is staged yet, so every `.claude/**` read
resolved to the live develop-state file per `§1.7(d)`. The five prose criteria
(rule coherence, instruction completeness, prompt-design soundness,
context-budget impact, breakage of dependent prompts/agents) supersede the
Java-oriented WAL/crash/migration criteria. The "critical system path" framing
maps onto: which workflow surfaces the track changes (`edit-design`,
`design-review.md`, `research.md`, `design-document-rules.md`) and what breaks
downstream if the rework is wrong.

Headline: the track's named references all resolve, the staging boundary makes
the worst-case blast radius recoverable (so no blocker), and the by-reference
contract the track calls "a hard requirement" is a well-precedented pattern in
the live workflow. Two should-fix items: the de-warm of `design-review.md`
touches a second live caller that Track 2 owns (a cross-track sequencing risk),
and the fan-out warm-up is the one piece of orchestration with no live
precedent and a soft timing assumption.

## Findings

### R1 [should-fix]
**Certificate**: Exposure — de-warm of `design-review.md` (shared prompt)
**Location**: `track-1.md` §Plan of Work step 2, §Interfaces and Dependencies
(`design-review.md` row), D9; live `design-review.md` § Prose AI-tell additions
(lines 186-224) and §Track-scoped cold-read (lines 226-251).
**Issue**: Step 2 de-warms `design-review.md`, dropping the § Prose AI-tell
additions block and the research-log read. The track scopes this as a Track 1
deliverable and assigns the track-path prose axis to Track 2 (D9: "this DR fixes
the rule, Track 2 applies it at Step 4b"). But `design-review.md` is **not**
spawned only by `edit-design`. The live `create-plan/SKILL.md` step 9 spawns the
identical prompt with `target=tracks`, and the prompt's § Prose AI-tell
additions block names `target=tracks` in its own applies-to set ("This holds for
all three `target=design` kinds **and** for `target=tracks`"), so the
Step-4b track cold-read runs the prose axis through this same block today.

The blast radius if Track 1 simply deletes the § Prose AI-tell block while
landing alone: the `create-plan` Step-4b cold-read on `develop` (and on any
branch that takes Track 1 before Track 2) loses its prose AI-tell axis on track
files with nothing yet replacing it, because the auditor is not wired into
`create-plan` until Track 2. The track is a stacked-diff PR that "stands alone
as an independently reviewable and mergeable unit" (Phase A terminology), so an
independently-merged Track 1 would ship a `design-review.md` that no longer runs
the prose axis on the `target=tracks` surface while the auditor that should own
it does not exist on that surface yet. This is the diluted-pass-removal regret
the branch exists to prevent, applied to the track surface.

Likelihood: moderate (the track explicitly defers the track surface to Track 2,
so the gap is by design; the risk is that it is not flagged as a
must-co-deliver constraint). Impact: a Step-4b cold-read with no prose axis on
either reviewer for the duration between the two PRs. Staging blunts the live
impact (the de-warmed prompt stays under `_workflow/staged-workflow/` until
Phase 4), but the two tracks promote together only if both land before Phase 4.
**Existing safeguard**: S4 ("no surface runs the prose axis on both reviewers")
and the readability-feedback § Rule sync map (line 47) already document the dual
applies-to set, so the dependency is discoverable.
**Proposed fix**: Make Step 2 explicit that de-warming the § Prose AI-tell block
removes the axis from **both** `target=design` and `target=tracks` surfaces, and
that the `target=tracks` surface has no auditor owner until Track 2. Add a
one-line cross-track constraint to §Interfaces (or to S4's verification clause):
Track 1 and Track 2 must promote together, or the de-warm of `design-review.md`
must keep the § Prose AI-tell block's `target=tracks` arm until Track 2 lands the
track-path auditor. The decomposition can also choose to leave the
`target=tracks` arm in place in Track 1 and have Track 2 remove it together with
wiring the auditor — naming this as a decomposition decision closes the gap.

### R2 [should-fix]
**Certificate**: Assumption — fan-out cache warm-up timing and byte-identical
prompt cache sharing
**Location**: `track-1.md` D13/D14, §Plan of Work step 3 ("Implement the cost
levers (D13): the fan-out cache warm-up"); design.md § Cost levers for the
fan-out (lines 664-705).
**Issue**: D13/D14 rest on three load-bearing runtime assumptions that the track
itself flags as "the most involved orchestration in the branch" and defers to
implementation (gate A7): (1) a fixed ~one-minute delay is enough for the first
auditor's cold prompt prefix to "land and propagate" into a shared cache before
the rest of the fan-out spawns; (2) sub-agent spawns share a prompt cache keyed
on a byte-identical prompt; (3) the five-minute cache TTL does not age the prefix
out during the warm-up. **Evidence search** (grep over `.claude/` for `warm-up`,
`cache TTL`, `byte-identical`, `fan-out` delay, `cold prefix`): there is **no
live workflow precedent** for a delay-sequenced fan-out. Every existing fan-out
in the workflow (the `/code-review` dimensional reviewers, the
`readability-feedback` audit) races its sub-agents in parallel with no warm-up
step; none waits a fixed delay for a cache write to propagate. So the warm-up is
genuinely new machinery, and its timing constants (the one-minute delay, the
five-minute TTL window) are unverified against the harness's actual cache
behavior. The track notes the failure mode honestly: "a heavy code-grounded
author whose first turn runs long can push its cold write past the one-minute
delay" (D13 Risks/Caveats).

Likelihood: the warm-up *correctness* is low-risk because the failure mode is
graceful. If the prefix is not warm when the fan-out spawns, the cost lever
underperforms (more cold prefixes paid) but the loop still produces correct
output; cache sharing is a cost optimization, not a correctness invariant.
Impact: cost, not output. So this is a should-fix on the cost-realism axis, not
a blocker on correctness.
**Existing safeguard**: the iteration budget (S5) and the user-escalation
backstop bound the worst case regardless of cache behavior; the warm-up failing
does not break the dual-clean exit.
**Proposed fix**: Add a Phase-A decomposition note (or a step in the
decomposition) that the warm-up delay is a **tunable** with a measured fallback,
not a fixed constant, and that the acceptance check for the warm-up is "the loop
produces correct dual-clean output with the warm-up disabled", so correctness
must not depend on the cache lever landing. State the graceful-degradation
contract explicitly so an implementer does not build the loop to *require* a
warm cache. The byte-identical-prompt requirement (D14) should be verified
against the actual `Agent`-tool prompt assembly during implementation, since a
varying injected tail (CLAUDE.md, memory) can bust the shared body silently.

### R3 [suggestion]
**Certificate**: Testability — workflow-prose steps under an 85%/70%
code-coverage target
**Location**: `track-1.md` §Plan of Work (all four steps), §Validation and
Acceptance; the project coverage gate (85% line / 70% branch).
**Issue**: The risk-review coverage criterion ("can each step realistically
achieve 85% line / 70% branch coverage") is a JaCoCo code metric. Every Track 1
step edits Markdown prose — four agent definitions, the `design-review.md`
de-warm, the `edit-design` rework, the `research.md`/`design-document-rules.md`
S2 wording — none of which produces a Java line JaCoCo can cover. So the literal
coverage target is **not applicable** to this track, and a decomposition that
silently inherits it would have no way to satisfy it. The track's
§Validation and Acceptance correctly states prose-shaped acceptance instead
(each agent definition carries the intended `tools:` allow-list; the de-warmed
reviewer reads no log and runs no prose axis; the dual-clean loop does not exit
until both checks are clean). The risk is only that the acceptance for some
steps is hard to verify in isolation: the S5 dual-clean exit, the S3
freeze-order gate behavior, and the warm-up are loop-runtime properties, not
static file properties, so a step's "test" is a worked dry-run of the loop, not
a unit assertion. The closest live precedent for verifying prose machinery is
the `workflow-reindex.py --check` TOC validator (readability-feedback
§Validation) plus a static read of the staged files against the S-invariants.
**Existing safeguard**: the Phase-A decomposition writes per-step EARS/Gherkin
acceptance lines (the §Validation and Acceptance placeholder), which is the
right home for prose-shaped acceptance.
**Proposed fix**: At decomposition, state per step that the acceptance check is
prose-shaped (a static read against the named S-invariant plus
`workflow-reindex.py --check` for TOC integrity), not a JaCoCo line/branch
percentage, and flag the three loop-runtime properties (S5 dual-clean exit, S3
gate ordering, warm-up) as requiring a worked dry-run rather than a static
check. This is a note, not a defect — the track already chose prose acceptance.

### R4 [suggestion]
**Certificate**: Assumption — author spawns can return a thin summary
(by-reference orchestration)
**Location**: `track-1.md` §Plan of Work ("By-reference orchestration is a hard
requirement: every author spawn must return only a thin summary, never the
drafted content"), §Interfaces inter-track dependency, D19; design.md
§Collapsing the 4a/4b session boundary (lines 559-563).
**Issue**: The track makes by-reference orchestration a hard requirement and
states Track 2's D15 (4a/4b boundary collapse) "regresses to retaining the
boundary if by-reference cannot hold." This raises the question of whether the
`Agent` tool actually lets a sub-agent return only a thin summary while writing
its real output to disk. **Evidence search** (grep over `.claude/workflow` and
`.claude/skills` for `return only the thin manifest`, `partial-fetch`,
`output_path`): the pattern is **abundantly precedented** in the live workflow.
`consistency-review.md`, `technical-review.md`, `adversarial-review.md`,
`review-gate-verification.md`, `implementation-review.md`, and the cold-read in
`design-review.md` itself (its `output_path` branch, lines 378-392) all use the
"write the detail to a spawn-supplied path, return only a summary, orchestrator
partial-fetches `## Findings` from disk" shape. So the by-reference contract is a
known, working pattern, not a novel risk; the author spawn just needs the same
write-to-path-return-summary discipline the review spawns already use. That
lowers the residual risk on Track 2's D15 from "unproven assumption" to "apply an
established pattern."
**Verdict**: VALIDATED. The by-reference contract is realizable with the existing
output-path/partial-fetch idiom.
**Existing safeguard**: the live workflow's review-file schema
(`conventions-execution.md §2.5`) already standardizes the summary-plus-detail
return shape the author spawn would reuse.
**Proposed fix**: Optional — have the author agent definition reference the
existing `output_path` + partial-fetch idiom (the one `design-review.md` already
uses for `phase4-creation`) as the realization of the by-reference contract, so
the implementer reuses the proven pattern rather than inventing a return
discipline. No change required for correctness; this is a de-risking pointer.

## Evidence base

#### Exposure: de-warm of `design-review.md` (shared prompt)
- **Track claim**: Step 2 de-warms `design-review.md` (drops the § Prose AI-tell
  additions block and the research-log read), keeping comprehension + structure;
  D9 assigns the `target=tracks` prose axis to Track 2.
- **Critical path trace** (workflow-prose surface, not code):
  1. `edit-design/SKILL.md` § Step 4 (line 450) spawns
     `prompts/design-review.md` for the `phase1-creation` cold-read.
  2. `create-plan/SKILL.md` step 9 (lines ~710-735) spawns the **same**
     `prompts/design-review.md` with `- target: tracks` for the Step-4b track
     cold-read.
  3. `design-review.md` § Prose AI-tell additions (lines 186-224) declares its
     applies-to set as the three `target=design` kinds **and** `target=tracks`
     (line 189, restated line 247 and in the §Output format render note line
     431).
  4. `readability-feedback/SKILL.md` § Rule sync map (line 47) confirms the
     § Prose AI-tell block "scans both `target=design` and `target=tracks`".
- **Blast radius**: removing the § Prose AI-tell block in Track 1 strips the
  prose axis from both surfaces; the `target=tracks` (Step-4b) surface has no
  auditor owner until Track 2, so an independently-merged or independently-staged
  Track 1 leaves the track cold-read with no prose axis on either reviewer for
  the window between the two PRs.
- **Existing safeguards**: S4 invariant + readability-feedback § Rule sync map
  both document the dual applies-to set, so the dependency is discoverable;
  staging keeps the de-warm non-live until the Phase 4 promotion, which promotes
  both tracks together if both land first.
- **Residual risk**: MEDIUM — by-design deferral that is not flagged as a
  must-co-deliver cross-track constraint; a stacked-diff merge order or a
  Phase-4 promotion taken with only Track 1 complete reintroduces the
  diluted-pass gap on the track surface.

#### Exposure: S2/S3 read-scope wording edit (research.md canonical home)
- **Track claim**: Step 4 / D18 extend the canonical S2 statement in
  `research.md` §"Read-scope discipline (S2)" (and its
  `design-document-rules.md` restatement) to name the warm absorption agent as a
  sanctioned log reader; `conventions.md` cross-refs likely untouched.
- **Critical path trace**:
  1. Canonical S2 home: `research.md` line 116, "the log is read for decision
     content in exactly two places: at Step 4a/4b artifact authoring … and by
     the Phase-2 consistency review" — resolves, exact heading present.
  2. Restatement: `design-document-rules.md` line 103 names S2 ("the log is read
     for decision content only at …") — resolves.
  3. `conventions.md` cross-refs: lines 86 and 174 say "the two sanctioned read
     points" and carry **no** `S2` label — matches the track's CR3 claim exactly,
     so the deliverable correctly targets `research.md` +
     `design-document-rules.md` and leaves the conventions.md cross-refs alone.
  4. S3 freeze-order: `research.md` (the `## Adversarial gate record` cadence,
     lines ~108-115) and `edit-design/SKILL.md` § Step 4 (lines 414-442) carry
     the gate; the de-warmed reviewer reads no log so it no longer needs the gate
     for its own sake (D5), but the gate stays on the loop for the author and
     absorption check (S3) — consistent.
- **Blast radius**: if the S2 wording is **not** updated, the Phase-2 consistency
  review (or a literal reader) sees the warm absorption agent as a third
  log-reading site and flags an S2 violation. The track correctly binds the
  wording edit as a deliverable rather than an implicit reinterpretation (D18).
- **Existing safeguards**: the two-sanctioned-sites wording is centralized in
  `research.md` with one restatement, and the absorption check is the only new
  log reader (the auditor and de-warmed comprehension reviewer read no log), so
  the site count stays at two.
- **Residual risk**: LOW — all anchors resolve, the deliverable is explicit, and
  the conventions.md "two sanctioned read points" cross-refs are descriptive
  (site count, not reader identity), so they stay accurate after the edit.

#### Assumption: fan-out cache warm-up timing and byte-identical-prompt sharing
- **Track claim**: D13/D14 — a ~one-minute fixed warm-up delay lets the first
  auditor's cold prefix land in a shared cache before the rest fan out; spawns
  share a cache keyed on a byte-identical prompt; the five-minute TTL does not
  age the prefix out.
- **Evidence search**: Grep over `.claude/` for `warm-up`, `warm up`,
  `cache TTL`, `byte-identical`, `fan-out`/`fan out` delay, `cold prefix`,
  `five-minute`.
- **Code evidence**: no live workflow file sequences a fan-out with a delay; the
  existing fan-outs (the 17 `review-*` dimensional agents, `readability-feedback`
  step 3) all spawn in parallel with no warm-up. The track itself flags the
  warm-up as "the most involved orchestration in the branch … deferred to
  implementation (gate A7)" and names the long-first-turn failure mode.
- **Verdict**: UNVALIDATED (cost lever, not correctness).
- **Detail**: the timing constants are unverified against the harness; the
  failure mode is graceful (cost regression, not wrong output), so the residual
  is a cost-realism should-fix, bounded by the iteration budget + escalation
  backstop. The byte-identical requirement (D14) needs verification against the
  actual `Agent`-tool prompt assembly during implementation.

#### Assumption: author spawns can return a thin summary (by-reference)
- **Track claim**: "every author spawn must return only a thin summary, never
  the drafted content"; Track 2's D15 regresses without it.
- **Evidence search**: Grep over `.claude/workflow` and `.claude/skills` for
  `return only the thin manifest`, `partial-fetch`, `output_path`.
- **Code evidence**: the pattern is in `consistency-review.md` (507-508),
  `technical-review.md` (313-314), `adversarial-review.md` (425-426),
  `review-gate-verification.md` (110), `implementation-review.md` (348, 384,
  404), and `design-review.md`'s own `output_path` branch (378-392) — all
  write detail to a spawn-supplied path and return a summary the orchestrator
  partial-fetches.
- **Verdict**: VALIDATED.
- **Detail**: the by-reference contract reuses an established, working idiom; the
  author spawn applies the same write-to-path / return-summary discipline. This
  de-risks Track 2's D15 dependency from "unproven" to "apply a known pattern."

#### Testability: workflow-prose steps under an 85%/70% code-coverage target
- **Coverage target**: 85% line / 70% branch (project default).
- **Difficulty assessment**: every step edits Markdown (agent definitions, the
  `design-review.md` de-warm, the `edit-design` rework, the
  `research.md`/`design-document-rules.md` wording). None yields a JaCoCo-covered
  Java line, so the numeric coverage target is not applicable. The runtime
  properties (S5 dual-clean exit, S3 gate ordering, the warm-up) are
  loop-behavior, verifiable only by a worked dry-run, not a static file read.
- **Existing test infrastructure**: `workflow-reindex.py --check` (TOC integrity,
  cited in readability-feedback § Validation) and a static read of the staged
  files against each named S-invariant; the §Validation and Acceptance
  placeholder is the home for per-step EARS/Gherkin acceptance lines.
- **Feasibility**: ACHIEVABLE.
- **Detail**: acceptance is prose-shaped (static invariant read +
  `workflow-reindex.py --check`), with the three loop-runtime properties needing
  a worked dry-run. The track already chose prose acceptance in §Validation and
  Acceptance; this certificate flags that the numeric coverage target should be
  explicitly marked N/A at decomposition so it is not silently inherited.
