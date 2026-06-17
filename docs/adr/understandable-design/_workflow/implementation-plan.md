<!-- workflow-sha: ed3fe83cda372f371df18d63268aeb8cf6aebeb0 -->
# Two-role authoring loop for readable design docs

## Design Document
[design.md](design.md)

## Component Map
The change adds five agent roles and reuses three existing orchestrators. The
slice below is for cross-track impact assessment; per-track detail, the full
Decision Records, the invariants, and the constraints live in the track files.

```mermaid
flowchart TD
    subgraph roles["Agent roles (.claude/agents/)"]
        AU["code-grounded author"]
        RA["cold readability auditor"]
        AB["warm absorption check"]
        CR["de-warmed comprehension review"]
        FC["Phase 4 fidelity check"]
    end
    subgraph orch["Orchestrators"]
        ED["edit-design SKILL.md"]
        CP["create-plan Step 4b"]
        CFD["create-final-design.md"]
    end

    ED -->|Track 1| AU
    ED -->|Track 1| RA
    ED -->|Track 1| AB
    ED -->|Track 1| CR
    CP -->|Track 2| AU
    CP -->|Track 2| RA
    CP -->|Track 2| AB
    CFD -->|Track 2| FC
```

- **Track 1** builds the four inner-loop / gate roles (author, readability
  auditor, absorption check, de-warmed comprehension review) and wires them
  into `edit-design`. It also extends the `conventions.md` read-scope
  invariants the new log reader implies. This is the dependency root.
- **Track 2** reuses Track 1's author, auditor, and absorption roles at two
  more authoring points — `create-plan` Step 4b (track files) and
  `create-final-design.md` (Phase 4) — adds the one new role the Phase 4 path
  needs (the fidelity check), and collapses the full-tier 4a/4b session
  boundary into one `create-plan` invocation.
- The whole change is workflow-modifying (§1.7(b)): every edit stages under
  `_workflow/staged-workflow/.claude/` and the live workflow stays at develop
  state until the Phase 4 promotion.

## Checklist
- [ ] Track 1: The two-role authoring loop, wired into design creation
  > Build the core: a code-grounded author drafts `design.md` for a reader who
  > has only the finished doc, a cold readability auditor reports every passage
  > that reader cannot reconstruct, and a warm absorption check confirms the
  > draft still carries every load-bearing decision — run as a dual-clean inner
  > loop inside `edit-design`. De-warm the comprehension reviewer
  > (`design-review.md`) so its verdict is finally cold, realize all four roles
  > as agent definitions with minimal tool allow-lists, and update the
  > `conventions.md` read-scope invariants. Detailed description in
  > plan/track-1.md.
  > **Scope:** ~9 files covering 4 agent definitions, `design-review.md`
  > de-warm, `edit-design/SKILL.md` rework, and `conventions.md` S2/S3/S4
  > wording (all staged).

- [ ] Track 2: Reuse the loop at track authoring and Phase 4; collapse the 4a/4b boundary
  > Wire the Track 1 loop into `create-plan` Step 4b (so `lite` / `minimal`
  > track files get readability help) and into `create-final-design.md` Phase 4
  > (swapping the second check from absorption to a doc-against-episodes
  > fidelity check, with a PSI residual). Add the one new fidelity-check agent
  > definition, and collapse the full-tier 4a/4b session boundary into one
  > `create-plan` invocation — a staged auto-resume-contract change that depends
  > on Track 1's by-reference orchestration. Detailed description in
  > plan/track-2.md.
  > **Scope:** ~5 files covering `create-plan/SKILL.md` (Step 4b loop + Step 1c
  > re-spec), `create-final-design.md`, 1 fidelity-check agent definition, and
  > `planning.md` / `workflow.md` touch-ups (all staged).
  > **Depends on:** Track 1
