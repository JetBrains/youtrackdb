<!-- MANIFEST
dimension: workflow-consistency
iteration: 1
findings: 1
severity_breakdown: { blocker: 0, should-fix: 1, suggestion: 0 }
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WC1, sev: should-fix, anchor: "#wc1-should-fix--svg-figure-naming-convention-drift", loc: "docs/workflow-book/README.md:43; workflow-book-builder/PIPELINE.md:71; docs/workflow-book/assets/diagrams/.gitkeep:1 vs workflow-book-builder/DIAGRAMS.md:19-21", cert: n/a, basis: read }
-->

## Findings

### WC1 [should-fix] — SVG figure-naming convention drift (`fig-N.svg` vs `fig-<name>.svg`)

- **Axis:** mermaid vs prose / cross-file rule restatement (figure-naming convention restated inconsistently across the run-target docs and the authoritative figure-set spec).
- **Cost:** a producer run following PIPELINE/README expects numeric `fig-N.svg` output names, but the authoritative figure set and the render script emit descriptive stems; the run can mis-name committed assets or fail to find the figures the cross-reference matrix indexes.
- **Location:** `docs/workflow-book/README.md:43`, `workflow-book-builder/PIPELINE.md:71`, `docs/workflow-book/assets/diagrams/.gitkeep:1` (all say `fig-N.svg`) versus `workflow-book-builder/DIAGRAMS.md:19-21` (the enumerated set: `fig-phase-state-machine.svg`, `fig-tier-gate.svg`, `fig-track-step-episode.svg`) and `workflow-book-builder/DIAGRAMS.md:27` (`fig-*.svg`).
- **Issue:** `DIAGRAMS.md` is the authoritative figure-set spec (check f). It enumerates the three committed figures with descriptive stems (`fig-phase-state-machine`, `fig-tier-gate`, `fig-track-step-episode`) and describes the render output as `fig-*.svg`. `render-diagrams.sh` confirms the descriptive form: it derives the output as `out="${src%.d2}.svg"`, so `fig-phase-state-machine.d2` renders to `fig-phase-state-machine.svg`, never `fig-1.svg`. But `README.md:43`, `PIPELINE.md:71`, and `assets/diagrams/.gitkeep:1` all name the committed output `fig-N.svg`, implying a numeric naming scheme that the spec and the script do not produce. `DIAGRAMS.md:17` separately says the *figure numbers* (`fig-1`, `fig-2`) are "assigned at TOC time," which suggests numeric figure labels coexist with descriptive file stems — but `README.md`/`PIPELINE.md`/`.gitkeep` apply the numeric form to the committed `.svg` *file name*, which contradicts the descriptive sidecar/SVG pairs in `DIAGRAMS.md:19-21`.
- **Proposed fix:** Pick one file-naming convention and restate it identically. Either rename the descriptive stems in `DIAGRAMS.md:19-21` to numeric `fig-1.svg`/`fig-2.svg`/`fig-3.svg`, or (preferred, since the descriptive stems are self-documenting and match what the script emits) change `README.md:43`, `PIPELINE.md:71`, and `assets/diagrams/.gitkeep:1` to read `fig-<name>.svg` / `fig-*.svg` so the run-target docs agree with the authoritative `DIAGRAMS.md` figure set and the `render-diagrams.sh` output rule.

## Evidence base
