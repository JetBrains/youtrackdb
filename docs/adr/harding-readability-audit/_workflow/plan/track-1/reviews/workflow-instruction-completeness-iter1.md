<!--
MANIFEST
dimension: workflow-instruction-completeness
target: track-1 step-1 (commit 2a8167a416)
scope: delta of six staged workflow files (302-line delta); verbatim-copied remainder out of scope
verdict: PASS
blocker_count: 0
finding_count: 3
evidence_base: 3
cert_index: 3
flags: []
index:
  - id: WI1
    sev: Minor
    anchor: "#wi1-minor--never-clean-tail-blocker-vs-should-fix-only"
    loc: ".claude/skills/edit-design/SKILL.md (staged) §Step 6 lines 886-897; §canonical convergence mechanism lines 951-953"
    cert: C1
    basis: judgment
  - id: WI2
    sev: Minor
    anchor: "#wi2-minor--agent-guard-blocker-not-routed-through-settled-state-filter"
    loc: ".claude/agents/readability-auditor.md (staged) lines 31-33; .claude/skills/edit-design/SKILL.md §Step 6 lines 855-863"
    cert: C2
    basis: judgment
  - id: WI3
    sev: Minor
    anchor: "#wi3-minor--track-path-anchor-fold-omits-the-when-present-tolerance"
    loc: ".claude/skills/create-plan/SKILL.md (staged) Step 4b item 9 lines 860-867"
    cert: C3
    basis: judgment
-->

## Findings

### WI1 [Minor] — never-clean tail: blocker vs should-fix-only

- **Axis:** loop termination
- **Cost:** a never-clean tail carrying an open `blocker` (not just should-fix) has no specified exit; the loop hits budget=0 and the spec does not say which path it takes.
- **Issue:** Step 6's never-clean-tail terminal path (staged `edit-design/SKILL.md` lines 886-897) is specified for "budget exhaustion with only should-fix findings open" — it exits through the S5 user-is-the-gate path. The canonical convergence mechanism (lines 951-953) reinforces that "only prose-density should-fix findings ride the budget-plus-S5 tail," because a decision-shaped finding re-opens the S3 gate. But the agent-side whole-doc guard (D2) returns a **`blocker`** finding, and the absorption check can return a `blocker`. If such a blocker is genuinely unresolvable across the budget (e.g. a persistent wiring error the orchestrator self-check missed but the guard catches every round), the budget reaches zero with an open blocker, and the never-clean-tail clause — scoped to "only should-fix findings open" — does not name the exit. The general S5 exit ("both exit to the user on budget exhaustion," line 807) does cover it, so the LLM is not strictly stranded; the gap is that the *named* tail path silently excludes the blocker case without saying the generic S5 exit catches it.
- **Suggestion:** In the never-clean-tail sentence, add a half-clause that a never-clearing **blocker** at budget exhaustion exits through the same generic S5 budget-exhaustion path (line 807) — surfaced to the user as a non-self-correcting failure, not the apply-cheap-fixes-and-accept-residual should-fix path. One clause closes the scope gap.

### WI2 [Minor] — agent-guard blocker not routed through settled-state filter

- **Axis:** sub-agent handshake
- **Cost:** the orchestrator's per-section round decision (drop on settled-unchanged / keep on changed) does not name where the agent-side guard's `blocker` finding lands, so on a re-spawn into an already-settled section the guard blocker could be dropped by the settled-state filter.
- **Issue:** The settled-state filter (staged `edit-design/SKILL.md` lines 927-932) drops *all* findings on a settled-and-unchanged section. The agent-side whole-doc guard fires `slice_count == 1 AND total_lines > ~300` (readability-auditor.md lines 31-33) — a wiring-error `blocker`, not a prose finding about the section's content. If a guard blocker is returned on a spawn whose slice happens to map to a section the orchestrator considers settled-and-unchanged, the literal "all its findings dropped" rule would suppress the guard blocker — exactly the collapse the guard exists to surface. In practice the collapse condition (one slice over a long doc) cannot co-occur with a normal multi-section settled-state, so this is near-unreachable; but the filter rule does not carve the wiring-error blocker out of the "drop all findings" sweep, so the actor boundary is under-specified.
- **Suggestion:** Add a one-clause carve-out to the per-section round decision: a wiring-error `blocker` (the agent-side whole-doc guard, or any non-prose structural blocker) is never section-scoped and is never dropped by the settled-state filter — it surfaces regardless of the section's settled state.

