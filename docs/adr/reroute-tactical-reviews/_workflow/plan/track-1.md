<!-- workflow-sha: eb984cba63bd557fb3c2b32156d85bf1a72e82b4 -->
# Track 1: Generalize §1.7 staging to a third prefix (`.claude/agents/`)

## Purpose / Big Picture
After this track, an edit to a `.claude/agents/**` file on a workflow-modifying
branch routes to the staged mirror, stays at develop-state on the live tree
until Phase 4, and promotes with the rest of the staged workflow machinery.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Precursor. Extends the §1.7 staging convention so agent-definition edits stage
like every other workflow file. Highest-care edit: the workflow-modifying
marker matcher, made prefix-agnostic so the plan's two-prefix Constraints
marker matches both the live gate during this track and the staged gate after
it (D7). Lands first because every later track that edits `.claude/agents/`
depends on this rule self-applying via §1.7(d) reads-precedence — Track 3's
agent edits cannot stage until this rule is in the staged mirror.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-07T12:20Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

**DL1 (Phase A, Technical review, 2026-06-07) — scope completed within D7, no
plan change.** The Technical review accepted six findings (0 blocker, 3
should-fix, 3 suggestion) that finish the §1.7 generalization's enumeration
without altering any plan Decision Record. They grow the in-scope footprint from
~16 to ~20 files; recorded here so the growth is auditable:

- **§1.6(b) stamp base (T3) is a D7 entailment, not a new decision.** D7's
  rationale already adopts "an agent-only develop commit registers as a
  workflow-format change for drift." For that to stay self-consistent, the stamp
  base (§1.6(b), copied verbatim in `conventions.md`, `create-plan/SKILL.md`,
  `edit-design/SKILL.md`) must move with the drift `WORKFLOW_PATHSPECS`.
  Otherwise an artifact created after an agent-only develop commit stamps to a
  pre-agent SHA and its drift range starts before that commit. Chose option (a),
  extend §1.6(b) to three prefixes, over option (b), soften the Validation claim.
- **Staged-read precedence caveats (T1).** Seven review/gate prompts enumerate
  the two-prefix pair in their §1.7(d) caveat; extended to three prefixes so a
  staged agent read (first arising at Track 3) resolves to the staged copy. This
  is staging plumbing, not the reviewer-side output work Tracks 2-4 own. The
  caveats land in Track 1 rather than Track 3, where they are first functionally
  needed, to keep the §1.7(d) read-precedence surface internally consistent
  across its definition (`conventions.md`) and all seven consumers within the one
  precursor track (A2). Deferring them would open a multi-track window where the
  definition names three prefixes and its consumers name two, which the
  consistency reviewer would flag mid-stack.
- **Reindex scope split (T2).** Step 4's load-bearing work is routing a staged
  agent into the rules-6/7-only applicability gate, not the comment edits: the
  dead glob auto-activates into `validate`'s eight-rule loop and would over-fire
  rules 1/2/3/4/5/8 on a staged agent. A validation-routing test covers it,
  distinct from the existing discovery-only test.
- **migrate-workflow classification (T4)** extended alongside its pathspecs so an
  agent-only commit classifies as a workflow-format change.
- **Wording fixes (T5, T6):** the marker match is prose-only, so step 6 carries
  no Python marker-matcher test; the Phase 4 `cp -r` is already prefix-agnostic,
  so step 5 extends the `git add` list and the divergence check instead.

The §1.8 TOC-protocol bootstrap line and `code-review/SKILL.md`'s triage table
were checked and ruled out of scope (read-protocol and already-lists-agents,
respectively). The adversarial review (A5) also noted that
`consistency-gate-verification.md` and `structural-gate-verification.md` lack the
§1.7(d) read-precedence caveat their review-prompt siblings carry; this is a
pre-existing develop-state asymmetry (those Phase-2 gate verifiers read
`_workflow/` plan artifacts, not staged `.claude/**` files), not a three-prefix
gap this track introduces, so it stays out of scope. Footprint ~20 sits at the
lower edge of the ~20-25 split band; kept one track because the work is a uniform
mechanical generalization plus two high-care cores, and ~10 of the files are
one-line edits.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (6 findings, 6 accepted). Findings completed
  the §1.7 generalization's enumeration within D7 (no plan change): §1.6(b) stamp
  base (T3, D7 entailment), 7 review/gate read-precedence caveats (T1), reindex
  staged-agent scope split (T2), migrate-workflow classification (T4), plus two
  wording fixes (T5 prose-only marker match, T6 cp-r already prefix-agnostic).
  Footprint ~16 → ~20 files; see DL1.
