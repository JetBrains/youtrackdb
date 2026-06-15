<!-- MANIFEST
role: reviewer-technical
phase: 3A
track: "Track 1: Make the research log agent-internal during Phase 0"
iteration: 1
verdict: PASS
findings: 1
index:
  - id: T1
    sev: suggestion
    anchor: "#t1-suggestion"
    loc: "track-1.md D1 Risks/Caveats (the \"first branch to exercise §1.7(l)\" sentence)"
    cert: "Premise: §1.7(l) fires on both (b) and (k) markers; the §(l) \"first to exercise\" sentence is scoped to the opt-out path"
    basis: "conventions.md:1307-1311 (l fires on either marker); conventions.md:1320-1325 (first-to-exercise scoped to opt-out branch)"
evidence_base:
  premises: 10
  edge_cases: 0
  integrations: 1
  confirmed: 11
  non_confirmed: 0
-->

## Findings

### T1 [suggestion]
**Certificate**: Premise — §1.7(l) marker scope vs. the "first to exercise (l)" framing
**Location**: `track-1.md` → `## Decision Log` → D1 → **Risks/Caveats**, the sentence "This is the first branch to exercise §1.7(l) end-to-end (its own note flags the bootstrap caveat)."
**Issue**: The claim is defensible but the cross-reference is imprecise. `conventions.md:1307-1311` (§(l)) fires the Phase-3A criteria-switch on **either** the (b) workflow-modifying marker **or** the (k) opt-out marker, so this branch — which carries the (b) marker — does legitimately exercise the (l) criteria re-point (this very technical review is that re-point in action). But the §(l) "first to exercise" sentence at `conventions.md:1320-1325` is scoped specifically to a future **opt-out (k)** branch ("A future opt-out branch's review trio is the first to exercise (l) end-to-end"). So D1's bare "first branch to exercise §1.7(l)" reads as if it contradicts the conventions text, when the two are actually compatible once the marker path is named: this branch is first to exercise (l) via the **(b)** path; the conventions sentence reserves "first via the **(k)** opt-out path" for a later branch. The note drives no planned edit (it is a Risks/Caveats annotation), so this is cosmetic, not load-bearing.
**Proposed fix**: Optional. Tighten the D1 caveat to "first branch to exercise the §1.7(l) criteria re-point at all (via the (b) workflow-modifying trigger); the conventions §(l) note reserves 'first via the (k) opt-out path' for a later branch." Drop entirely if the decomposer prefers to keep D1 terse — no correctness impact.

## Evidence base

#### Premise: research.md §Rules "Record decisions" bullet at develop `:183`
- **Track claim**: Track `## Context and Orientation` quotes the §Rules "Record decisions in the research log" bullet at develop `:183` as a leak-inviting passage ("acknowledge it clearly … append it to the log's `## Decision Log` with its `**Why:**` and `**Alternatives rejected:**` fields").
- **Search performed**: `sed -n '183p'` + Read of `.claude/workflow/research.md` lines 172-190 (prose-only track; grep+Read per spawn instruction, no PSI).
- **Code location**: `.claude/workflow/research.md:183-187`
- **Actual behavior**: Line 183 is `- **Record decisions in the research log.** When the user makes a`; the bullet body (`:184-187`) reads "…acknowledge it clearly and append it to the log's `## Decision Log` with its `**Why:**` and `**Alternatives rejected:**` fields. The log, not conversation memory, is what carries decisions across a `/clear` and into planning." Quote and line number match exactly.
- **Verdict**: CONFIRMED
- **Detail**: Edit target (§Rules) and the "keep the /clear point" instruction in Plan-of-Work 1b are both grounded in the live text.

#### Premise: research.md §Transition step 1 at develop `:151`
- **Track claim**: §Transition to Phase 1 step 1 at develop `:151` reads "The agent confirms the research log captures the key findings…" and can turn into asking the user to review/reference the log.
- **Search performed**: `sed -n '151p'` + Read of lines 146-171.
- **Code location**: `.claude/workflow/research.md:151`
- **Actual behavior**: Line 151 is `1. The agent confirms the research log captures the key findings and`. Continuation (`:152-155`) describes the durable seed. Quote and line number match.
- **Verdict**: CONFIRMED
- **Detail**: Plan-of-Work 1d's reword target verified.

#### Premise: research.md §How it works step 4 append bullet at develop `:138`
- **Track claim**: §How it works step 4 at develop `:138` carries "Appends decisions, surprises, and open questions to the research log" — the natural cross-reference site.
- **Search performed**: `sed -n '138p'` + Read of lines 125-145.
- **Code location**: `.claude/workflow/research.md:138-139`
- **Actual behavior**: Line 138 is `   - Appends decisions, surprises, and open questions to the research log`; `:139` adds "as they settle (§The research log)". Quote and line number match.
- **Verdict**: CONFIRMED
- **Detail**: Plan-of-Work 1c append-target verified.

