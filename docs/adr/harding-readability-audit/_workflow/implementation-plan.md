<!-- workflow-sha: 1065c173addca97b35fda8af611eb1e656e3ada2 -->
# Harden readability-auditor slicing and convergence

## Design Document
[design.md](design.md)

## Component Map

The change touches one control-flow protocol — the dual-clean review-iteration
loop — and the files that describe it. The orchestrator and the cold auditor are
the runtime components; the six files are where the rules live.

```mermaid
flowchart TD
    O["Dual-clean loop orchestrator<br/>(partition + self-check + settled-state filter)"]
    A["Cold readability-auditor<br/>(reads slice + anchors; whole-doc guard)"]
    SS["Settled-state<br/>(orchestrator-side, section-keyed, anchor-folded hash)"]
    ED["edit-design/SKILL.md<br/>Step 4 partition + Step 6 convergence<br/>(operative homes + relocation)"]
    RA["readability-auditor.md<br/>(hard slicing rule + agent guard + 2 params)"]
    CE["conventions-execution.md §2.5<br/>(relocated review-file home)"]
    CP["create-plan/SKILL.md Step 4b item 9<br/>(track-path cross-refs)"]
    DDR["design-document-rules.md<br/>(slicing sync ref)"]
    RF["readability-feedback/SKILL.md<br/>(canonical-partition ref)"]

    O -->|spawns one per slice| A
    O -->|maintains| SS
    ED -.defines.-> O
    RA -.defines.-> A
    CE -.defines.-> SS
    CP -.cross-references.-> ED
    DDR -.cross-references.-> ED
    RF -.cross-references.-> ED
```

- **edit-design/SKILL.md** — the operative home: Step 4 carries the partition
  algorithm and the relocated params home; Step 6 carries the canonical
  convergence-mechanism statement and the relocated resume glob.
- **readability-auditor.md** — the agent: "range-sliced" becomes a hard
  requirement plus the whole-doc guard, fed by two new params (`slice_count`,
  `total_lines`).
- **conventions-execution.md §2.5** — generalizes the third-scope review-file
  home to cover the Phase-1 authoring loop (the relocation target).
- **create-plan / design-document-rules / readability-feedback** — consumers
  that cross-reference the canonical homes above; no rule is duplicated.

## Checklist
- [x] Track 1: Harden readability-auditor slicing and convergence
  > Make the design-path auditor fan-out a deterministic orchestrator
  > obligation with an agent-side collapse guard, give the dual-clean loop
  > orchestrator-side section-keyed settled-state so it stops re-flagging
  > settled prose while the auditor stays fully cold, and relocate the
  > Phase-1 authoring-loop files from `plan/` to `_workflow/reviews/`. All
  > edits route through §1.7 full staging.
  >
  > **Track episode:** Hardened the dual-clean readability-auditor loop (deterministic slice partition + agent-side collapse guard + section-keyed settled-state convergence + file relocation), staged under §1.7. — see `plan/track-1.md` `## Episodes` § Track completion. (1 step, 0 failed)
  >
  > **Track file:** `plan/track-1.md`