- [x] Risk: PASS at iteration 1 (1 finding, 1 accepted). Zero blockers. R1
  (suggestion) made the step-2/step-4 co-requisite and its cross-track guarantee
  (Track 3 waits for the reindex fix) explicit in the Ordering constraint. Risk
  validated that extending §1.6(b) is benign on this branch (the latest
  agent-touching develop commit predates the stamp base, so future stamps are
  unchanged) and that the script CI gate is `--check` correctness, not line
  coverage, so the prose-only marker matcher is not a coverage gap.
- [x] Adversarial: PASS at iteration 1 (5 findings, 0 blockers; A2/A4/A5 applied
  as clarifications, A1/A3 validated the design with no change). All four
  challenges survive: A1 confirmed the §1.6(b) extension is a genuine D7
  entailment (not over-reach), A3 confirmed prefix-agnostic matching is correct
  (editing the plan marker would deactivate the live develop-state gate for the
  authoring plan), I6 holds for the third prefix. A2 added the Track-1-over-Track-3
  rationale; A4 flagged the pre-commit gate refused-path set as a distinct I6
  enforcement site; A5 recorded the gate-verification caveat asymmetry as a
  checked out-of-scope pre-existing item.

## Context and Orientation

§1.7 today governs two stageable prefixes only: `.claude/workflow/**` and
`.claude/skills/**`. The convention's single source of truth is
`conventions.md §1.7`, with the prefix pair hardcoded across many consumers.
The job of this track is to add `.claude/agents/` as a third prefix everywhere
the pair appears, and to make the marker matcher prefix-agnostic so the
bootstrap holds (see Plan of Work).

Concrete state of the consumers at track start:

- **`conventions.md`** — §1.6(b) `WORKFLOW_SHA` stamp-base computation
  (`git log -1 ... -- .claude/workflow .claude/skills`, line 579) names the
  pair; §1.6(h) deliberately omits `staged-workflow/` from the stamp walk and
  explains the pair; §1.7(a) staged-subtree path layout names the two prefixes;
  §1.7(b) defines the canonical marker literal
  (`This plan is workflow-modifying: it edits .claude/workflow/** or .claude/skills/**.`);
  §1.7(d) reads-precedence and §1.7(e) write-routing both enumerate the pair.
  Each clause must extend to three prefixes.
- **`create-plan/SKILL.md` and `edit-design/SKILL.md`** — copy the §1.6(b)
  `WORKFLOW_SHA` stamp-base line verbatim (create-plan line 370; edit-design
  lines 220 and 286). The stamp base must move in lockstep with the drift
  pathspec so D7's "agent-only commit registers as a workflow-format change"
  property stays self-consistent (see Plan of Work step 3).
- **`implementer-rules.md`** — the path-mapping write-routing rule, the marker
  matcher that activates the gate, and the pre-commit gate that refuses live
  `.claude/workflow/**`/`.claude/skills/**` writes outside the Phase 4 promotion.
  The marker literal is matched in only two files (here and §1.7(b)); the
  review/gate prompts below detect the marker descriptively, not by literal
  prefix-list match, so they need no matcher edit.
- **Seven review/gate prompts** — `technical-review.md`, `risk-review.md`,
  `adversarial-review.md`, `consistency-review.md`, `structural-review.md`,
  `review-gate-verification.md`, `dimensional-review-gate-check.md` each carry a
  **staged-read precedence caveat** enumerating "`.claude/workflow/**` or
  `.claude/skills/**`" (one line each). A reviewer resolves a staged file read
  through §1.7(d); once agents are stageable, the caveat must name the third
  prefix or a staged agent read falls through to the live develop-state copy
  (the phantom-mismatch §1.7(d) prevents). `track-code-review.md` /
  `step-implementation.md` carry a prefix-agnostic delta glob
  (`staged-workflow/.claude/*`), not this caveat, so they need no edit.
- **`workflow-startup-precheck.sh`** — `WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/"`
  (line 273) drives the drift walk; the comment at line 268 describes the
  two-prefix staged layout.