#### Premise: create-plan/SKILL.md Step 3 research-mode append bullet at develop `:270-274`
- **Track claim**: `create-plan/SKILL.md` Step 3 (Phase-0 research-mode list) at develop `:270-274` carries the "Append decisions, surprises, and open questions to the research log" bullet; it is agent-facing (says what to write *in* the log), so it does not narrate to the user.
- **Search performed**: `sed -n '270,274p'` + Read of lines 262-278.
- **Code location**: `.claude/skills/create-plan/SKILL.md:270-274`
- **Actual behavior**: `:270` is `- **Append decisions, surprises, and open questions to the research log**`; the body (`:271-274`) describes ISO-timestamp + `[ctx=]` tag + `**Why:**`/`**Alternatives rejected:**` fields. Range and content match. The bullet is plainly agent-facing (it is in the "In this mode:" action list of what the agent does in the log).
- **Verdict**: CONFIRMED
- **Detail**: Plan-of-Work 2a reword target verified; the "agent-facing, not user-narration" characterization in `## Context and Orientation` is accurate.

#### Premise: create-plan/SKILL.md Step 2 seed-the-log bullet at develop `:237`
- **Track claim**: Step 2 (`:237`, seed the log) is agent-facing.
- **Search performed**: `sed -n '237p'` + Read of lines 228-260.
- **Code location**: `.claude/skills/create-plan/SKILL.md:237`
- **Actual behavior**: `:237` is `Once the user provides the aim, write the **research log's `## Initial`. Step 2 describes seeding `## Initial request` and the six sections — agent-facing log-authoring instruction.
- **Verdict**: CONFIRMED
- **Detail**: Plan-of-Work 2b's "optional light cross-reference … keep minimal; agent-facing file" target verified.

#### Premise: D1 §1.7(k) consumer-class citation at conventions.md:1231,1243
- **Track claim**: D1 cites `conventions.md:1231,1243` for "§1.7(k) criterion (2) is a file-level consumer-class test, explicitly 'consumer class, not author intent'", concluding `create-plan/SKILL.md` (read as the `/create-plan` orchestration procedure) is an execution-procedure file that must stage.
- **Search performed**: `sed -n '1228,1246p'` + Read of §(k) lines 1204-1278.
- **Code location**: `.claude/workflow/conventions.md:1231` (heading "Opt-out criteria — consumer class, not author intent") and `:1243` ("Both criteria are about what consumes the edited file, not what the planner meant to do. A plan that satisfies (1) but edits an execution-procedure file fails (2) and must stage that file.")
- **Actual behavior**: Both cited lines say what D1 says. Criterion 2 (`:1237-1241`) explicitly names "the step-implementation orchestration loop" and "the migrate replay" as execution-procedure files that "stay staged even on an otherwise-qualifying plan." A `/create-plan` SKILL.md read as the orchestration procedure falls in the same execution-procedure class.
- **Verdict**: CONFIRMED
- **Detail**: D1's rationale for choosing §1.7 staging over the §1.7(k) opt-out is correctly grounded. The conclusion that editing any execution-procedure file makes the opt-out unavailable (not merely weaker) follows from the marker-mutual-exclusion premise (next certificate).

#### Premise: §1.7(b) and §1.7(k) markers mutually exclusive on one plan
- **Track claim**: D1 — "the §1.7(b) and §1.7(k) markers are mutually exclusive per plan."
- **Search performed**: Read of §(k) lines 1265-1269.
- **Code location**: `.claude/workflow/conventions.md:1265-1269`
- **Actual behavior**: "The two markers are mutually exclusive on one plan. A plan carries the workflow-modifying marker (it stages) or the opt-out marker (it edits live), never both…"
- **Verdict**: CONFIRMED
- **Detail**: The premise D1's logic chain rests on (edit one execution-procedure file → cannot take opt-out → must stage everything) is sound given mutual exclusion.

#### Premise: §1.7 sub-clauses (b)(d)(e)(f)(g)(k)(l) all exist
- **Track claim**: The track and plan cite §1.7(b), (d), (e), (f), (g), (k), (l) across `### Constraints`, `## Context and Orientation` staging note, Plan-of-Work, and `## Interfaces and Dependencies` Activation.
- **Search performed**: `awk` over conventions.md:851-1330 for `### (x)` headings; Read of (a)-(l) heading list.
- **Code location**: `.claude/workflow/conventions.md` — (a)`:878`, (b)`:910`, (c), (d), (e), (f), (g), (h), (i), (j), (k)`:1204`, (l)`:1301`. All present.
- **Actual behavior**: Every cited sub-clause resolves to an existing `### (x)` section heading under §1.7.
- **Verdict**: CONFIRMED
- **Detail**: No dangling §1.7 sub-clause reference in the track.

