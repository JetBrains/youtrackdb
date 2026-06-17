<!-- MANIFEST
role: reviewer-technical
phase: 3A
track: track-1
iteration: 1
verdict: PASS
findings: 2
blockers: 0
should_fix: 1
suggestions: 1
index:
  - id: T1
    sev: should-fix
    anchor: "T1"
    loc: "track-1.md:195-197, :148 (D18); research.md:116-119"
    cert: "Premise: canonical S2 wording at research.md"
    basis: premise
  - id: T2
    sev: suggestion
    anchor: "T2"
    loc: "track-1.md:238-244, :308 (acceptance); plan Component Map roles[]"
    cert: "Premise: Agent-definition tools: allow-list mechanism"
    basis: premise
evidence_base:
  premises: 9
  edge_cases: 1
  integrations: 2
  confirmed: 9
  not_confirmed: 2
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise — canonical S2 wording at `research.md` §"Read-scope discipline (S2)"
**Location**: track-1.md `## Context and Orientation` (lines 195-197) and D18 (line 148); against `.claude/workflow/research.md` lines 116-119
**Issue**: The track's "what is there today" description and D18 both characterize the live canonical S2 statement as one that "names the author or the cold-read reviewer as the authoring reader." That overstates the live wording. The live S2 reads: *"the log is read for decision content in exactly two places: at Step 4a/4b artifact authoring (to seed the carriers) and by the Phase-2 consistency review."* It names the **read site** ("Step 4a/4b artifact authoring … to seed the carriers"), not the reader **role**. There is no "author or cold-read reviewer" naming in research.md's S2 to edit — the named-reader framing the track wants to *extend* (adding the absorption agent as a named sanctioned reader) does not exist in the source yet. The quoted "exactly two places … and by the Phase-2 consistency review" wording is otherwise accurate (research.md:117-119). This is a faithful-orientation defect: the deliverable (D18 — extend S2 to sanction the absorption agent) is well-founded and feasible, but it is an **addition** of reader-naming, not an **edit** of an existing reader name. If the implementer reads the track literally and greps research.md for "the author or the cold-read reviewer" to edit it, the string is absent and the edit stalls. The design-document-rules.md restatement (line 103-104) similarly reads "the log is read for decision content only at Step 4a/4b authoring and the Phase-2 consistency cross-check" — site-named, not reader-named, same as research.md.
**Proposed fix**: In track-1.md `## Context and Orientation` (lines 195-197) and D18 (line 148), replace "naming the author or the cold-read reviewer as the authoring reader" with the accurate live characterization — the canonical S2 names the **authoring read site** ("Step 4a/4b artifact authoring, to seed the carriers") without enumerating reader roles, and D18's deliverable is to *add* the warm absorption agent as an explicitly named sanctioned reader under that site (so a literal S2 reading no longer treats a separate absorption-only spawn as a third site). No change to the deliverable itself or the in-scope file list — only the orientation prose and D18's framing.

