<!-- review-manifest
verdict: should-fix
findings: 5
evidence_base: 6 certificates (3 decision challenges, 1 assumption test, 2 open-question/scope challenges) grounded in the model book at docs-ytdb-internals-book/ and the live repo tool/precedent state.
index:
- id: A1, sev: should-fix, anchor: "#a1-should-fix", loc: "research-log.md D5/D6", cert: "Challenge: D5/D6 — committed-SVG/D2 diagram pipeline", basis: model-book-asset-inventory
- id: A2, sev: should-fix, anchor: "#a2-should-fix", loc: "research-log.md D6", cert: "Assumption test: D2 binary is more robust in CI/containers than mermaid", basis: installed-tool-probe
- id: A3, sev: should-fix, anchor: "#a3-should-fix", loc: "research-log.md Surprise#4 / D10", cert: "Challenge: D10 — refresh 'mirrors the workflow-drift-check gate'", basis: workflow-drift-check.md-mechanism
- id: A4, sev: suggestion, anchor: "#a4-suggestion", loc: "research-log.md D8/D10", cert: "Challenge: D8/D10 — one evolution-aware pipeline replaces refresh+cycle split", basis: model-README-production-record
- id: A5, sev: suggestion, anchor: "#a5-suggestion", loc: "research-log.md OQ3", cert: "Open-question: machinery name + Maven-module sub-question not closed", basis: research-log-OQ-vs-D2
-->

# Adversarial Review — Research Log (Phase 0 → 1 gate), iter 1

**Target:** `docs/adr/workflow-book/_workflow/research-log.md`
**Scope:** research-log (`## Decision Log` D1–D10, `## Surprises & Discoveries`, `## Open Questions`)
**matched_categories:** (none) — gate run lens-free
**Verdict:** should-fix (no blockers; 3 should-fix, 2 suggestion)