#### Premise: §1.7(l) re-points Phase-3A technical review onto the five prose lenses
- **Track claim**: `## Interfaces and Dependencies` Activation — the §1.7(b) marker "fires the §1.7(l) Phase-3A review-criteria re-point onto the prose lenses (rule coherence, instruction completeness, prompt-design soundness, context-budget impact, breakage of dependent prompts/agents)."
- **Search performed**: Read of §(l) lines 1301-1325.
- **Code location**: `.claude/workflow/conventions.md:1304-1311`
- **Actual behavior**: §(l) names the three Phase-3A criteria-switch blocks (technical-review.md, risk-review.md, adversarial-review.md), states they fire on **either** the (b) or (k) marker, and re-point "onto the prose criteria (path/anchor reference checks plus the five prose lenses)." The technical-review.md "Workflow-machinery criteria" block (lines 113-116, read in full) enumerates the five lenses verbatim: rule coherence and non-contradiction, instruction completeness, prompt-design soundness, context-budget impact, breakage of dependent prompts or agents.
- **Verdict**: CONFIRMED
- **Detail**: The spawn's criteria re-point and the track's Activation claim are both grounded in the live machinery. This review applied the five prose lenses, not the Java/WAL/crash lenses, per §(l).

#### Premise: §1.7(a) staged-subtree path layout matches the track's staged paths
- **Track claim**: `## Interfaces and Dependencies` lists in-scope staged files at `_workflow/staged-workflow/.claude/workflow/research.md` and `_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md`; the staging note cites §1.7(e) copy-then-edit-on-first-touch and §1.7(g)/I6 (live stays at develop).
- **Search performed**: Read of §(a) lines 878-908 and §(g) lines 249-272.
- **Code location**: `.claude/workflow/conventions.md:885-887` (path layout) and `:251-268` (I6 invariant).
- **Actual behavior**: §(a) fixes the two-level prefix `docs/adr/<dir-name>/_workflow/staged-workflow/.claude/workflow/...` and `.../skills/...`, mirroring the live relative path byte-for-byte — exactly the track's in-scope paths. §(g) I6: "Promotion at Phase 4 is the only intra-branch authoring transition" → live `research.md`/`SKILL.md` legitimately stay at develop for the branch. No staged subtree exists yet (verified `ls`: directory absent), so all reads this review performed resolved to the live develop-state file — correct, since staged edits are authored in Phase B.
- **Verdict**: CONFIRMED
- **Detail**: Staging routing, the I6 "live stays at develop" claim, and the "no staged subtree at Phase A" precondition all hold.

#### Integration: dependent prompts/agents that derive from the research log
- **Plan claim**: `## Interfaces and Dependencies` Out-of-scope — "a grep over `.claude/{workflow,skills,agents}` confirmed no third Phase-0 surface narrates the log to the user (the other hits are Phase-1 read-the-log derivation references and review bookkeeping)." Invariant: "do not weaken the S2 read-scope discipline." Implicit integration risk: does the new user-facing opacity rule break any consumer that reads the log?
- **Actual entry point**: `grep -rln 'research-log\|research log'` over `.claude/{workflow,skills,agents}` returns 12 files: research.md, conventions-execution.md, design-document-rules.md, workflow.md, create-plan/SKILL.md, prompts/create-final-design.md, conventions.md, track-review.md, planning.md, prompts/design-review.md, prompts/adversarial-review.md, edit-design/SKILL.md.
- **Caller analysis**: A targeted grep for user-facing log-structure narration outside research.md/create-plan (`recorded as D|logged as D|review the log|reference the log`) returned **empty** — confirming no third Phase-0 surface narrates the log to the user. Every other hit is a Phase-1+ derivation reference (the log is read for decision *content* at Step 4a/4b authoring and the Phase-2 consistency cross-check — the S2 two-site flow) or a verdict/status read of `## Adversarial gate record`. The new opacity rule governs the **log→user narration** axis during Phase 0; the S2 discipline governs the **log→artifact internal read** axis. These are orthogonal: adding the opacity rule does not touch, weaken, or contradict S2, and breaks none of the 12 consumers — they all run in Phase 1+ and read content/status internally, never narrating log structure to the user. The D3 carve-outs are real: (1) §Transition plain-language findings summary (`research.md:149-155`, verified) and (2) the create-plan Step-4 verdict recital + tier proposal ("Propose the tier and the matched categories to the user and wait for confirmation", `create-plan/SKILL.md:323-330`, verified) — both surface findings/blockers/tier, not log structure, so the opacity rule's explicit carve-out leaves them un-muzzled.
- **Breaking change risk**: None. No dependent prompt/agent breaks. The "no new ##/### headings → TOC/annotations unchanged" invariant holds: all `research.md` edit targets (§Rules `:172`, §How it works `:125`, §Transition `:146`, §The research log `:49`) are existing headings; edits are in-place rewords + bullet additions, so `workflow-reindex.py --check` (tool exists and supports `--check`, verified at `.claude/scripts/workflow-reindex.py`) has nothing new to index. The `### Adversarial review…` at `research.md:99` is inside a code fence (a heading-shape template), not a TOC row, consistent with the no-new-heading reasoning.
- **Verdict**: MATCHES