### WI3 [Minor] — track-path anchor-fold omits the "when present" tolerance

- **Axis:** conditional branch coverage
- **Cost:** the track-path anchor-fold cross-reference (create-plan item 9) names two anchors unconditionally, with no complement for the case where one is absent — a `minimal`-tier plan with a single track and no Component Map, or a track skeleton lacking `## Purpose / Big Picture`, would have an undefined fold.
- **Issue:** The canonical mechanism's anchor-fold (staged `edit-design/SKILL.md` lines 933-946) is carefully conditional on the design path — `## Core Concepts` is folded **"when present"**, with the absent case explicitly declared normal and not an error. The track-path cross-reference in `create-plan` Step 4b item 9 (staged lines 860-867) names "the plan Component Map plus each track's `## Purpose / Big Picture`" as the fold set with no parallel "when present" tolerance. D5 Risks/Caveats (track file) asserts the Component Map and track skeletons are byte-stable because items 1-8 settle them before item 9 runs, which establishes byte-*stability* but not byte-*presence*: a `minimal` single-track plan may legitimately carry a thin or absent Component Map, and the cross-reference does not say what the fold does when an anchor is missing. The design-path home handles its absent-anchor case explicitly; the track-path cross-reference inherits the mechanism but not the tolerance clause.
- **Suggestion:** Add a half-sentence to the item-9 convergence cross-reference mirroring the design-path "when present" tolerance: fold in only the track-path anchors that exist (the Component Map when the plan carries one; each track's `## Purpose / Big Picture`), and an absent Component Map on a thin `minimal` plan is not an error and does not force a re-audit by itself.

## Evidence base

#### C1 — never-clean tail blocker-vs-should-fix exit (judgment, confirmed)

Traced the terminal-path branch coverage. Staged `edit-design/SKILL.md` line 807 gives the generic exit ("both exit to the user on budget exhaustion"); lines 891-893 give the never-clean-tail exit scoped to "budget exhaustion with **only should-fix findings open**"; lines 951-953 reinforce that only prose-density should-fix findings ride the tail. The guard (readability-auditor.md line 31) and the absorption check both can return `blocker`. A persistent blocker at budget=0 is covered by the generic line-807 S5 exit but is not named by the scoped tail clause — confirmed gap is a missing cross-link, not a missing exit. Downstream consumer of the exit decision: the S5 user-is-the-gate handler. Surviving finding (one-line): scoped tail clause excludes the blocker case without pointing at the generic S5 catch.

#### C2 — agent-guard blocker vs settled-state filter (judgment, confirmed)

Traced the produce→consume path for the guard blocker. Producer: readability-auditor.md lines 31-33 (`blocker` finding on collapse). Consumer: Step 5 merge (lines 781-784, "one set per slice") → Step 6 per-section round decision (lines 927-932, "settled and unchanged ... all its findings dropped"). The "all its findings dropped" rule does not exempt a non-prose wiring-error blocker. Co-occurrence of (collapse-to-one-slice) with (normal multi-section settled-state) is near-unreachable because the collapse means one whole-doc slice — confirmed near-unreachable, so Minor not Recommended. The actor-boundary under-specification survives: the filter rule sweeps the structural blocker it should never own.

#### C3 — track-path anchor-fold "when present" tolerance (judgment, confirmed)

Compared the design-path anchor-fold conditional against the track-path cross-reference. Design path (staged `edit-design/SKILL.md` lines 937-942): `## Core Concepts` folded "**when present**," absent case declared normal. Track path (staged `create-plan/SKILL.md` lines 860-867): "the plan Component Map plus each track's `## Purpose / Big Picture`" stated unconditionally. Checked D5 Risks/Caveats (track file lines 97): establishes byte-stability via items-1-8-settle-first, not byte-presence; `minimal` single-track plans are an enumerated tier (create-plan resume arms reference `tier=minimal` throughout). The complement (anchor absent) is unhandled on the track path while handled on the design path — confirmed missing-complement, scoped Minor because a `minimal` plan with no Component Map at all is uncommon and the hash simply folds fewer anchors (degrades gracefully rather than stranding).