This is a `minimal`-tier, non-workflow-modifying change: a new `workflow-book-builder/`
directory of prose prompts plus a documented (mostly empty) `docs/workflow-book/`
book target. The core scope decisions (D1 builder-only, D2 prose-prompt/non-Maven,
D3 split book/machinery dirs, D4 onboarding audience, D7/D9 generated-and-living TOC)
survive challenge — they track the chosen base model (`docs-ytdb-internals-book/`)
faithfully. The findings cluster on one decision family: the diagram pipeline (D5/D6)
and the drift/evolution machinery (D8/D10/Surprise#4), where the rationale leans on
analogies that the model book and the live repo state do not fully support.

## Findings

### A1 [should-fix]
**Certificate:** Challenge: D5/D6 — committed-SVG/D2 diagram pipeline
**Target:** Decision D5 (hybrid ASCII-default + committed SVG) and D6 (D2 render DSL)
**Challenge:** The base model the user explicitly chose (`docs-ytdb-internals-book/`)
ships **zero** rendered diagram assets. All 17 chapters carry inline ```mermaid``` blocks
(17/17 chapter files contain a mermaid fence; `find` for `*.svg`/`*.png`/`*.d2`/`*.mmd`
under the model book returns nothing). The whole `youtrackdb` repo has no committed SVG
under `docs/`, no `*.d2` source, and no `render*.sh` anywhere. D5/D6 therefore introduce
a brand-new build-artifact class (sidecar `.d2` source + `render-diagrams.sh` + committed
SVG) with **no precedent in either the model or the repo**, justified by a viewer-portability
problem that the model never solved this way. The user's complaint ("mermaid support quite
limited by viewers") is real and worth addressing, but the rationale does not weigh the
chosen heavy solution against the lighter options it rejects: (a) the model's own
all-mermaid status quo, or (b) all-ASCII (which the log itself notes the `.claude/workflow`
docs already mix in, e.g. `branch-divergence-check.md`, `conventions-execution.md`, and
which the house style prefers). The diffability loss the open question flagged for
pre-rendered SVG is acknowledged in OQ2 but not re-weighed in D5's rationale.
**Evidence:** `docs-ytdb-internals-book/docs/ytdb-internals-book/chapters/*` — 17 files,
all inline mermaid, no asset files; repo-wide `find docs -name '*.svg'` and
`find . -name '*.d2'`/`-name 'render*.sh'` all empty.
**Proposed fix:** Strengthen D5/D6's rationale to (1) state explicitly that the chosen
base ships only inline mermaid and that this plan is *diverging* from the model on diagrams
(not "mirroring" it), and (2) record why committed-SVG beats the cheaper all-ASCII option
that already exists in the workflow docs and is house-style-preferred — e.g., name the
specific dense figures (phase state machine, tier gates) that ASCII genuinely cannot carry,
so the SVG class earns its build-cost. If the dense-figure count is small, consider
narrowing D5 to "ASCII for all but the named N figures" so the SVG pipeline's footprint is
bounded by an enumerated list rather than an open category.

### A2 [should-fix]
**Certificate:** Assumption test: D2 binary is more robust in CI/containers than mermaid
**Target:** Decision D6 (D2 chosen over mermaid-cli "robust in CI/containers, no
headless-browser dependency")
**Challenge:** The "more robust in CI/containers" claim is stated as settled but is
unverified against this environment, and the available evidence cuts the other way for
*this* machine. `d2` is **not installed** (`which d2` → not found). The mermaid toolchain's
core prerequisite — `node`/`npx` — **is** present (`/usr/bin/node`, `/usr/bin/npx`), while
`mmdc` itself is not. So today the mermaid path is one `npx @mermaid-js/mermaid-cli` away
from working, whereas the D2 path needs a fresh Go-binary install that nothing in the repo
provisions. The robustness argument (puppeteer/Chromium fragility) is a real and reasonable
point, but it is asserted, not demonstrated, and the operator who runs `render-diagrams.sh`
will hit a missing-binary failure on a clean checkout with no documented install step.
**Evidence:** tool probe — `which d2` empty; `which node npx` → `/usr/bin/node`,
`/usr/bin/npx`; `which mmdc` empty. No CI workflow or install script for either tool in the
repo.
**Verdict:** FRAGILE — D6 holds as a defensible preference but its load-bearing "robust"
claim rests on an install assumption the environment contradicts.
**Proposed fix:** D6 should record the install prerequisite explicitly (e.g.
`render-diagrams.sh` checks for `d2` and prints the install command, or the brief documents
the `d2` install as a one-time operator step) and soften "robust in CI/containers" to a
preference claim, since neither tool ships in the environment and the only currently-present
half-toolchain is mermaid's `node`/`npx`. Note: since D1 scopes this plan to the *builder*
(no chapters authored yet), the render path will not actually run until a later production
run — so this is rationale-hardening and an operator-doc requirement, not an execution
blocker for this plan.

### A3 [should-fix]
**Certificate:** Challenge: D10 — refresh "mirrors the workflow-drift-check gate"
**Target:** Surprise #4 and Decision D10 (drift window computed "mirroring the
workflow-drift-check gate already in the repo")
**Challenge:** Surprise #4 and D10 lean on an analogy that conflates two different
mechanisms. The repo's `workflow-drift-check.md` gate does **not** walk
`.claude/workflow/**` source for content drift. It is a session-startup gate (roles
orchestrator/planner) that detects drift in a branch's **`_workflow/**` plan artifacts**,
keyed off per-file `workflow-sha:` line-1 stamps and a `git merge-base` fold to a
`BASE_SHA`, then routes to `/migrate-workflow`. Its pathspec watches
`.claude/workflow/**` + skills + agents only as the *trigger* for whether plan artifacts
need re-stamping — it does not classify or refresh content the way the book refresh must.
The actual analogue for the book's source-tree drift is the **internals book's
`MAINTENANCE_PROMPT.md`**, which runs `git log BOOK_SHA..NEW_SHA --name-only -- <source
paths>` and triages chapters clean/sweep/review. So the log cites the wrong repo mechanism
as its model; the right one (the maintenance prompt) is the very file D8 also references.
This matters because a derived design/plan that "mirrors the workflow-drift-check gate"
would inherit the stamp/merge-base machinery, which is irrelevant to a markdown book that
has no per-file stamps and no `/migrate-workflow` equivalent.
**Evidence:** `.claude/workflow/workflow-drift-check.md` §Detection — drift computed over
the active plan's `_workflow/**` stamped artifacts via `BASE_SHA..HEAD` fold, not over
`.claude/workflow/**` content; `docs-ytdb-internals-book/.../MAINTENANCE_PROMPT.md` Phase 0
— `git log BOOK_SHA..NEW_SHA --name-only -- <source paths>`, the real source-tree-drift
analogue.
**Proposed fix:** Reword Surprise #4 and D10 to cite the **internals book
`MAINTENANCE_PROMPT.md`** as the drift model (it already does the source-tree `git log`
window the book needs), and demote the `workflow-drift-check` reference to "the repo's
*workflow-SHA stamp concept* is reused as the book's baseline-SHA, but the book's drift
*walk* follows the maintenance-prompt pattern, not the stamp-gate's plan-artifact fold."
Keep the workflow-SHA baseline reuse (that part is sound); drop the implication that the
stamp gate's detection machinery transfers.

### A4 [suggestion]
**Certificate:** Challenge: D8/D10 — one evolution-aware pipeline replaces refresh+cycle
split
**Target:** Decision D8 (full role set for evolution) and D10 (one unified pipeline;
initial production = evolution from empty baseline)
**Challenge:** D8's premise — that the model "refused new content" and required a manual
new cycle, a ceiling the user asked to remove — is only half the picture. The model's
`README.md` Production record shows the book **did** add new content (cycle 1 → cycle 2 with
three deferred chapter additions) via exactly that manual-cycle path, and it worked. The
model's deliberate two-mode split (lightweight drift refresh in `MAINTENANCE_PROMPT.md`;
heavyweight author-wave cycle for new content, run by hand) is a working design, not a
defect. D10 collapses both into one "evolution from an empty baseline" pipeline on the
rationale of "one fewer artifact to keep in sync" — but the from-scratch case (empty
baseline, TOC from scratch, every chapter through full waves) and the incremental case
(drift window, clean/sweep/rewrite triage, a few touched chapters) have genuinely different
control flow, and folding them risks a single prompt that is over-general for the common
incremental case and under-specified for the from-scratch case. The unification is
defensible (the empty-baseline framing is elegant) and survives — but the rationale
overstates the model's limitation it claims to fix.
**Evidence:** `docs-ytdb-internals-book/.../README.md` §Production record — cycle 1 + cycle
2 added new chapters via manual cycles; `MAINTENANCE_PROMPT.md` Rule 5 / Phase 5 — new
features "outside a refresh... log it and stop," i.e. an explicit, working handoff to a
cycle, not an unaddressed ceiling.
**Verdict:** Survives (D10's design is sound) but the "removes a ceiling the model couldn't
handle" framing is inaccurate.
**Proposed fix:** Reframe D8/D10's rationale from "the model refused new content (a ceiling)"
to "the model split production into two hand-driven entry points; we unify them so the
from-scratch and incremental cases share one code path." Add one risk line to D10 noting the
from-scratch vs incremental control-flow divergence the single pipeline must still branch on,
so a later design/plan does not assume the two cases are truly identical.

### A5 [suggestion]
**Certificate:** Open-question: machinery name + Maven-module sub-question not closed
**Target:** Open Question #3 (machinery home/form; name alternatives; "module" =
Maven-module?)
**Challenge:** OQ3 raised three sub-questions: (1) machinery home/form, (2) the name
(`workflow-book-builder` vs `-press`/`-forge`/`-pipeline`, since the user said "or can
propose more appropriate name if that one feels not fitting"), and (3) whether the user's
word "module" meant a Maven module. D2 resolves (1) and (3) cleanly (prose-prompt directory,
not a Maven module — content is markdown, not Java). But it adopts `workflow-book-builder`
without recording that the name alternatives were considered and this one chosen, leaving
OQ3 partially open against the gate's rule that a load-bearing open question must be folded
into the Decision Log or explicitly waived. The name is low-stakes (a directory rename is
cheap), so this is a suggestion, not a should-fix — but the Open Questions section still
lists OQ3 as live with no resolution pointer.
**Evidence:** `research-log.md` OQ3 vs D2 — D2 picks the name without a one-line
"alternatives rejected: -press/-forge/-pipeline" note; the user's initial request explicitly
invited a better name.
**Proposed fix:** Either add the name decision to D2's `Alternatives rejected:` line (one
sentence: chose `-builder` over `-press`/`-forge`/`-pipeline` because it reads as
plain-language and matches the `*-builder` convention) or annotate OQ3 as resolved-by-D2
with the Maven-module sub-question explicitly closed (markdown, no Maven). All four Open
Questions otherwise map to decisions (OQ-scope → D1, OQ-diagram → D5/D6, OQ-machinery → D2,
OQ-audience → D4), so closing OQ3's residue clears the section.

## Evidence base

#### Challenge: D5/D6 — committed-SVG/D2 diagram pipeline
- **Chosen approach:** ASCII-default diagrams with committed rendered SVG for the few dense
  figures; SVG authored in sidecar `.d2` and rendered by `render-diagrams.sh` (D5, D6).
- **Best rejected alternative:** the model's own status quo — inline mermaid in every chapter
  — or all-ASCII (already mixed into the workflow docs and house-style-preferred).
- **Counterargument trace:**
  1. The user chose `docs-ytdb-internals-book/` as the base. That base renders every diagram
     as an inline ```mermaid``` block (17/17 chapters) and ships no rendered assets.
  2. D5/D6 diverge to a committed-SVG + D2 pipeline that has zero precedent in the model or
     the wider repo (no `*.svg` under `docs/`, no `*.d2`, no `render*.sh`).
  3. The rationale solves the user's real viewer-portability complaint but does not weigh the
     heavy new artifact class against the cheaper all-ASCII option the log itself cites as
     already present in `branch-divergence-check.md` / `conventions-execution.md`.
- **Codebase evidence:** model chapters all-mermaid, no assets; repo-wide SVG/d2/render-script
  search empty.
- **Survival test:** WEAK — the decision is reasonable but its rationale omits the
  status-quo and all-ASCII comparisons and the diffability-loss re-weighing.

#### Assumption test: D2 binary is more robust in CI/containers than mermaid
- **Claim:** D2 is "more robust in CI/containers, no headless-browser dependency" than
  mermaid-cli (D6).
- **Stress scenario:** a clean checkout runs `render-diagrams.sh`.
- **Code evidence:** `which d2` → not installed; `which node npx` → present; `which mmdc` →
  not installed. No install step for d2 in the repo or any CI workflow.
- **Verdict:** FRAGILE — the only half-present toolchain is mermaid's (`node`/`npx`); d2's
  binary is absent and unprovisioned, so the "robust" claim is asserted, not demonstrated,
  and a clean run fails on a missing binary.

#### Challenge: D10 — refresh "mirrors the workflow-drift-check gate"
- **Chosen approach:** the unified pipeline "computes the drift window over `.claude/workflow/**`
  + skills + agents since the baseline workflow-SHA, mirroring the workflow-drift-check gate
  already in the repo" (D10, Surprise #4).
- **Best rejected alternative (mis-cited model):** the internals book's
  `MAINTENANCE_PROMPT.md`, which is the actual source-tree-drift analogue.
- **Counterargument trace:**
  1. `workflow-drift-check.md` detects drift in `_workflow/**` plan artifacts via per-file
     `workflow-sha:` stamps and a `git merge-base` fold, routing to `/migrate-workflow` — it
     does not classify `.claude/workflow/**` content.
  2. The book refresh needs a content-drift `git log <baseSHA>..HEAD -- <source paths>` and a
     chapter clean/sweep/review triage — exactly `MAINTENANCE_PROMPT.md` Phase 0–2.
  3. Citing the stamp gate as the model implies inheriting stamp/merge-base machinery the
     markdown book does not have.
- **Codebase evidence:** `workflow-drift-check.md` §Detection (plan-artifact stamp fold);
  `MAINTENANCE_PROMPT.md` Phase 0 (source-tree `git log` window).
- **Survival test:** WEAK — the workflow-SHA baseline reuse is sound, but the "mirrors the
  drift-check gate" framing cites the wrong mechanism.

#### Challenge: D8/D10 — one evolution-aware pipeline replaces refresh+cycle split
- **Chosen approach:** one unified evolution-aware pipeline; initial production is the
  empty-baseline case (D10); evolution uses the full author/review/copy-edit/beta role set,
  removing the model's "new content needs a manual cycle" ceiling (D8).
- **Best rejected alternative:** the model's working two-mode design (lightweight
  `MAINTENANCE_PROMPT` refresh + hand-driven full cycle for new content).
