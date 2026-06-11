<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 4, suggestion: 3}
index:
  - {id: CR1, sev: should-fix, loc: implementation-plan.md:119-123, anchor: "### CR1 ", cert: C3, basis: "stub spec names bare headings; precheck script reads first top-level checkboxes in Plan Review / Final Artifacts"}
  - {id: CR2, sev: should-fix, loc: plan/track-1.md:80-83, anchor: "### CR2 ", cert: C17, basis: "research-log.md has four of the five D5 sections; no '## Baseline and re-validation' heading exists"}
  - {id: CR3, sev: should-fix, loc: plan/track-2.md:45-49, anchor: "### CR3 ", cert: C26, basis: "live 3A Risk gate is critical-paths/perf only; 'major architectural decisions' gates Adversarial today, not Risk"}
  - {id: CR4, sev: should-fix, loc: plan/track-2.md:67-70, anchor: "### CR4 ", cert: C32, basis: "plan-slim-rendering.md defines slim PLAN rendering only; no slim-track construct exists anywhere in the live machinery"}
  - {id: CR5, sev: suggestion, loc: plan/track-2.md:64-66, anchor: "### CR5 ", cert: C31, basis: "frozen-design guard sits at implementer-rules.md:75-81, not ~103; guard couples plan DRs AND track file in one sentence"}
  - {id: CR6, sev: suggestion, loc: implementation-plan.md:36-54, anchor: "### CR6 ", cert: C51, basis: "no track names the §1.8 annotation/TOC maintenance duty for staged prose edits or resolves reindex-on-staged-mirror"}
  - {id: CR7, sev: suggestion, loc: plan/track-1.md:124-130, anchor: "### CR7 ", cert: C52, basis: "third-scope lifecycle clause destination unstated; canonical review-file lifecycle lives in §2.1 (Track 2's section)"}
evidence_base: {section: "## Evidence base", certs: 52, matches: 41}
cert_index:
  - {id: C3, verdict: PARTIAL, anchor: "#### C3 "}
  - {id: C17, verdict: MISMATCHES, anchor: "#### C17 "}
  - {id: C26, verdict: MISMATCHES, anchor: "#### C26 "}
  - {id: C31, verdict: PARTIAL, anchor: "#### C31 "}
  - {id: C32, verdict: PARTIAL, anchor: "#### C32 "}
  - {id: C37, verdict: MISMATCHES, anchor: "#### C37 "}
  - {id: C48, verdict: ASPIRATIONAL, anchor: "#### C48 "}
  - {id: C49, verdict: ASPIRATIONAL, anchor: "#### C49 "}
  - {id: C50, verdict: ASPIRATIONAL, anchor: "#### C50 "}
  - {id: C51, verdict: GAP, anchor: "#### C51 "}
  - {id: C52, verdict: GAP, anchor: "#### C52 "}
flags: [CONTRACT_OK]
-->

Phase 2 consistency review, iteration 1, for the plan-slimization plan
(`docs/adr/plan-slimization/_workflow/`). Reviewer: reviewer-plan
(consistency). mcp-steroid was NOT reachable this session; every
verification below used grep/Read over the live tree. The plan's "code"
is workflow machinery (Markdown rule docs, SKILL files, prompts, shell
and Python scripts), so PSI would not apply in any case; exact-match
greps over section headings, file paths, and literal phrases are
reliable for these targets, and a reference-accuracy caveat is recorded
on the one certificate (C32) whose verdict rests on a negative search.
Staged-read precedence (§1.7(d)) was honored: `_workflow/staged-workflow/`
does not exist yet, so every `.claude/**` read resolved to the live file,
and the intent-axis pre-screen treated the live files' lack of the
planned changes as expected target state, not findings.

## Findings