- **`workflow-reindex.py`** — already carries an **inert/dead** staged-agents
  glob (`docs/adr/*/_workflow/staged-workflow/.claude/agents/**/*.md`, line 155)
  documented as never-matching because agents are modified live today (lines
  144-155). It routes live agent files through the rules-6/7-only
  `discover_agent_citing_files` scope, never `IN_SCOPE_GLOBS`. `validate` runs
  all eight rules on every `parsed_files` member (~2274-2284) but only rules 6/7
  on `parsed_agent_files` (~2289-2293). Once agents are stageable, the dead glob
  goes live, so `discover_in_scope_files` returns a staged agent into
  `parsed_files` and the eight-rule loop over-fires rules 1/2/3/4/5/8 on it (no
  stamp, no TOC, no annotations, no in-file refs). A staged agent must be routed
  into the rules-6/7-only gate instead, validating like a live agent.
- **`create-final-design.md`** — the Phase 4 promotion `git add` path list (line
  368) and the §1.7(f) pre-promotion divergence-check `git log` pathspec (line
  361) name the pair. The `cp -r "$STAGED_DIR/.claude/." .claude/` (line 367)
  copies the whole subtree and is already prefix-agnostic.
- **`workflow-drift-check.md`** — the pathspec defensive comment naming the pair.
- **`workflow.md` §Final Artifacts** and **`step-implementation.md`** — staging
  references that name the pair.
- **`migrate-workflow/SKILL.md`** — the migration pathspecs (the Step 2
  `git show` walk and the rename-detect pathspec) and the `format`/`skill`/`rename`
  commit-classification rules, which decide whether a develop commit is a
  workflow-format change.
- **Script tests** — `test_workflow_startup_precheck.py`, `test_workflow_reindex.py`
  pin the two-prefix behavior and the staged-discovery assertions.

```mermaid
flowchart LR
    M["Constraints marker<br/>(2-prefix literal)"] --> G["implementer marker matcher<br/>prefix-agnostic after this track"]
    G --> WR["write-routing §1.7(e)<br/>workflow / skills / agents"]
    WR --> SM["staged mirror<br/>_workflow/staged-workflow/.claude/agents/"]
    SM --> P4["Phase 4 promotion cp -r<br/>create-final-design.md"]
    PS["precheck WORKFLOW_PATHSPECS"] --> DR["drift walk + reindex globs"]
    SM -.->|staged-read precedence| RP["later tracks' implementer<br/>self-applies the staged rule"]
```

- **marker matcher** — boolean trigger; made prefix-agnostic so the bootstrap
  holds.
- **write-routing** — the prefix set that determines which writes stage; extended
  to three.
- **staged mirror** — gains a `.claude/agents/` subtree once routing accepts it.
- **drift walk / reindex globs** — recognize the third prefix so an agent-only
  develop commit registers as a workflow-format change and staged agents validate.

## Plan of Work

The approach is one mechanical generalization (two prefixes to three) applied
uniformly across the consumers above, plus two non-mechanical pieces that the
rest of the track and every later track depend on: the marker matcher and the
reindex staged-agent scope split.

1. **Marker matcher (highest-care, do first within the track).** Change the
   marker definition in `conventions.md §1.7(b)` to name the third prefix, and
   change the `implementer-rules.md` gate matcher to match on the stable prefix
   `This plan is workflow-modifying:` regardless of the trailing prefix list.
   This lets the plan keep the develop-state two-prefix Constraints marker
   verbatim: the live gate matches it during this track, and the staged
   prefix-agnostic gate matches it afterward (D7). Do not require the plan's
   Constraints marker to change; a prefix-agnostic matcher is the bootstrap. The
   marker literal lives in only two files (`conventions.md §1.7(b)` definition
   and the `implementer-rules.md` matcher). The review/gate prompts detect it
   descriptively, not by literal prefix-list match, so they need no matcher edit
   here — their separate read-precedence edit is in step 2.
2. **Write-routing and reads-precedence.** Extend §1.7(a) path layout, §1.7(d)
   reads-precedence, and §1.7(e) write-routing + copy-then-edit to include
   `.claude/agents/`. Extend the two distinct `implementer-rules.md` sites in
   lockstep: the path-mapping write-routing rule and the pre-commit gate's
   refused-path set. The refused-path set is the sole mechanical I6 enforcement
   for the third prefix on future branches and is a separate, easy-to-miss edit
   from the write-routing prose. Extend the staged-read
   precedence caveat in the seven review/gate prompts (`technical-review.md`,
   `risk-review.md`, `adversarial-review.md`, `consistency-review.md`,
   `structural-review.md`, `review-gate-verification.md`,
   `dimensional-review-gate-check.md`): each enumerates "`.claude/workflow/**` or
   `.claude/skills/**`" and must name the third prefix so a reviewer resolves a
   staged agent read through §1.7(d) rather than reading the live develop-state
   copy. This surfaces once a later track (Track 3) stages an agent; landing it
   here keeps the §1.7(d) generalization complete.