### T2 [suggestion]
**Certificate**: Premise — Agent-definition `tools:` allow-list and `model:` field mechanism
**Location**: track-1.md `## Plan of Work` step 1 (lines 235-244) and acceptance (line 308); plan Component Map roles
**Issue**: The track relies on a per-agent `tools:` allow-list and `model: sonnet` in agent-definition frontmatter (D13/D14, D7). The frontmatter mechanism is confirmed-feasible: all 20 existing `.claude/agents/*.md` carry `name`/`description`/`model` frontmatter and `model:` is universally used (the track's "none currently carries `tools:`" claim is exactly right — `grep -lE '^tools:' .claude/agents/*.md` returns zero). But two facts are unverified from inside this repo and worth a Phase-B confirmation note rather than left implicit: (1) no existing agent def sets `model: sonnet` (every one uses `model: opus`), so the `sonnet` value for the absorption check is the first of its kind in this project's agent defs — the harness accepts the `model:` key but the specific `sonnet` value has no in-repo precedent; (2) the `tools:` allow-list is a harness/Agent-tool capability the project's own auto-memory tracks as an open lever (YTDB-1094), not something any committed agent def exercises, so its exact value syntax (tool-name list, whether mcp-steroid PSI is named as `mcp__localhost-6315__*` or a friendly alias) is a Phase-A/B realization detail with no in-repo example to copy. Neither is a blocker — both are documented as implementation-settled in D13 and the track's "Exact filenames set at Phase A" / "Phase A decides" hedges — but a one-line note would keep the implementer from assuming a copyable precedent that does not exist.
**Proposed fix**: Optional. In `## Plan of Work` step 1 or `## Interfaces and Dependencies` `## Signatures / contracts`, add a one-line note that `tools:` has no committed precedent in `.claude/agents/` (YTDB-1094 lever) and `model: sonnet` is the first non-`opus` agent-def model in this repo, so the exact `tools:` value syntax (including how mcp-steroid PSI is named for the author) is confirmed against the live Agent-tool docs at Step 1 implementation. No scope or boundary change.

## Evidence base

#### Premise: `edit-design/SKILL.md` has the Step 1 (apply) / Step 4 (review) / Step 6 (iterate) structure the rework assumes
- **Track claim**: track-1.md D11/Plan-of-Work step 3 and `## Context and Orientation` — "Step 1 (apply) spawns the author … Step 4 (review) spawns the per-round auditor-plus-absorption pair … Step 6 is the dual-clean loop"; "Step 6 escalates to the user on budget exhaustion."
- **Search performed**: Read `.claude/skills/edit-design/SKILL.md` in full (TOC + body).
- **Code location**: `.claude/skills/edit-design/SKILL.md` — §Step 1 Apply (line 167), §Step 4 Run the cold-read sub-agent (line 401), §Step 6 Iterate (line 536); budget-exhaustion escalation at lines 567-579.
- **Actual behavior**: Step 1 = "Use the `Edit` tool … Read the target file first" (main agent authors inline today). Step 4 = "spawn the cold-read sub-agent via the `Agent` tool", `subagent_type: general-purpose`, prompt = full content of `design-review.md` (line 448-450). Step 6 = "Each iteration runs in this order until either the budget is exhausted or no findings remain"; "Budget exhausted with blockers remaining … present findings + diff to the user" (line 573-575). Iteration budget default 3 (Skill inputs, line 108).
- **Verdict**: CONFIRMED
- **Detail**: The step numbers and roles match the rework's assumptions exactly. Today's Step 1 is inline authoring, Step 4 is a single `general-purpose` cold-read spawn of `design-review.md`, Step 6 is the bounded iterate with user-escalation on exhaustion — precisely the three steps the track reworks (S5 exit-on-exhaustion preserved).

#### Premise: `design-review.md` carries the § Prose AI-tell additions block, the absorption cross-check, the comprehension questions, and the structural findings the de-warm splits (D5/D8/D9)
- **Track claim**: track-1.md `## Context and Orientation` — "`design-review.md` is the single multi-axis reviewer: it runs the comprehension questions, the structural findings, the absorption cross-check (which makes it read the research log), and a § Prose AI-tell additions block."
- **Search performed**: Read `.claude/workflow/prompts/design-review.md` in full.
- **Code location**: `.claude/workflow/prompts/design-review.md` — §Prose AI-tell additions (line 186), absorption-completeness cross-check (lines 54-59, 253-284, 479-497 injection in edit-design), §Comprehension questions (line 326), §Structural findings (line 355), §Human-reader cold-read additions (line 169).
- **Actual behavior**: The reviewer carries all four axes. The Prose AI-tell block scans over-dense/too-terse/hard-to-read (line 195). The absorption cross-check is owned by the `phase1-creation` invocation and reads `research_log_path` (lines 54-59, 124-128, 279-284). Comprehension = seven questions (lines 336-353). Structural findings = TL;DR/References footer/length/Mechanics-link/Core Concepts (lines 366-372). Human-reader additions = audience-fit/glossary/why-before-what/navigability/explanatory-register (lines 175-179).
- **Verdict**: CONFIRMED
- **Detail**: Every axis the de-warm proposes to split out (prose → auditor; absorption → warm per-round agent; whole-doc human-reader split per D8) exists in the live file. The split is feasible: the absorption cross-check is already a self-contained block (lines 253-284) that can be lifted into a separate warm spawn; the Prose AI-tell block (lines 186-224) is self-contained and bound to creation-time prose (line 219). The Human-reader five-check split (D8) maps onto the five §Human-reader rules (lines 175-179) plus navigability — exactly the items D8 routes between the sliced auditor and the whole-doc reviewer.

#### Premise: the canonical S2 statement lives in `research.md` §"Read-scope discipline (S2)" with the quoted "exactly two places" wording
- **Track claim**: track-1.md `## Context and Orientation` (line 193-197) and D18 (line 148) — S2 "reads 'the log is read for decision content in exactly two places: at Step 4a/4b artifact authoring … and by the Phase-2 consistency review', naming the author or the cold-read reviewer as the authoring reader."
- **Search performed**: grep `Read-scope discipline (S2)` across research.md + design-document-rules.md; Read research.md lines 116-127.
- **Code location**: `.claude/workflow/research.md` lines 116-119.
- **Actual behavior**: *"**Read-scope discipline (S2).** The log → carrier flow is strictly one-way: the log is read for decision content in exactly two places: at Step 4a/4b artifact authoring (to seed the carriers) and by the Phase-2 consistency review (as a cross-check)."*
- **Verdict**: PARTIAL
- **Detail**: The "exactly two places … Step 4a/4b artifact authoring … and by the Phase-2 consistency review" wording is verbatim-accurate. But the live S2 names the **read site** ("(to seed the carriers)"), not the reader **role**. There is no "the author or the cold-read reviewer" naming in the source. The track's deliverable to extend S2 to name the absorption agent is therefore an addition of reader-naming, not an edit of an existing reader name — see finding T1.

#### Premise: `design-document-rules.md` restates the S2 rule
- **Track claim**: track-1.md line 196-197 / D18 — "`design-document-rules.md` restates the same rule."
- **Search performed**: grep `S2` in design-document-rules.md; Read lines 95-109.
- **Code location**: `.claude/workflow/design-document-rules.md` lines 102-104.
- **Actual behavior**: *"(S2: the log is read for decision content only at Step 4a/4b authoring and the Phase-2 consistency cross-check)."*
- **Verdict**: CONFIRMED
- **Detail**: The restatement exists and is site-named (Step 4a/4b authoring + Phase-2 cross-check), consistent with research.md. D18's deliverable correctly targets both files. (Same reader-vs-site nuance as T1 applies, but the deliverable and target-file set are accurate.)

#### Premise: `conventions.md` carries only descriptive read-scope cross-refs with no `S2` label (D18 / CR3)
- **Track claim**: track-1.md line 197-198 / D18 line 148 — "`conventions.md` carries no `S2` label and only descriptive cross-refs to 'the two sanctioned read points', so the deliverable … leaves the conventions.md cross-refs alone (CR3)."
- **Search performed**: grep `\bS2\b` and `sanctioned read` in conventions.md; Read lines 168-177.
- **Code location**: `.claude/workflow/conventions.md` line 86 ("the two sanctioned read points only") and lines 173-176 ("the two sanctioned read points (Step 4a/4b authoring, the Phase-2 consistency cross-check)").
- **Actual behavior**: Two cross-refs, both descriptive and site-named. Zero matches for a bare `S2` token in conventions.md.
- **Verdict**: CONFIRMED
- **Detail**: Exactly as D18/CR3 claims — descriptive site-named cross-refs, no `S2` label, no reader enumeration. Leaving them untouched is correct: neither names a reader set, so the changed-reader-set does not make either inaccurate. The track's `## Interfaces and Dependencies` conventions.md note (lines 342-345, "likely untouched") is sound.

#### Premise: the `Agent` tool / agent-definition mechanism supports the `tools:` allow-list and `model:` field (D13/D14)
- **Track claim**: track-1.md `## Context and Orientation` — "none currently carries a `tools:` allow-list, though the `Agent` tool supports one (the lever D13/D14 add)"; D7 — "`model: sonnet`."
- **Search performed**: `grep -lE '^tools:' .claude/agents/*.md` (zero matches); `grep -lE '^model:' .claude/agents/*.md` (20/20); `grep '^model:' .claude/agents/*.md | grep -i sonnet` (zero); Read sample frontmatter.
- **Code location**: `.claude/agents/*.md` — 20 files, all with `name`/`description`/`model` frontmatter (e.g. review-workflow-consistency.md lines 1-4: `model: opus`).
- **Actual behavior**: Every agent def carries `model:` (all `opus`); none carries `tools:`. The frontmatter parser accepts the keys used today.
- **Verdict**: CONFIRMED
- **Detail**: The "no agent def carries `tools:` today" claim is exactly right; `model:` is proven. `model: sonnet` and `tools:` have no in-repo precedent (no committed def uses either value) — see suggestion T2. The mechanism (Agent-tool frontmatter) is the documented per-spawn config surface, and the project auto-memory tracks `tools:` allow-lists as the YTDB-1094 lever, so the capability is real even without a committed example.

#### Premise: `readability-feedback/SKILL.md` encodes the range-sliced enumerate-every-finding contract the auditor reuses (D4)
- **Track claim**: track-1.md `## Context and Orientation` / D4 — "`readability-feedback/SKILL.md` already encodes the audit contract the auditor reuses (range-sliced fan-out, enumerate-every-finding)."
- **Search performed**: grep `range-slic|enumerate|~200|fan-out|slice` in readability-feedback/SKILL.md; Read lines 25-69.
- **Code location**: `.claude/skills/readability-feedback/SKILL.md` lines 32-34.
- **Actual behavior**: Step 2 "Partition. Split the doc into ~200-line ranges on `##` / `# Part` boundaries … Cap at ~6 sub-agents." Step 3 "Fan out the audit. Launch one `general-purpose` sub-agent per range, in parallel … Each agent reads `house-style.md` in full, audits only its range, and classifies every obscure passage as `CAUGHT by § <section>` or `GAP`."
- **Verdict**: CONFIRMED
- **Detail**: The range-sliced (~200-line, cap ~6) parallel fan-out and the per-slice enumerate-every-passage obligation both exist. D4's "reuses the existing `readability-feedback` audit contract: range-sliced fan-out, each slice obligated to enumerate findings" is accurate. The auditor's `Read`+`Grep` allow-list is consistent — the contract needs no Write/Edit/PSI (it judges text it is handed). Note: the existing contract's per-range agent reads house-style **in full** (line 34), while D13's cost lever and design-review.md's reading rules (line 324) prescribe targeted `§`-section reads; this is a deliberate optimization the auditor adopts, not a contradiction with the reused contract's shape.

#### Premise: the four agent-definition tool allow-lists are internally consistent with each role's job
- **Track claim**: track-1.md acceptance (line 307-308) and Plan-of-Work step 1 (238-242) — "auditor and absorption: `Read`, `Grep`; comprehension: `Read`; author: `Read`, `Write`, `Edit`, `Bash`, PSI."
- **Search performed**: cross-checked each role's job against the design (author writes design.md + reads code via PSI/Bash; auditor/absorption/comprehension are read-only) against design.md §"The code-grounded author" (line 220), §"The cold readability auditor" (line 265), §"The dual-clean inner loop" (line 316), §"Restructuring the comprehension and structural review" (line 373).
- **Code location**: track-1.md lines 238-242, 307-308; design.md DRs D3/D4/D7.
- **Actual behavior**: Author is "the only writer" (D3 risk note, track line 58; design.md §code-grounded author) → needs Write/Edit (apply draft), Bash + mcp-steroid PSI (read code, run `git log` for stamps). Auditor judges handed text → Read + Grep (D4, no Write/Edit/PSI). Absorption reads log + draft, two-way set matching → Read + Grep (D7). Comprehension reads doc alone → Read.
- **Verdict**: CONFIRMED
- **Detail**: Each allow-list matches its role's job. The author's list is the only one with write/execute tools, consistent with "author is the sole writer; the other three roles are read-only" (track line 226, mermaid). D3's risk note ("if its allow-list omits a tool it needs (PSI, Bash), it fails mid-task") correctly flags the author as the failure-sensitive role. One micro-observation, not a finding: the author runs the `phase1-creation` stamp idiom (`git log -1 …` via Bash, edit-design Step 1 lines 222-225) and prepends the stamp via Edit — both covered by the Bash + Edit grants, so the stamp path is feasible under the allow-list. If the orchestrator (not the author) keeps owning the stamp prepend, the author still needs Bash for PSI-adjacent reads; either way the list suffices.

#### Premise: the plan Component Map matches what each orchestrator spawns and Track 1's boundary is correct
- **Track claim**: plan Component Map (lines 37-46) and track-1.md `## Interfaces and Dependencies` — Track 1 = four inner-loop/gate roles + `edit-design` wiring + S2 read-scope; Track 2 = create-plan Step 4b + create-final-design + fidelity check + 4a/4b collapse.
- **Search performed**: Read implementation-plan.md in full; grep `D11|D15|Step 4b` in track-2.md.
- **Code location**: implementation-plan.md lines 12-49; track-2.md lines 5, 44-49.
- **Actual behavior**: Component Map roles AU/RA/AB/CR wired from `edit-design` under "Track 1"; FC (fidelity check) + AU/RA/AB wired from `create-plan`/`create-final-design` under "Track 2". track-2.md carries D11(create-plan facet) line 44 and the 4a/4b collapse (D15) — design.md §"Track authoring in create-plan Step 4b" line 49 ref. design.md DR target sections all exist (Core Concepts line 43; the seven mechanism sections lines 220-664).
- **Verdict**: CONFIRMED
- **Detail**: The boundary is clean and non-overlapping. D9 (track line 97-102) correctly states "this track for the design surfaces; Track 2 for the track surface (see Track 2 D11)"; D11(edit-design facet) (track line 104-109) correctly states "The create-plan Step 4b facet … in Track 2." Every cross-facet DR (D9/D11) is homed in exactly one track with a pointer to the other. Track-1 sizing: plan says "~9 files", track sizing justification (lines 364-372) says "under the ~12-file floor" with a written dependency-boundary rationale — consistent, and the written justification satisfies the soft-floor escape clause.

#### Edge case: the de-warmed comprehension reviewer loses the freeze-order (S3) gate's own need, but the gate must stay on the loop
- **Trigger**: a load-bearing decision surfaces while authoring; the de-warmed comprehension reviewer reads no log, so it no longer needs the S3 freeze-order gate for its own sake — does dropping the log read strand the S3 invariant?
- **Code path trace**:
  1. S3 gate today blocks the `phase1-creation` cold-read while a log-adversarial entry is open — `edit-design/SKILL.md` §Step 4 lines 414-433.
  2. The gate's purpose (research.md §"Read-scope discipline (S2)" line 113, freeze-order discussion) is that a draft cannot reach comprehension review while a decision is still being challenged on the log.
  3. After de-warm: the author (reads log) and the absorption check (reads log) are the log-reading roles on the loop (track D5 risk note line 72, D7 risk note line 86); the auditor and the de-warmed comprehension reviewer read no log (S1, track line 380).
- **Outcome**: The track correctly keeps the S3 freeze-order gate on the loop because the author and absorption check read the log (track Plan-of-Work step 3 line 261-263, D5 risk note line 72: "the gate stays on the loop because the author and the absorption check read the log (S3)"). The gate no longer guards the comprehension reviewer for its own log read, but it guards the loop's log-reading roles — the invariant is preserved by repointing, not stranded.
- **Track coverage**: yes — D5 risk note and Plan-of-Work step 3 both state the gate stays on the loop; S3 invariant (track line 382) verifies it.

#### Integration: `edit-design` is the orchestrator both design-creation kinds route through
- **Plan claim**: track-1.md `## Purpose / Big Picture` (line 18-19) and D11 — "The track wires this loop into `edit-design`, the orchestrator both design-creation kinds already route through … its existing callers inherit the loop with no changes of their own."
- **Actual entry point**: `.claude/skills/edit-design/SKILL.md` — `phase1-creation` and `phase4-creation` kinds (Skill inputs line 106, mode table lines 126/136, Step 1 phase1/phase4 branches lines 174/243).
- **Caller analysis**: edit-design is invoked by `/create-plan`'s planning-transition (seeds design.md, line 197-202) and by `create-final-design.md` (phase4-creation, line 246-248). Both route design authoring through this one skill. The track changes the work *inside* Steps 1/4/6, so callers that pass the same inputs inherit the new loop.
- **Breaking change risk**: Low for Track 1's scope. The track explicitly scopes the `edit-design` rework to design surfaces (phase1-creation) and leaves phase4-creation behavior for Track 2 (the fidelity-check swap). The by-reference contract (track lines 281-285) keeps author-spawn returns thin, preserving the orchestrator's bounded context — load-bearing for Track 2's D15 collapse.
- **Verdict**: MATCHES
- **Detail**: edit-design is correctly identified as the single design-authoring orchestrator. The track's claim that "existing callers inherit the loop with no changes of their own" holds for the phase1-creation path. Note the phase4-creation path (create-final-design) is Track 2's surface — Track 1 reworking only Steps 1/4/6 for the design-creation path does not break the phase4 path, which Track 2 then adapts (the fidelity-check second-check swap).

#### Integration: the absorption cross-check moves from the comprehension reviewer to a separate warm per-round agent (D6/D7)
- **Plan claim**: track-1.md D6/D7 and Plan-of-Work step 3 — absorption becomes "a co-equal per-round check" run as "a small warm agent that reads the research log and the draft," separate from the cold auditor (S1).
- **Actual entry point**: today's absorption cross-check is inline in `design-review.md` (lines 253-284, owned by the `phase1-creation` invocation via the injected `research_log_path`, edit-design Step 4 lines 479-497).
- **Caller analysis**: Today only the `phase1-creation` cold-read runs the absorption check, and it is the **same** spawn that runs comprehension — which is exactly why the comprehension verdict comes from a no-longer-cold reader (track D5 line 71). Moving absorption to a separate warm spawn de-couples the two.
- **Breaking change risk**: Low. The absorption-completeness criterion is a self-contained block (design-review.md lines 253-284) with a defined two-way contract (every load-bearing log decision → seed D-record, and no invented record). Lifting it into a standalone warm agent that reads log + draft preserves the contract; the cold auditor never gains a log read (S1). The de-warmed design-review.md drops the `research_log_path` injection and the absorption block.
- **Verdict**: MATCHES
- **Detail**: The split is feasible and the contract is preserved. D6's per-round justification (the cross-slice-drop failure under range-slicing, track lines 76-79) is sound given the auditor is range-sliced (readability-feedback ~200-line partition confirmed). D7's `model: sonnet` choice for the absorption agent is a cost lever consistent with "two-way set matching with light semantic-equivalence judgment" — feasible (model field proven), first non-opus agent-def model in repo (see T2).