### CR1 [should-fix]
**Certificate**: C3
**Location**: `implementation-plan.md` D1 (lines 119-123) and
`plan/track-1.md` Plan of Work step 6 (lines 140-146); same wording in
frozen `design.md` Part 4 (lines 623-627). Code:
`.claude/scripts/workflow-startup-precheck.sh:1456,1553-1554`.
**Issue**: D1 specifies the `minimal` stub as "`## Plan Review` heading,
glyph-valid `## Checklist` with one track entry, `## Final Artifacts`
heading, plus the D18 tier line", and presents this as "exactly what the
machinery reads". The frozen script reads more than headings: it calls
`section_first_checkbox_token` on `## Plan Review` (line 1456; any token
other than `done` means State 0) and on `## Final Artifacts` (lines
1553-1554; `done` means Done, anything else State D). A heading-only stub
parses to a readable state (State 0), so the planned fixture acceptance
("asserts a readable state") would pass, but there is no checkbox for the
orchestrator to flip to `[x]` after Phase 2 or Phase 4 — the resume
machine could never leave State 0 / never reach Done on a `minimal`
branch.
**Evidence**: script header contract lines 869-877 ("a `## Plan Review`
first top-level checkbox still `[ ]` means plan review pending"; "read
`## Final Artifacts`' first top-level checkbox");
`section_first_checkbox_token` (lines ~1011-1066) returns the empty token
when a section has no top-level checkbox, which collapses to State 0 /
State D respectively. The current plan file demonstrates the required
shape (one decision checkbox under each of the two headings).
**Proposed fix**: amend D1's shape spec (and track-1 step 6) to: a
`## Plan Review` section carrying its decision checkbox, a glyph-valid
`## Checklist` with one track entry, a `## Final Artifacts` section
carrying its decision checkbox, plus the D18 tier line. Optionally
strengthen track-1's acceptance line so the fixture also walks the
post-review transitions (flip Plan Review to `[x]` and assert State A/C;
flip the track and Final Artifacts and assert State D/Done), not only "a
readable state". The design's matching sentence is frozen; record it for
the Phase 4 `design-final.md` reconciliation.
**Classification**: mechanical
**Justification**: current-state claim about the frozen script's read
surface; single unambiguous correct rendering taken from the script
itself; the stub's intent (shape-complete minimal plan) is unchanged.

### CR2 [should-fix]
**Certificate**: C17
**Location**: `plan/track-1.md` Context and Orientation, "Precedent on
this branch" bullet (lines 80-83). Code:
`docs/adr/plan-slimization/_workflow/research-log.md`.
**Issue**: the bullet claims `_workflow/research-log.md` "already exists
with the five-section shape — the working prototype of the D5 artifact".
The file carries four of D5's five sections; `## Baseline and
re-validation` does not exist in it.
**Evidence**: `grep -n '^## '` over the log returns exactly `## Initial
request` (10), `## Decision Log` (44), `## Surprises & Discoveries`
(1073), `## Open Questions` (1192); `grep -c '^## Baseline'` returns 0
(file is 1289 lines). The only "Baseline" mentions are inside Decision
Log entry text (e.g. line 115) describing the D5 spec, not a section.
**Proposed fix**: reword the bullet to "already exists with four of the
five D5 sections (the `## Baseline and re-validation` section has not
been added; D5 fills it on workflow-modifying branches) — the working
prototype of the D5 artifact". Whether this branch's own log should gain
the fifth section is a separate execution-time choice; the Context bullet
only needs to stop overstating the prototype.
**Classification**: mechanical
**Justification**: current-state claim (Context and Orientation
carve-out); single unambiguous correct rendering from the file's actual
headings; plan intent unchanged.

### CR3 [should-fix]
**Certificate**: C26 (design-side echo: C37)
**Location**: `plan/track-2.md` Context and Orientation, `track-review.md`
bullet (lines 45-49). Code: `.claude/workflow/track-review.md:607-621`.
Frozen-design echo: `design.md` Part 6 ("the same characteristics that
warrant it today").
**Issue**: the bullet describes today's Phase-3A Risk pass as "gated by
track characteristics (critical paths, performance constraints, major
architectural decisions)". In the live table, "Moderate + critical paths
or performance constraints" adds Risk; "Moderate + major architectural
decisions or non-obvious scope" adds **Adversarial**, not Risk. The third
characteristic is misattributed.
**Evidence**: track-review.md lines 619-620 (the two upgrade rows quoted
above); line 611 (Complex runs all three). The design's Part 6 enumerates
the three characteristics for the target Risk gate and claims they are
"the same characteristics that warrant it today" — the enumeration as a
target is a design choice, but the same-as-today parenthetical is
factually wrong for "major architectural decisions".
**Proposed fix**: correct the track-2 Context bullet to the accurate
two-row mapping (Risk: critical paths / performance constraints;
Adversarial: major architectural decisions / non-obvious scope), and note
that the design's target Risk gate deliberately widens to include
architectural decisions, so Track 2 step 6 implements the design's
enumeration rather than "the same as today". The design parenthetical is
frozen; record it for the Phase 4 `design-final.md` reconciliation.
**Classification**: mechanical
**Justification**: current-state claim (Context and Orientation
carve-out); single unambiguous correct rendering from the live table;
the target gating stays as the design's Part-6 enumeration states.

### CR4 [should-fix]
**Certificate**: C32
**Location**: `plan/track-2.md` Context and Orientation,
`plan-slim-rendering.md` bullet (lines 67-70) and Plan of Work step 2
(lines 89-93). Code: `.claude/workflow/plan-slim-rendering.md`,
`.claude/scripts/render-slim-plan.py`,
`.claude/workflow/implementer-rules.md:100`.
**Issue**: the bullet says plan-slim-rendering.md "defines the slim
plan/track rendering sub-agents receive". The live file defines the slim
**plan** rendering only; no slim-track rendering exists anywhere in the
live machinery, and sub-agents receive the track file whole. Step 2
("Slim-track rendering includes the inline DR section") reads as
extending an existing mechanism, which could send the execution agent
hunting for a construct that is not there.
**Evidence**: plan-slim-rendering.md line 1 title is "Slim Plan Rendering
(for sub-agent contexts)"; its rendering rule operates on plan Checklist
entries only (line 139: pending-track detail "lives in the track file's
four track-level sections ... the transform is a no-op").
`render-slim-plan.py` docstring: "Slim plan rendering for sub-agent
contexts". `grep -rn 'slim track|slim-track|slim_track'` over
`.claude/workflow/`, `.claude/skills/`, and `.claude/scripts/*.py`
returns nothing. The implementer's input contract passes the full track
file (`step_file_path`, implementer-rules.md:100). Caveat: this verdict
rests on a negative search; the grep covered the three naming variants
across all three machinery roots, and the design itself (Part 4) names
"slim-track rendering" as part of the target consumption model, which is
consistent with it not existing yet.
**Proposed fix**: reword the Context bullet to "defines the slim plan
rendering sub-agents receive; track files are passed whole today (no
track-side rendering exists)", and reword step 2 to make the creation
explicit: "define the slim-track rendering in plan-slim-rendering.md
(new), including the track's inline DR section, so D7's consumption model
holds (slim plan + slim track with full DRs inline, `design.md`
path-only)".
**Classification**: mechanical
**Justification**: current-state claim (Context and Orientation
carve-out); single unambiguous correct rendering (the live file's actual
scope); the target consumption model from the design is unchanged.

### CR5 [suggestion]
**Certificate**: C31
**Location**: `plan/track-2.md` Context and Orientation,
`implementer-rules.md` bullet (lines 64-66). Code:
`.claude/workflow/implementer-rules.md:75-81,100-103`.
**Issue**: the bullet anchors the frozen-design guard "around line 103".
The guard lives in §Loading discipline at lines 75-81; line 103 is the
`design_path` input bullet that cross-references it. Also, the guard's
sentence couples two carriers: "The plan's Decision Records **and the
track file** are the authoritative source of truth during execution" —
the planned rewording ("plan's DRs" to "track's DRs") should account for
the whole sentence, not a bare phrase swap.
**Evidence**: implementer-rules.md line 75 ("**Frozen-design guard.**"),
line 79 (the authoritative-source sentence), line 103 (the cross-ref
bullet inside the inputs contract).
**Proposed fix**: re-anchor the bullet to "around line 75" (or drop the
line number for the section name, which is already correct) and note the
sentence-level rewording target.
**Classification**: mechanical
**Justification**: current-state claim; single correct rendering from the
file; cosmetic anchor precision, no scope change.

### CR6 [suggestion]
**Certificate**: C51
**Location**: `implementation-plan.md` ### Constraints (lines 36-54);
both track files' Plan of Work. Code: `conventions.md` §1.8(c)/(d),
`.claude/scripts/workflow-reindex.py`.
**Issue**: gap. Both tracks add new sections to TOC-bearing workflow docs
(the third adversarial scope, the design-review second target, new
conventions clauses), and every annotated doc's TOC region is rebuilt
from per-section annotations by `workflow-reindex.py --write`
(conventions.md §1.1 "TOC region" row: "authors do not maintain it by
hand"). Neither track names the duty to annotate new sections per §1.8(c)
and regenerate TOC regions, nor resolves whether the reindex script runs
against the staged mirror paths (it normally operates on live
`.claude/**`, which I6 freezes on this branch).
**Evidence**: no occurrence of `workflow-reindex` or §1.8
annotation/TOC duties in `implementation-plan.md`, `plan/track-1.md`, or
`plan/track-2.md` (grep over the three files); both tracks' in-scope
lists include multiple TOC-bearing docs.
**Proposed fix**: add one Constraints bullet (or a Plan-of-Work note in
each track): staged prose edits maintain §1.8 per-section annotations and
TOC regions; whether `workflow-reindex.py` can be pointed at the staged
mirror or the TOC rows are hand-written-then-reindexed-at-promotion is
resolved at implementation time.
**Classification**: mechanical
**Justification**: current-state gap against an existing global
convention; the added bullet restates a standing duty without changing
the plan's goals, scope, or architecture.

### CR7 [suggestion]
**Certificate**: C52
**Location**: `plan/track-1.md` Plan of Work step 4 (lines 124-130) and
Interfaces ("§2.5 only — §2.1 belongs to Track 2", line 251). Code:
`conventions-execution.md:275-292` (§2.1 `#### Review-file lifecycle`).
**Issue**: step 4 adds "a third-scope location/lifecycle clause" naming
the review-file home, `<type>-iter<N>.md` naming, commit-at-return
applicability, and the Phase-4 sweep, but does not say which subsection
the clause lands in. The canonical review-file location/lifecycle text
("Committed at reviewer-return", the `plan/track-N/reviews/` home, the
Phase 4 sweep) lives in §2.1's `#### Review-file lifecycle` — a section
Track 1 declares out of scope. An implementer could reasonably edit §2.1
and break the two tracks' disjoint-sections premise (both sizing
justifications rest on it).
**Evidence**: conventions-execution.md line 286-287 ("**Committed at
reviewer-return.** A review file is written **and committed** at
reviewer-return"); the `#### Review-file lifecycle` annotation (line 276)
carries execution roles/phases only. Track-1's step is titled "§2.5
access wiring", implying but not stating the destination.
**Proposed fix**: state the destination explicitly in step 4: the
third-scope location/lifecycle clause lands inside §2.5 (new sub-clause),
restating nothing from §2.1 beyond a cross-reference; §2.1 stays
untouched by Track 1.
**Classification**: mechanical
**Justification**: single clarifying rendering consistent with the
track's own in-scope declaration; prevents an out-of-scope edit without
changing what either track delivers.

## Evidence base

Tool note: all searches are grep/Read over the live tree (mcp-steroid
unreachable; targets are Markdown/shell/Python, not Java). Git state
checks ran against the fork point `e9377f7f13` (the workflow-sha stamp
on all four plan artifacts).

**Plan ↔ Code — implementation-plan.md and track-1.md current-state claims**

#### C1 Ref: §1.7(b) canonical workflow-modifying marker
- **Document claim**: plan Constraints line 38 carries the canonical marker sentence.
- **Search performed**: Read conventions.md:821-861; string compare.
- **Code location**: conventions.md:829.
- **Actual**: `This plan is workflow-modifying: it edits .claude/workflow/**, .claude/skills/**, or .claude/agents/**.` — byte-identical to the plan's sentence.
- **Verdict**: MATCHES

#### C2 Ref: precheck script reads `## Plan Review` / `## Checklist` / `## Final Artifacts`
- **Document claim**: D1/D18 — the stub must carry what the state machine reads; spawn instructions ask to ground this.
- **Search performed**: grep -n over workflow-startup-precheck.sh for the three headings; Read lines 869-877, 1440-1600.
- **Code location**: script:869-877 (contract comment), 1449-1467 (State 0 + Checklist walk), 1548-1560 (Final Artifacts).
- **Actual**: State 0 from Plan Review first checkbox; first `[ ]` track from a column-0 `- [<glyph>] Track <N>:` walk with closed-enum glyph validation; Done vs State D from Final Artifacts first checkbox.
- **Verdict**: MATCHES (script exists and reads exactly these constructs)

#### C3 Ref: D1 stub-shape spec vs the script's actual read surface
- **Document claim**: plan:119-123 / track-1.md:140-146 / design.md:623-627 — stub = bare `## Plan Review` heading + glyph-valid `## Checklist` (one entry) + bare `## Final Artifacts` heading + tier line, "exactly what the machinery reads".
- **Search performed**: Read script:995-1066 (`section_first_checkbox_token`), 1449-1465, 1548-1560.
- **Code location**: script:1456 (`section_first_checkbox_token "$plan_file" "Plan Review"`), 1553-1554 (Final Artifacts), 1011-1066 (empty token when no checkbox).
- **Actual**: the machinery reads first top-level decision checkboxes in both sections, not headings; a checkbox-less section parses (State 0 / State D) but can never record `[x]`.
- **Verdict**: PARTIAL → CR1

#### C4 Ref: §1.6(f) stamped-type enumeration and exclusion rationale (D19)
- **Document claim**: §1.6(f) enumerates exactly four stamped types; D19 reuses "the same exclusion rationale §1.6(f) already records for design-mutations.md".
- **Search performed**: Read conventions.md:661-679.
- **Code location**: conventions.md:664-669 (positive list of four), 677-679 (design-mutations.md: "append-only contract makes the file replay-immune by construction").
- **Actual**: as claimed; the append-only/replay-immune rationale exists verbatim for design-mutations.md.
- **Verdict**: MATCHES

#### C5 Ref: §1.6(h) walk, script lines ~391-394, conformance fixture
- **Document claim**: track-1.md:63-68 — §1.6(f) names the four types the frozen script walk hardcodes at lines ~391-394; the §1.6(h) walk is the byte-source pinned by a conformance fixture under `.claude/scripts/tests/`.
- **Search performed**: Read conventions.md:698-734; sed script:380-400; grep test_workflow_startup_precheck.py for conformance.
- **Code location**: conventions.md:723-726 (spec walk); script:391-396 (`ls` over the four globs in no_drift_normalization, "keeping the byte-copy contract with §1.6(h) intact"); test_workflow_startup_precheck.py:2927 ("§ 1.6(h) source-extraction conformance").
- **Actual**: all three exist as claimed; line range ~391-394 is accurate.
- **Verdict**: MATCHES

#### C6 Ref: D19 "the presence check only fires on enumerated types"
- **Document claim**: an unstamped research-log.md never trips the unstamped-artifact protocol.
- **Search performed**: Read the §1.6(h) glob set (conventions.md:723-726) and the script's mirrored walk.
- **Code location**: conventions.md:723-726.
- **Actual**: the walk enumerates only implementation-plan.md, design.md, design-mechanics.md, plan/track-*.md; research-log.md is outside the glob set.
- **Verdict**: MATCHES

#### C7 Ref: design-mechanical-checks.py `section_has_references`, live path
- **Document claim**: plan Constraints + track-1 Context — the script implements `section_has_references` and sits outside the three §1.7 stageable prefixes.
- **Search performed**: grep design-mechanical-checks.py; Read conventions.md:765-767.
- **Code location**: design-mechanical-checks.py:666-674 (accepts `### References` or `**References.**`); §1.7 prefixes are `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**` — `.claude/scripts/` excluded.
- **Actual**: as claimed; the backward-compat constraint (old footer must keep passing) is grounded — current code knows only the old spellings.
- **Verdict**: MATCHES

#### C8 Ref: create-plan Step 1c routes on bare design/plan presence
- **Document claim**: track-1.md:48-50.
- **Search performed**: grep create-plan/SKILL.md for Step 1c.
- **Code location**: SKILL:127-180; the `ls` over `design.md` + `implementation-plan.md` at line 133.
- **Actual**: routing keys on which of the two files exist plus committed-and-clean checks; no tier awareness (the planned branch is new).
- **Verdict**: MATCHES

#### C9 Ref: create-plan Step 4a/4b unconditional; fenced templates with stamp directive; Phase 0 in conversation context; Step 1b exists
- **Document claim**: track-1.md:47-52; track-1 step 5 cites "Step 1b/2/3".
- **Search performed**: grep create-plan/SKILL.md (Step 4a/4b, stamp, research-log, Step 1b); grep research.md.
- **Code location**: SKILL:214-276 (4a/4b mandatory design), 362-393 (stamp computed once, two Step-4b fenced templates + design template), 46/68/85 (Step 1b mkdir); research.md:38 ("The output of this phase is **conversation context**").
- **Actual**: as claimed; no research-log mention anywhere in the SKILL (expected — Track 1 adds it).
- **Verdict**: MATCHES

#### C10 Ref: edit-design Step 3.5 — phase1-creation only, precedes cold-read, ~line 398
- **Document claim**: track-1.md:53-55.
- **Search performed**: grep edit-design/SKILL.md headings and kind-conditionality.
- **Code location**: SKILL:398 (`### Step 3.5: Run the adversarial sub-agent (phase1-creation only)`), 155-162 (§Workflow intro names the kind-conditionality).
- **Actual**: as claimed; line anchor exact.
- **Verdict**: MATCHES

#### C11 Ref: adversarial-review.md carries two scopes; design scope at line ~41
- **Document claim**: track-1.md:55-58.
- **Search performed**: grep heading lines in prompts/adversarial-review.md.
- **Code location**: prompt:41 (`## Design-scoped review (Phase 1)`); base Phase-3A track machinery from line 100 (`## Workflow Context`) onward.
- **Actual**: exactly two scopes today; the retargeting pattern the third scope will follow is present.
- **Verdict**: MATCHES

#### C12 Ref: design-review.md targets design.md only (Inputs / Comprehension questions / Verdict)
- **Document claim**: track-1.md:58-59.
- **Search performed**: grep heading lines and design.md mentions in prompts/design-review.md.
- **Code location**: prompt:38-41 (`## Inputs` — `design_path`), 144 (`## Comprehension questions`), 234 (`## Verdict`).
- **Actual**: single-target cold-read prompt; no plan/track target exists.
- **Verdict**: MATCHES

#### C13 Ref: conventions.md §1.1 lacks tier/log/aggregator terms; §1.2 lacks research-log.md
- **Document claim**: track-1.md:62-63.
- **Search performed**: Read conventions.md:64-178.
- **Code location**: §1.1 table (lines 67-88), §1.2 listing (lines 100-153).
- **Actual**: glossary terms enumerated; none of change tier / research log / aggregator plan / track-canonical present; §1.2 layout lists implementation-plan, design, design-mechanics, design-mutations, plan/, handoff-* — no research-log.md.
- **Verdict**: MATCHES

#### C14 Ref: §2.5 annotated for execution roles/phases only (D17 gap)
- **Document claim**: track-1.md:69-72; plan D17/Integration Points — TOC row and used subsections need `planner`/`1`.
- **Search performed**: grep conventions-execution.md TOC row and section annotations.
- **Code location**: conventions-execution.md:13 (TOC row roles/phases), 474, 483, 546, 598, 631 (section markers).
- **Actual**: roles include orchestrator/reviewers (no `planner`); phases 2,3A,3B,3C,4 (no `1`). The wiring gap D17 names is real.
- **Verdict**: MATCHES

#### C15 Ref: risk-tagging.md HIGH-risk category list (D4 source)
- **Document claim**: plan D4 / design Part 1 — seven categories: concurrency, crash-safety/durability, public API, security, architecture/cross-component, performance hot path, workflow machinery.
- **Search performed**: grep risk-tagging.md headings.
- **Code location**: risk-tagging.md:96-161 (§HIGH-risk triggers with exactly those seven `### ` subsections).
- **Actual**: list matches one-for-one, including "Workflow machinery".
- **Verdict**: MATCHES

#### C16 Ref: design-document-rules.md — adversarial-then-cold-read; `### References` footer shape
- **Document claim**: track-1.md:75-77.
- **Search performed**: grep design-document-rules.md.
- **Code location**: rules:169-175 (phase1-creation: "an adversarial pass, then cold-read"), 261 (References footer: `### References` or `**References.**`).
- **Actual**: as claimed; D11's rename targets are real.
- **Verdict**: MATCHES

#### C17 Ref: research-log.md "five-section shape" precedent
- **Document claim**: track-1.md:80-83 — the log "already exists with the five-section shape".
- **Search performed**: `grep -n '^## '` and `grep -c '^## Baseline'` over the log.
- **Code location**: research-log.md:10,44,1073,1192 — four sections; `^## Baseline` count 0 (1289-line file).
- **Actual**: `## Baseline and re-validation` absent; only Decision Log entry text mentions it as spec (line 115).
- **Verdict**: MISMATCHES → CR2

#### C18 Ref: research-log.md line-1 stamp (D19 "harmless stamp predating the rule")
- **Document claim**: plan D19 risk bullet; track-1.md:82-83.
- **Search performed**: head -1 of the log.
- **Code location**: research-log.md:1.
- **Actual**: `<!-- workflow-sha: e9377f7f13... -->` present; combined with C6, no walk reads it.
- **Verdict**: MATCHES

#### C19 Ref: mid-phase-handoff.md exists (Track 1 in-scope file 6, D15 queue block)
- **Document claim**: track-1 step 7 and in-scope list.
- **Search performed**: ls .claude/workflow/.
- **Code location**: .claude/workflow/mid-phase-handoff.md.
- **Actual**: present; the queue block is a target-state addition (pre-screen: no finding).
- **Verdict**: MATCHES

#### C20 Ref: `<type>-iter<N>.md` naming practice and the existing `reviews/` directory
- **Document claim**: plan D17 + track-1 step 4 — naming follows track-review.md's practice; the home "e.g. the existing `reviews/` directory".
- **Search performed**: grep track-review.md for iter naming; ls _workflow/reviews/.
- **Code location**: track-review.md:641 (`plan/track-N/reviews/<type>-iter<N>.md`); on-disk `_workflow/reviews/log-gate-B1-B2-iter{1,2,3}.md`.
- **Actual**: both exist; this branch's own gate already used the `_workflow/reviews/` home with iter-suffixed names.
- **Verdict**: MATCHES

#### C21 Ref: "commit-at-return" concept
- **Document claim**: plan D17 wiring note + track-1 step 4 name "commit-at-return applicability".
- **Search performed**: grep -rn 'commit-at-return|committed.*return' .claude/workflow/.
- **Code location**: conventions-execution.md:286-287 ("**Committed at reviewer-return.** A review file is written **and committed** at reviewer-return").
- **Actual**: the hyphenated shorthand resolves to the §2.1 `#### Review-file lifecycle` rule; not a phantom. Ownership tension recorded at C52.
- **Verdict**: MATCHES

#### C22 Ref: planning.md §Track descriptions (sizing-justification anchor)
- **Document claim**: both tracks' sizing justifications cite `planning.md` §Track descriptions.
- **Search performed**: grep planning.md.
- **Code location**: planning.md:382 (`## Track descriptions`).
- **Actual**: section exists.
- **Verdict**: MATCHES

#### C23 Ref: execute-tracks SKILL — State 0 routing tier-agnostic; pass selection inside implementation-review.md
- **Document claim**: both tracks' out-of-scope lists.
- **Search performed**: grep execute-tracks/SKILL.md.
- **Code location**: SKILL:37 (State 0 loads implementation-review.md), 48 ("loaded only when State 0 fires"), 88-89.
- **Actual**: the SKILL dispatches states; pass content lives in the loaded doc. Leaving the SKILL untouched is coherent.
- **Verdict**: MATCHES

#### C24 Ref: out-of-scope file references exist
- **Document claim**: track-2 out-of-scope names track-code-review.md, review-agent-selection.md; plan Non-Goals name the `review-workflow-*` agents.
- **Search performed**: ls .claude/workflow/ and the agent roster.
- **Code location**: both files present in .claude/workflow/; review-workflow-* agents exist in the agent roster.
- **Actual**: all referenced exclusions are real constructs.
- **Verdict**: MATCHES

**Plan ↔ Code — track-2.md current-state claims**

#### C25 Ref: track-review.md selects the 3A panel by step count (Simple/Moderate/Complex)
- **Document claim**: track-2.md:45-47; plan/design reconciliation claims.
- **Search performed**: grep + Read track-review.md:596-621.
- **Code location**: track-review.md:596-611.
- **Actual**: Simple (1-2 steps) / Moderate (3-5) / Complex (6-7 or critical path / high-risk) drives the panel; the step-count axis the tier replaces is real.
- **Verdict**: MATCHES

#### C26 Ref: today's Risk-pass gating characteristics
- **Document claim**: track-2.md:47-49 — Risk "gated by track characteristics (critical paths, performance constraints, major architectural decisions)".
- **Search performed**: Read track-review.md:613-621.
- **Code location**: track-review.md:619 (Risk: "critical paths or performance constraints"), 620 (Adversarial: "major architectural decisions or non-obvious scope").
- **Actual**: "major architectural decisions" warrants Adversarial today, not Risk.
- **Verdict**: MISMATCHES → CR3

#### C27 Ref: implementation-review.md — unconditional passes with a design half; frozen-design findings defer to Phase 4; State 0 construct
- **Document claim**: track-2.md:50-53; plan Integration Points ("implementation-review.md State 0").
- **Search performed**: grep implementation-review.md.
- **Code location**: doc:37 (State 0 detection), 79-80 ("design ↔ code ↔ plan alignment"), 580-583 (§`design.md` is frozen — defers to Phase 4).
- **Actual**: both passes run unconditionally; design-touching findings recorded-only and deferred; State 0 is the real Phase-2 entry construct.
- **Verdict**: MATCHES

#### C28 Ref: structural-review doc + prompt — design-destination bloat fixes and the plan/design duplication check
- **Document claim**: track-2.md:54-58 — checks "would fire backwards against the now-mandated full track DRs".
- **Search performed**: grep both files.
- **Code location**: structural-review.md:61-75 (fix destinations "move ... to a `design.md` section"; duplication row: DR body >50 lines AND matching design.md section title); prompts/structural-review.md:341-346 (same check), 318-334 (design-destination fixes).
- **Actual**: as claimed; the repurpose target (D10) is grounded — the live check compares plan DRs against design.md sections and routes bloat to design.md.
- **Verdict**: MATCHES

#### C29 Ref: prompts/consistency-review.md reads design + plan + code with no design-absent branch
- **Document claim**: track-2.md:59-60.
- **Search performed**: full Read of the prompt (this reviewer's own task definition).
- **Code location**: prompt:111-124 (Inputs list the design document unconditionally; only the staged-read precedence is conditional).
- **Actual**: no design-presence guard exists; D10's conditional is new work.
- **Verdict**: MATCHES

#### C30 Ref: inline-replanning.md — updatable-section lists, completed-track pause, ESCALATE
- **Document claim**: track-2.md:61-63 — lists carry no `## Decision Log` today; pause has no documentation-only carve-out; the ESCALATE path D12 rides exists.
- **Search performed**: Read inline-replanning.md:215-267; grep ESCALATE.
- **Code location**: cases 2 and 3 (lines ~236-260) update Purpose/Context/Plan of Work/Interfaces/Validation — `## Decision Log` absent from both lists (its only nearby mention, line ~229, is case 1's empty-at-creation continuous-log enumeration); case 4 (lines ~261-267) "Pause and ask the user" with no doc-only append carve-out; doc title "Inline Replanning (ESCALATE)".
- **Actual**: all three sub-claims hold.
- **Verdict**: MATCHES

#### C31 Ref: implementer-rules.md frozen-design guard "around line 103"
- **Document claim**: track-2.md:64-66 — §Loading discipline carries the guard citing "plan's DRs" around line 103.
- **Search performed**: grep + Read implementer-rules.md:56-110.
- **Code location**: guard at lines 75-81 (§Loading discipline): "The plan's Decision Records and the track file are the authoritative source of truth during execution"; line 103 is the `design_path` input bullet cross-referencing it.
- **Actual**: section name correct, substance correct, line anchor off (~75 vs ~103); the guard sentence couples plan DRs and the track file.
- **Verdict**: PARTIAL → CR5

#### C32 Ref: plan-slim-rendering.md "slim plan/track rendering"
- **Document claim**: track-2.md:67-70 — the file "defines the slim plan/track rendering sub-agents receive".
- **Search performed**: Read plan-slim-rendering.md title/TOC/rule; head render-slim-plan.py; `grep -rn 'slim track|slim-track|slim_track'` over .claude/workflow/, .claude/skills/, .claude/scripts/*.py; grep implementer-rules.md for step_file_path.
- **Code location**: plan-slim-rendering.md:1 ("# Slim Plan Rendering"); rule line 139 (pending-track transform is a no-op; detail lives in the track file); render-slim-plan.py docstring ("Slim plan rendering"); implementer-rules.md:100 (full track file passed).
- **Actual**: slim **plan** rendering only; zero hits for any slim-track construct (negative-search caveat: three naming variants over all three machinery roots; design Part 4 itself treats slim-track rendering as target).
- **Verdict**: PARTIAL → CR4

#### C33 Ref: §2.1 `## Decision Log` lifecycle row execution-time-only today
- **Document claim**: track-2.md:71-74.
- **Search performed**: grep conventions-execution.md for Decision Log.
- **Code location**: conventions-execution.md:233 (lifecycle row: Phase 1 = "Move 1 placeholder"; content from sub-step-7 promotion and gate-override/inline-replan entries).
- **Actual**: Phase 1 writes a placeholder only; the plan-at-start inline-DR home is the planned change.
- **Verdict**: MATCHES

#### C34 Ref: workflow.md §Final Artifacts + prompts/create-final-design.md — unconditional artifacts, promotion + cleanup in the Phase-4 prompt
- **Document claim**: track-2.md:75-79; plan Integration Points ("verdict fold inserted before the `_workflow/` cleanup; §1.7(f) promotion machinery unchanged").
- **Search performed**: grep both files.
- **Code location**: workflow.md:604-636 (design-final.md + adr.md, unconditional); create-final-design.md:331-364 (§1.7 promotion step, §1.7(f) rebase-precedes-promotion halt), 380-405 (cleanup commit Step 6 removes staged subtree and `_workflow/`).
- **Actual**: as claimed; an insertion point before the cleanup exists.
- **Verdict**: MATCHES

**Design ↔ Code**

#### C35 Ref: design Part 1 Gate-1 category list vs risk-tagging.md
- **Document claim**: design names the seven HIGH categories as already maintained in risk-tagging.md.
- **Search performed**: same as C15.
- **Code location**: risk-tagging.md:96-161.
- **Actual**: one-for-one match.
- **Verdict**: MATCHES

#### C36 Ref: design Part 6 "Simple track → Technical only" floor
- **Document claim**: `minimal`'s Technical-only is "the analog of the live floor that drops both Risk and Adversarial".
- **Search performed**: Read track-review.md:609,617.
- **Code location**: track-review.md:609/617.
- **Actual**: Simple (1-2 steps) runs Technical only, any characteristics; the floor is real.
- **Verdict**: MATCHES

#### C37 Ref: design Part 6 "the same characteristics that warrant it today" (Risk gate)
- **Document claim**: the target Risk gate's three characteristics are those that warrant Risk today.
- **Search performed**: same as C26.
- **Code location**: track-review.md:619-620.
- **Actual**: only two of the three warrant Risk today; the third warrants Adversarial. Frozen-design text; correction defers to `design-final.md` (folded into CR3's body).
- **Verdict**: MISMATCHES (frozen; routed via CR3 note, no separate plan edit)

#### C38 Ref: design Part 3 — "explicit read-instruction alone is non-viable under §1.8(e)/(f)"
- **Document claim**: D17's wiring must extend annotation axes because readers filter before opening.
- **Search performed**: Read conventions.md:1308-1330 (e), 1498-1525 (f).
- **Code location**: §1.8(f) flowchart: role/phase mismatch → "Skip — no open" at file level and "Skip section" at TOC level.
- **Actual**: a planner/phase-1 reader following the protocol would never reach §2.5 sections annotated without planner/1; the both-axes extension is the correct mechanism.
- **Verdict**: MATCHES

#### C39 Ref: design Part 3 — "phase4-creation already has no adversarial pass"
- **Document claim**: Phase 4 unaffected by the relocation.
- **Search performed**: Read edit-design SKILL:155-162.
- **Code location**: SKILL:159-162 ("Step 3.5 (adversarial) runs **only** for phase1-creation ... Every other kind goes straight from Step 3 ... to Step 4 (cold-read)").
- **Actual**: as claimed.
- **Verdict**: MATCHES

#### C40 Ref: design Part 5 — Step 4b "pre-persist confirmation" exists
- **Document claim**: the written plan and tracks are presented for the user's pre-persist confirmation (D15's window anchor).
- **Search performed**: grep create-plan SKILL for persist/confirm.
- **Code location**: SKILL:645 ("Once the user confirms the files this session produced look right, persist").
- **Actual**: the confirmation step exists in the live Step 4b → Step 5 seam; not a phantom anchor.
- **Verdict**: MATCHES

#### C41 Ref: design Part 4 — "the implementer's frozen-design guard already names the live DR authoritative"
- **Document claim**: foundation for D7's carrier flip.
- **Search performed**: Read implementer-rules.md:75-81.
- **Code location**: implementer-rules.md:79-81.
- **Actual**: "The plan's Decision Records and the track file are the authoritative source of truth during execution; the frozen design.md may have diverged" — supports the claim; D7's rewording is the planned delta (see CR5 for the sentence-level note).
- **Verdict**: MATCHES

**Design ↔ Plan**

#### C42 Ref: every plan `**Full design**` reference resolves to a real design.md section
- **Document claim**: D1-D17 carry `**Full design**` lines naming Part-level sections.
- **Search performed**: full Read of design.md; heading compare for all 17 cited section titles.
- **Code location**: design.md Parts 1-7 (e.g. "The aggregator plan and the minimal stub", "The two gates and the tier map", "Reuse and the third scope", "Review-iteration batching", "The Phase 4 audit trail", "Staging for this workflow-modifying branch").
- **Actual**: all cited sections exist under the named Parts; no dangling reference.
- **Verdict**: MATCHES

#### C43 Ref: D18/D19 have no design coverage (plan-new DRs)
- **Document claim**: D18/D19 carry no `**Full design**` line.
- **Search performed**: Read plan D18/D19.
- **Code location**: implementation-plan.md:355-384.
- **Actual**: both are Step-4b-time decisions absent from the frozen design (tier-line persistence; log unstamped). Expected frozen-design lag; the honest omission of a `**Full design**` line confirms intent. `design-final.md` reconciles at Phase 4. No finding per the intent-axis pre-screen and the revised-DR divergence rule.
- **Verdict**: MATCHES (with note)

#### C44 Ref: plan D9 matrix vs design Part 6 matrix
- **Document claim**: per-(pass, tier) cells align between plan D9 and design Part 6.
- **Search performed**: side-by-side compare of the two tables and prose.
- **Code location**: implementation-plan.md:229-239; design.md Part 6 table.
- **Actual**: identical cell semantics (minimal drops structural + risk + adversarial, lightens consistency to track-vs-code; lite/full narrowed adversarial with track-1 episode-challenge drop; 3B/3C unchanged).
- **Verdict**: MATCHES

#### C45 Ref: scope indicators vs in-scope file lists
- **Document claim**: Track 1 "~13 files", Track 2 "~11 files".
- **Search performed**: count the numbered in-scope lists in both track files.
- **Code location**: track-1.md:243-255 (13 items), track-2.md:198-208 (11 items).
- **Actual**: counts match; both sizing justifications present per planning.md §Track descriptions; the shared-file split (conventions-execution.md §2.5 vs §2.1) is recorded in both files as the rule requires.
- **Verdict**: MATCHES

**Invariants**

#### C46 Invariant: I6 — live workflow at develop until promotion
- **Document claim**: `git diff <fork-point> HEAD -- .claude/workflow .claude/skills .claude/agents` stays empty.
- **Code evidence**: `git diff --stat e9377f7f13 HEAD` over the three prefixes returned empty output this session.
- **Mechanism**: §1.7 staging (conventions.md:762-1113); no staged mirror exists yet because execution has not started.
- **Verdict**: ENFORCED (currently holds; the staging machinery exists to keep it through Phase 3)

#### C47 Invariant: S1 — resume machinery untouched
- **Document claim**: script + existing tests byte-identical to develop; only additive new files under `.claude/scripts/`; the stub parses through the unchanged script.
- **Code evidence**: `git diff --stat e9377f7f13 HEAD -- .claude/scripts` empty; the script's `--mode {full,divergence-only,migrate-range}` exists for the planned fixture invocation (`--mode full`).
- **Mechanism**: untouched-now is fact; the fixture half is Track 1 step 12 (named implementing work). See CR1 for the stub-shape precision the fixture's "readable state" assertion alone would not catch.
- **Verdict**: ENFORCED (current state) / fixture half ASPIRATIONAL with implementing track named — no finding

#### C48 Invariant: S2 — one-way log → carrier seed
- **Document claim**: log read at exactly two sites (Step 4a/4b authoring; Phase-2 consistency cross-check).
- **Code evidence**: no live machinery reads the log today (grep: no research-log reference in .claude/workflow/ or .claude/skills/); the discipline is created by Tracks 1-2.
- **Mechanism**: ASPIRATIONAL — enforced by D8 absorption criterion (Track 1) and the propagation duty (Track 2); track-1 acceptance carries the two-read-site assertion.
- **Verdict**: ASPIRATIONAL (implementing tracks named — no finding per pre-screen)

#### C49 Invariant: S3 — freeze order preserved
- **Document claim**: no documented path reaches design cold-read with an open log-adversarial entry.
- **Code evidence**: today the ordering lives inside edit-design (Step 3.5 before Step 4, SKILL:159-162); the relocation re-creates it as a gate on the cold-read step.
- **Mechanism**: ASPIRATIONAL — Track 1 step 8; acceptance line covers the batch loop-back.
- **Verdict**: ASPIRATIONAL (implementing track named — no finding)

#### C50 Invariant: S4 — complexity signals never stack
- **Document claim**: tier selects Phase 2/3A; per-step risk tag gates 3B; triage gates 3C.
- **Code evidence**: today's separation exists (track-review.md step-count axis at 3A; risk-tagging.md tag gates 3B; track-code-review triage at 3C); the staged rewrite must preserve it.
- **Mechanism**: ASPIRATIONAL — Track 2 step 6 + acceptance ("no staged sentence combines the tier and the per-step risk tag").
- **Verdict**: ASPIRATIONAL (implementing track named — no finding)

**Gaps**

#### C51 Gap: §1.8 annotation/TOC duty for staged prose edits
- **Document claim**: (absence) — neither the plan Constraints nor either track names per-section annotation and TOC-region maintenance for the new/edited sections in the eleven staged prose files.
- **Code evidence**: conventions.md §1.1 "TOC region" row ("`workflow-reindex.py --write` rebuilds the table from per-section annotations; authors do not maintain it by hand"); §1.8(c)/(d) require annotations on every `##`/`###` heading; grep over the three plan artifacts finds no workflow-reindex or annotation-duty mention.
- **Mechanism**: no owner named; reindex-on-staged-mirror feasibility unstated (the script normally targets live `.claude/**`, which I6 freezes).
- **Verdict**: GAP → CR6

#### C52 Gap: destination of the third-scope location/lifecycle clause
- **Document claim**: track-1 step 4 adds the clause under the "§2.5 access wiring" step without naming the destination subsection.
- **Code evidence**: the canonical review-file location/lifecycle text lives in conventions-execution.md §2.1 `#### Review-file lifecycle` (lines 275-292), a section Track 1 declares out of scope ("§2.5 only — §2.1 belongs to Track 2", track-1.md:251).
- **Mechanism**: ambiguity could route an implementer into §2.1 and break the disjoint-sections premise both sizing justifications rest on.
- **Verdict**: GAP → CR7