3. **Stamp walk, stamp base, and drift.** Extend §1.6(h)'s staged-prefix
   omission note and `workflow-startup-precheck.sh`'s `WORKFLOW_PATHSPECS` to the
   third prefix; update the `workflow-drift-check.md` pathspec comment. Extend
   §1.6(b)'s `WORKFLOW_SHA` stamp-base computation
   (`git log -1 ... -- .claude/workflow .claude/skills`) to the third prefix in
   `conventions.md` (line 579) and at the two verbatim copiers
   `create-plan/SKILL.md` (line 370) and `edit-design/SKILL.md` (lines 220, 286).
   D7's accepted property — an agent-only develop commit registers as a
   workflow-format change — requires the stamp base and the drift
   `WORKFLOW_PATHSPECS` to move in lockstep. A lagging base would stamp an
   artifact created after an agent-only develop commit to a pre-agent SHA, so the
   drift range `BASE_SHA..HEAD` would spuriously start before that commit (see
   Decision Log).
4. **Reindex (staged-agent scope split — non-mechanical).** The staged-agents
   glob already sits in `IN_SCOPE_GLOBS` (line 155), and `discover_in_scope_files`
   returns a staged agent the moment §1.7(e) routing stages one, so no
   glob-string edit is needed. That auto-activation is the hazard, not the finish
   line: `validate` runs all eight rules on every `parsed_files` member
   (~2274-2284), so a staged agent (no stamp, no TOC, no per-section annotations,
   no in-file refs) over-fires rules 1/2/3/4/5/8. Live agents avoid this via the
   separate `parsed_agent_files` scope that runs rules 6/7 only (~2289-2293). The
   reconciliation routes a staged agent into that same rules-6/7-only
   applicability gate, so it validates like a live agent. Then re-document the
   now-false inert-rationale comment (lines 144-154) and the
   `discover_agent_citing_files` docstring (lines 1095-1104), which assert agents
   are modified live with no staged copy.
5. **Promotion.** In `create-final-design.md`, extend the Phase 4 `git add` path
   list (line 368) and the pre-promotion divergence-check `git log` pathspec
   (line 361) to the third prefix; the `cp -r "$STAGED_DIR/.claude/." .claude/`
   (line 367) copies the whole subtree and is already prefix-agnostic, so it
   needs no change. Extend the `workflow.md §Final Artifacts` staging reference
   and `step-implementation.md`'s two staging enumerations (its write-routing
   scope and its reads-precedence scope, not a single reference), the
   `migrate-workflow/SKILL.md`
   migration pathspecs (Step 2 `git show` walk, rename-detect), and that skill's
   `format`/`skill`/`rename` commit-classification rules so an agent-only commit
   classifies as a workflow-format change consistent with step 3's drift
   recognition.
6. **Tests.** Update `test_workflow_startup_precheck.py` and
   `test_workflow_reindex.py` to cover the third prefix in `WORKFLOW_PATHSPECS`
   and a staged agent's validation routing: assert a staged agent emits only
   rule-6/7 findings (no rule-1/2/3/4/5/8 over-fire), distinct from the existing
   discovery-only test. The prefix-agnostic marker match lives in
   `implementer-rules.md` prose (an LLM instruction), not in an executable
   matcher, so there is no Python unit to test; the marker bootstrap is covered
   by the prose review of `implementer-rules.md` + §1.7(b), not a script-level
   marker-matcher test.

Ordering constraint: step 1 (marker matcher) governs whether this track's own
edits stage and whether later tracks self-apply the rule; complete it before the
mechanical prefix extensions so reviews see the bootstrap intact. Step 4's scope
split is the second high-care edit and is a hard co-requisite of step 2: step 2
flips §1.7(e) routing so an agent write can stage, and step 4 stops a staged
agent from over-firing rules 1/2/3/4/5/8 in `validate`. Both must land within
Track 1. Track 1 stages no agent itself (it edits no `.claude/agents/` file), so
no over-fire is reachable between the two steps within this track; the guarantee
that matters is cross-track. Track 3 is the first track to stage an agent and
must not begin until Track 1 has landed the reindex fix, so decomposition keeps
the reindex scope split and its step-6 validation-routing test inside Track 1
rather than deferring either. Invariant to preserve: I6 (live workflow stays at
develop-state until Phase 4) must hold for the third prefix exactly as for the
first two.

