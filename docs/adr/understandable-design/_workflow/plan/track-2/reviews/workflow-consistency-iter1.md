<!--
MANIFEST
dimension: workflow-consistency
iter: 1
prefix: WC
high_water_mark: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WC1
    sev: Recommended
    anchor: "#wc1-recommended-mermaid-vs-prose"
    loc: "docs/adr/understandable-design/_workflow/plan/track-2.md:117-141"
    cert: n/a
    basis: "Cross-checked the track file's Context-and-Orientation diagram against the same file's Plan of Work / Interfaces sections and the staged edit-design/SKILL.md, create-final-design.md, and fidelity-check.md."
-->

## Findings

### WC1 [Recommended] mermaid vs prose — Phase 4 fidelity/author/auditor spawn ownership misattributed to create-final-design.md

- **File:** `docs/adr/understandable-design/_workflow/plan/track-2.md` (lines 117-141)
- **Axis:** mermaid vs prose
- **Cost:** the track's own orientation diagram contradicts the track's central concern-2 design decision (the kind-keyed swap lives in `edit-design`, not `create-final-design`); a reader orienting off the diagram learns the wrong spawn topology for the Phase 4 path.
- **Issue:** the `## Context and Orientation` mermaid (lines 117-134) draws `CFD["create-final-design.md (Phase 4)"]` spawning the author (`CFD -->|spawns| AU`), the readability auditor (`CFD -->|per round| RA`), and the fidelity check (`CFD -->|"per round: fidelity, not absorption"| FC`), and the two-bullet legend below it (lines 139-141) restates "**create-final-design** reuses author + auditor + the comprehension gate but swaps the second check to the new **fidelity check**." The Referent for "who spawns the Phase 4 roles" is `edit-design`, not `create-final-design`: the same track file states this repeatedly — `## Plan of Work` (lines 187-189: "the kind-keyed swap lives in `edit-design`"), `## Interfaces and Dependencies` (lines 340-341: "The kind-keyed second-check swap is not here; it lives in `edit-design`"), the Step 2 episode (line 246: `create-final-design.md` is only description-synced and threads inputs), and the staged files confirm it (`fidelity-check.md` line 3: "Spawned by edit-design on phase4-creation"; `edit-design/SKILL.md` Step 4 lines 698-718 hold the fidelity-check spawn contract; `create-final-design.md` staged delta lines 623-625: "The kind-keyed selection of fidelity-over-absorption lives in `edit-design`, not here"). `create-final-design.md` routes `phase4-creation` *through* `edit-design`, which is the actual spawner of AU/RA/FC. The diagram collapses the `CFD → edit-design → {AU, RA, FC}` indirection into a direct `CFD → {AU, RA, FC}`. (The create-plan Step 4b half — `CP` spawning AU/RA/AB — is correct, because create-plan Step 4b *is* the direct orchestrator of those spawns; only the CFD half is wrong.)
- **Suggestion:** align the diagram with the authoritative prose by inserting the `edit-design` node on the Phase 4 path — draw `CFD -->|routes phase4-creation| ED` and `ED -->|spawns| AU` / `ED -->|per round| RA` / `ED -->|"per round: fidelity, not absorption"| FC`, or, if the diagram is meant to stay at the role-reuse altitude, retitle the CFD-side edges and the legend bullet to "create-final-design (via edit-design) ..." so the spawn owner is not misstated. Note: the plan's own `## Component Map` in `implementation-plan.md` has the identical simplification (`CFD -->|Track 2| FC`) but was not touched in this track's reviewed range, so it is out of scope for this pass; flag it at Phase 4 if the diagrams are reconciled then.

## Evidence base
