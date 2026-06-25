# design-author params — tracks, Step-4b round 1 (harding-readability-audit)

## Inputs
- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- plan_dir: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- round: 1

## Track plan (settled shape — items 1-8 already decided; you write the prose)

**One track only.** The whole change is one reviewable diff (maximize-bundle
sizing rule; user-confirmed single track). The track file already exists as a
skeleton at `plan/track-1.md` with the line-1 workflow-sha stamp, the section
headers, and the seven Decision-Record headers seeded. **Fill the prose below
the stamp; do not touch line 1.**

**Track title:** Harden readability-auditor slicing and convergence (YTDB-1158).

**In-scope files this track will edit during Phase 3 (six), all routed through
§1.7 full staging — edits land under `_workflow/staged-workflow/.claude/...`,
the live `.claude/**` stays at develop-state until the Phase 4 promotion:**
1. `.claude/skills/edit-design/SKILL.md` — Step 4 = the operative partition
   algorithm (D1/D2) + params/`output_path` relocation (D6); Step 6 = the
   canonical convergence-mechanism statement (D4/D5) + the resume round-count
   glob relocation (D6).
2. `.claude/agents/readability-auditor.md` — "range-sliced" becomes a hard
   requirement + the agent-side whole-doc guard (D2), made computable by two new
   params `slice_count` / `total_lines`; a cold-read note that the settled-state
   is orchestrator-side (D3).
3. `.claude/workflow/conventions-execution.md` — §2.5 generalizes the
   third-scope review-file home to Phase-1 plan-scoped review scaffolding (D6).
4. `.claude/skills/create-plan/SKILL.md` — Step 4b item 9 cross-references the
   slicing principle (D2, per-file on the track path) and the convergence
   mechanism with the track-path params (D5); params relocation (D6).
5. `.claude/workflow/design-document-rules.md` — keep any cold-read-mechanics
   slicing statement in sync by reference (D2).
6. `.claude/skills/readability-feedback/SKILL.md` — cross-reference the
   canonical partition statement so the standalone tool and the in-loop path
   cannot drift on window size / cap (D2).

**Out of scope:** the live (unstaged) `.claude/**` files (read-only references);
adding a track-path resume round-count glob (pre-existing gap, orthogonal to D6).

**Decision Records the track owns (seven):** D1, D2, D3, D4, D5, D6, D8 — seeded
from the frozen `design.md` D-records. The skeleton already carries the seven
`#### D<n>:` headers with `**Full design**` pointers and HTML-comment guidance
naming the design section each derives from. Fill each four-bullet body
(Alternatives considered / Rationale / Risks-Caveats / Implemented-in) faithful
to the cited design section. **Do not invent a D7 track record:** D7 is the
tier/§1.7 meta-decision and lives in the phase ledger, not the track Decision
Log; surface its content only as the I6 staging invariant in
`## Invariants & Constraints` and the staging routing in `## Plan of Work`.

**Section homes to populate** (per `conventions-execution.md` §2.1 and the
skeleton's AUTHOR comments): `## Purpose / Big Picture` (BLUF + intro
paragraph), `## Context and Orientation` (codebase state + optional track-level
Mermaid), `## Plan of Work` (edit sequence + ordering + staging routing),
`## Interfaces and Dependencies` (the six in-scope files + out-of-scope), the
seven `## Decision Log` records, `## Invariants & Constraints` (I6, S1, S4, the
whole-doc floor, the deterministic-partition + verifiable-count obligations,
each with how it is verified — Phase C workflow reviewers / spec inspection,
since this is a prose change with no unit tests), and `## Validation and
Acceptance` (track-level behavioral criteria). Leave the Phase-A placeholders
(`## Concrete Steps`, `## Episodes`, `## Idempotence and Recovery`) untouched.

## Grounding note
This is a workflow-prose change: the "live codebase" you ground in is the
workflow markdown (the six files above and their current develop-state
behavior). Read them with `Read`/`Grep` — there is no Java symbol surface, so
PSI is not needed; note that in your summary.