## Concrete Steps
<!-- D9: thin numbered roster; per-step episodes live in ## Episodes. Step 1 is
the marker-matcher bootstrap (do first); steps 1-4 are the two high-care cores
plus the two convention/scheme generalizations, each its own high-isolation step;
step 5 collects the bounded-behavioral consumers. conventions.md and
implementer-rules.md are edited across sequential steps (allowed for sequential
commits; no parallel steps in this track). -->

1. Make the workflow-modifying marker matcher prefix-agnostic — change the `conventions.md §1.7(b)` marker definition to name the third prefix and change the `implementer-rules.md` gate matcher to match the stable prefix `This plan is workflow-modifying:` regardless of the trailing prefix list (the D7 bootstrap; keep the plan's two-prefix `### Constraints` marker verbatim). No executable test: prose-only marker match, validated by prose review + `workflow-reindex.py --check`. — risk: high (workflow machinery: §1.7 staging-convention gate matcher — the load-bearing bootstrap every later track self-applies)  [ ]
2. Extend §1.7 write-routing and reads-precedence to `.claude/agents/` — `conventions.md §1.7(a)(d)(e)`; the two distinct `implementer-rules.md` sites (path-mapping write-routing rule and pre-commit gate refused-path set); and the seven review/gate prompts' §1.7(d) staged-read precedence caveats (`technical-review.md`, `risk-review.md`, `adversarial-review.md`, `consistency-review.md`, `structural-review.md`, `review-gate-verification.md`, `dimensional-review-gate-check.md`). Validated by `workflow-reindex.py --check` + prose review. — risk: high (workflow machinery: §1.7 staging convention)  [ ]
3. Extend the §1.6 stamp scheme and drift walk to `.claude/agents/` — §1.6(b) `WORKFLOW_SHA` stamp base in `conventions.md`, `create-plan/SKILL.md`, and `edit-design/SKILL.md` (lockstep with the drift pathspec per DL1); the §1.6(h) stamp-walk omission note; `workflow-startup-precheck.sh` `WORKFLOW_PATHSPECS` and the `workflow-drift-check.md` pathspec comment; add third-prefix coverage to `test_workflow_startup_precheck.py`. — risk: high (workflow machinery: §1.6 stamp scheme + auto-running precheck script)  [ ]
4. Route a staged agent into the rules-6/7-only validation gate in `workflow-reindex.py` (not the eight-rule `parsed_files` loop) so a staged agent validates like a live agent; re-document the now-false inert-rationale comment and the `discover_agent_citing_files` docstring; add a staged-agent validation-routing test to `test_workflow_reindex.py` asserting only rule-6/7 findings (no rule-1/2/3/4/5/8 over-fire). Co-requisite of step 2 per the Ordering constraint. — risk: high (workflow machinery: auto-running reindex script — the track's second high-care edit)  [ ]
5. Extend the remaining §1.7 consumers to the third prefix — the Phase 4 promotion `git add` path list and the pre-promotion divergence check in `create-final-design.md` (the `cp -r` is already prefix-agnostic); the `workflow.md §Final Artifacts` staging reference; `step-implementation.md`'s two staging enumerations; and `migrate-workflow/SKILL.md`'s migration pathspecs plus its `format`/`skill`/`rename` commit-classification rules. Validated by `workflow-reindex.py --check` + prose review. — risk: medium (workflow machinery, bounded behavioral: migration commit-classification dispatch + Phase 4 promotion git-add; remaining edits are prose references) — size: ~4 files; no mergeable low/medium work fits (rest of track is high)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. -->

## Validation and Acceptance

- A workflow-modifying branch that edits a `.claude/agents/**` file routes the
  write to `_workflow/staged-workflow/.claude/agents/...`; the live agent file
  stays at develop-state until Phase 4 (I6 holds for the third prefix).
- The prefix-agnostic marker matcher accepts both the develop-state two-prefix
  marker and the three-prefix marker as workflow-modifying signals; the plan's
  unchanged two-prefix Constraints marker activates the gate before and after
  this track's edits.
- The drift walk, the §1.6(b) stamp base, and `workflow-reindex.py` recognize
  `.claude/agents/**` as a workflow-format path in lockstep: an agent-only commit
  registers as a workflow-format change for drift, a new artifact created after
  an agent-only commit stamps to a base that includes it, and a staged agent file
  validates through the staged discovery path with rules 6/7 only (no
  rule-1/2/3/4/5/8 over-fire).
- A reviewer or gate that opens a staged `.claude/agents/**` file resolves it to
  the staged copy via the third-prefix §1.7(d) read-precedence caveat, not the
  live develop-state copy.
- The Phase 4 promotion (`git add` path list + pre-promotion divergence check)
  and the `migrate-workflow` commit-classification rules cover the third prefix.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In scope (live paths; routed to the staged mirror during execution):**
- `.claude/workflow/conventions.md` — §1.6(b) stamp-base computation, §1.6(h) stamp-walk omission note, §1.7(a)(b)(d)(e)
- `.claude/workflow/implementer-rules.md` — path-mapping gate, prefix-agnostic marker matcher, pre-commit gate refused-path set
- `.claude/workflow/prompts/technical-review.md`, `risk-review.md`, `adversarial-review.md`, `consistency-review.md`, `structural-review.md`, `review-gate-verification.md`, `dimensional-review-gate-check.md` — the §1.7(d) staged-read precedence caveat (one line each)
- `.claude/workflow/prompts/create-final-design.md` — Phase 4 `git add` path list + §1.7(f) pre-promotion divergence check (the `cp -r` is already prefix-agnostic)
- `.claude/workflow/workflow-drift-check.md` — pathspec comment
- `.claude/workflow/workflow.md` — §Final Artifacts staging reference
- `.claude/workflow/step-implementation.md` — staging reference
- `.claude/skills/migrate-workflow/SKILL.md` — migration pathspecs (Step 2 walk, rename-detect) + `format`/`skill`/`rename` commit-classification rules
- `.claude/skills/create-plan/SKILL.md`, `.claude/skills/edit-design/SKILL.md` — §1.6(b) `WORKFLOW_SHA` stamp-base copiers (one site in create-plan, two in edit-design)
- `.claude/scripts/workflow-startup-precheck.sh` — `WORKFLOW_PATHSPECS`, drift walk
- `.claude/scripts/workflow-reindex.py` — staged-agent rules-6/7-only scope split + inert-rationale comment/docstring
- `.claude/scripts/tests/test_workflow_startup_precheck.py`,
  `.claude/scripts/tests/test_workflow_reindex.py` — third-prefix pathspec + staged-agent validation-routing coverage

The review/gate-prompt edits are staging plumbing — which copy a reviewer reads
via §1.7(d) — not the reviewer-side output and schema changes Tracks 2-4 own;
this track changes no review behavior. Footprint is ~20 files, of which the
seven prompt caveats and the three §1.6(b) sites are one-line edits; the
high-care core is the marker matcher (step 1) and the reindex scope split
(step 4). The count sits at the lower edge of the ~20-25 split band and stays
one track per the uniform-generalization justification (see Decision Log).

**Out of scope:** the manifest schema, routing, reviewer-side agent output/schema
edits, and coverage annotations (Tracks 2-4). The §1.8 TOC-protocol bootstrap
line ("When you Read any file under `.claude/workflow/` or `.claude/skills/`…")
that appears in ~30 workflow/skill/agent files is a read-protocol concern, not
§1.7 staging, and is **not** in scope. `code-review/SKILL.md`'s
workflow-machinery review-selection triage already lists `.claude/agents/*.md`,
so it needs no change.

**Inter-track dependencies:** none upstream (precursor). Downstream — **Track 3**
depends on this track's three-prefix rule being in the staged mirror so its
`.claude/agents/**` edits stage via §1.7(d) reads-precedence; **Tracks 2 and 4**
edit only `.claude/workflow/**`, which stages under the existing two-prefix rule,
so they do not strictly require this track, but the plan orders this first per
the design's precursor directive.

**Marker-bootstrap contract (load-bearing):** the plan's `### Constraints` marker
is the develop-state two-prefix literal, verbatim. This track must keep every
marker matcher recognizing that literal (prefix-agnostic match), never require
the plan to carry a three-prefix marker. A matcher that exact-matches only the
three-prefix spelling would silently deactivate the gate for this very plan
after Track 1 commits.

## Base commit

9aa1dad2d3974f04adae4e208dc87aacc0d43020