- **Counterargument trace:**
  1. D8 frames the model as having a ceiling it "refused" to cross (new content).
  2. The model's README shows cycle 1 → cycle 2 added new chapters via the manual-cycle path
     successfully; the split is intentional, not a defect.
  3. The from-scratch (empty baseline) and incremental (drift triage) cases have different
     control flow; folding them into one pipeline risks over-generality for the common case.
- **Codebase evidence:** `README.md` §Production record (cycle 1+2 new content);
  `MAINTENANCE_PROMPT.md` Rule 5/Phase 5 (explicit new-content handoff to a cycle).
- **Survival test:** YES — the unification is elegant; only the "removes a ceiling" rationale
  is overstated.

#### Open-question: machinery name + Maven-module sub-question not closed
- **Claim:** OQ3 (machinery home/form, name, "module"=Maven?) is resolved by D2.
- **Stress scenario:** gate rule requires a load-bearing OQ to be folded into the Decision
  Log or explicitly waived.
- **Code evidence:** D2 resolves home/form and the Maven sub-question but does not record the
  name-alternative choice; OQ3 still listed as live.
- **Verdict:** HOLDS (low stakes) — name is a cheap directory rename, but the OQ residue
  should be closed for cleanliness.

#### Survival summary (decisions that survive without finding)
- D1 (builder-only scope), D2 (prose-prompt, non-Maven, non-workflow-modifying), D3 (split
  book/machinery dirs), D4 (onboarding audience), D7 (TOC generated by first run, not shipped),
  D9 (TOC living artifact owned by the book) all track the chosen base model faithfully: the
  model ships its TOC *with* the book (`docs/ytdb-internals-book/TOC.md`), keeps machinery
  prose-prompt-driven (`BOOK_BRIEF.md` + `MAINTENANCE_PROMPT.md`), and is non-`.claude/`. No
  challenge survives against these.
