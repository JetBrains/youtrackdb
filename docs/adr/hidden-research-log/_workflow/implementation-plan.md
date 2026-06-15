<!-- workflow-sha: e8a35443b0a639ff87f1c4d63e2c82b2d5010393 -->
# Keep the research log opaque to the user during Phase 0

## High-level plan

**Change tier:** minimal — matched categories: none

### Constraints
This plan is workflow-modifying: it edits .claude/workflow/**, .claude/skills/**, or .claude/agents/**.

<!-- The §1.7(b) marker above activates §1.7 staging: edits land under
_workflow/staged-workflow/.claude/, live workflow stays at develop (I6),
a Phase-4 promotion commit copies staged → live after the §1.7(f) rebase.
The same marker fires the §1.7(l) Phase-3A review-criteria re-point onto the
prose lenses. The minimal stub template carries no `### Constraints`
natively; this one-line section is the hand-roll YTDB-1125 will codify. -->

## Checklist
- [ ] Track 1: Make the research log agent-internal during Phase 0
  > YTDB-1124. `research.md` tells the agent to append decisions to the
  > research log but never says the log stays out of the research
  > conversation, so the agent leaks log bookkeeping at the user ("recorded
  > as D3", section names, quoted `**Why:**`/`**Alternatives rejected:**`
  > fields). This track adds a user-facing opacity rule to `research.md`
  > §Rules, rewords the two leak-inviting passages (the "Record decisions"
  > bullet and the §Transition "confirms the log captures…" line), and
  > reflects the rule in `create-plan` SKILL.md's Phase-0 narration. The log
  > stays the agent's silent durable memory; findings reach the user as
  > plain prose.
  > **Scope:** ~2 files covering `research.md` (§Rules opacity rule + reworded §Rules/§How-it-works/§Transition passages) and `create-plan` SKILL.md (Phase-0 narration reflection)

## Plan Review
- [x] Plan review (consistency only; structural dropped under `minimal` tier per D9/D10) — passed at iteration 1

**Auto-fixed (mechanical)**: none — the consistency review and its independent gate verification both returned zero findings (CONTRACT_OK). Every current-state claim in Track 1 (the quoted `research.md` §Rules / §Transition / §How it works passages, the `create-plan/SKILL.md` Step 2/3 bullets, both D3 user-facing-recap carve-outs, and the D1 `conventions.md:1231,1243` §1.7(k) citation) verified against the live develop-state files.

**Escalated (design decisions)**: none.

## Final Artifacts
- [ ] Phase 4: Final artifacts (PR-description verdict summary; no `docs/adr/` entry — Gate 2 is the durable-ADR boundary; the §1.7(f) staged→live promotion commit runs here too, driven by the staged subtree)
