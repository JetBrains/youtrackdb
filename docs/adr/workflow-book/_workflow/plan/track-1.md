<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 1: Workflow-book builder machinery

## Purpose / Big Picture
After this track lands, an operator has a complete set of copy-paste prompts that, when run, produce a book teaching new engineers the YouTrackDB development workflow. The book itself is not yet written.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track builds the generator that produces a book about the YouTrackDB development workflow, not the book itself. The workflow being documented is the set of prose procedures under `.claude/workflow/` (phases 0–4, change tiers, tracks/steps, review agents, drift and migration). The generator is a top-level `workflow-book-builder/` directory of copy-paste prose prompts and briefs that an operator pastes and drives by hand, modeled on the existing `docs-ytdb-internals-book/` production pipeline. The book it will later emit lands in a separate `docs/workflow-book/` tree, whose layout this track stamps out empty. The one departure from the model: diagrams are ASCII by default with a small set of committed D2-rendered SVGs, replacing the mermaid the model uses, because mermaid renders unreliably across viewers.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-16T08:47Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-16T09:43Z [ctx=safe] Step 1 complete (commit e4ef1c6916)
- [x] 2026-06-16T09:49Z [ctx=safe] Step 2 complete (commit e796695b8d)
- [x] 2026-06-16T09:49Z [ctx=safe] Step implementation complete (Phase B)
- [x] 2026-06-16T10:22Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns (full four-bullet form below); the
section then continues as the execution-time continuous log. Seeded from
the research log. One block per decision: -->

#### D1: Build the builder machinery only, not the first book
- **Alternatives considered**: Build the machinery and the first run's `docs/workflow-book/` content together in one plan.
- **Rationale**: The aim was to "create a workflow to generate a book"; the book is the machinery's output, so the plan that builds the generator is complete without any chapter. Separating the two keeps this plan bounded and lets the machinery be reviewed before any content quality depends on it. The combined option couples generator quality to first-draft content quality and makes the plan too large.
- **Risks/Caveats**: A reviewer cannot see the machinery exercised end-to-end, because no chapter is produced here; the render path in particular never runs (see D6). Acceptance is therefore by inspection of the prompts and layout, not by a generated artifact.
- **Implemented in**: this track (step references added during execution).

#### D2: Machinery lives in a top-level `workflow-book-builder/` directory; the branch is not workflow-modifying
- **Alternatives considered**: Codify the machinery as `.claude/` skills and agents (the repo's native form for procedures the harness runs). Names other than `workflow-book-builder`: `-press`, `-forge`, `-pipeline`. Treat the user's word "module" as a Maven module.
- **Rationale**: The machinery is copy-paste prose prompts an operator drives by hand, not code the harness executes, so a plain top-level directory fits and keeps the branch off the `.claude/**` change path. That matters because edits under `.claude/**` would make the branch workflow-modifying — triggering §1.7 staging (a convention that mirrors any `.claude/**` change into a staged copy and adds a drift baseline) and the heavier review overhead that goes with it, for no payoff at this stage. The internals-book pipeline already proved the prose-prompt model works without any of that machinery. The name `workflow-book-builder` reads as plain language and matches the repo's `*-builder` convention; the user invited a better name and this one fits. The content is markdown, not Java, so "module" is read as "directory", not a Maven module.
- **Risks/Caveats**: A future decision to make the prompts harness-native (`.claude/` skills) would flip the branch to workflow-modifying and pull in the staging and drift machinery deliberately avoided here. That is a separate change, not a silent drift of this one.
- **Implemented in**: this track (step references added during execution).

#### D3: Book output home is `docs/workflow-book/`
- **Alternatives considered**: Keep production machinery and book content in one interleaved directory, the layout the internals book uses.
- **Rationale**: Reading material belongs under `docs/`; production machinery does not. The user's stated exception splits the two so the book and the generator that builds it do not share a directory.
- **Risks/Caveats**: The split means two trees stay in sync by convention (the book pins a baseline that the machinery reads), rather than by sitting side by side. The pipeline doc and README carry that coupling.
- **Implemented in**: this track (step references added during execution).

#### D4: Audience is new engineers adopting the workflow
- **Alternatives considered**: Frame the book for contributors extending the workflow, or as a reference-only "why it is shaped this way" narrative.
- **Rationale**: The user named onboarding as the goal. That fixes an onboarding-first teaching arc — how to run the workflow and why it is shaped the way it is, ramping one concept at a time, concrete before abstract. The other framings sit at the wrong altitude for a first-time reader.
- **Risks/Caveats**: An onboarding arc under-serves an experienced extender who wants reference depth; the TOC the first run generates must still leave room for that reader without breaking the ramp.
- **Implemented in**: this track (step references added during execution); the audience and arc are written into `BOOK_BRIEF.md`.

#### D5 + D6: Diagrams are hybrid (ASCII default, committed D2-rendered SVG for a named dense set)
- **Alternatives considered**: Mermaid-only (the model's status quo, the very viewer-support problem being solved); all-ASCII (too weak for the dense figures); render-on-read from a DSL (forces every reader to install tooling). For the render DSL itself: mermaid via `mmdc` (a heavy, fragile puppeteer/Chromium install, and the only half-present toolchain today); Graphviz/DOT (lower-level syntax, weaker flowchart layout).
- **Rationale**: Recorded together: the diagram convention and its render DSL are one decision, since the choice of which figures get rendered (D5) is meaningless without the tool that renders them (D6). ASCII box-and-arrow diagrams in fenced blocks render identically in every viewer, need zero tooling, and diff cleanly, so they are the default for all flow and box figures. The exception is a short, explicitly enumerated set of dense figures that ASCII cannot lay out: the candidates are the phase state machine, the tier-gate decision tree, and the track/step/episode hierarchy. Each such figure is authored in D2 source kept in a sidecar `.d2` file. The `.d2` is rendered to committed SVG, so readers need no build step while the source stays diffable. The exact figure list is fixed at TOC time (the chapter plan is generated by the first run, per D7), which bounds the SVG footprint to a named set rather than an open category. D2 is chosen over the alternatives as a preference for a single Go binary with no headless-browser dependency, not a measured robustness claim. This is the one place the workflow-book deliberately departs from the internals-book model, which ships inline mermaid in all 17 chapters and zero rendered assets (see the model book at `../docs-ytdb-internals-book/`).
- **Risks/Caveats**: Pre-rendered SVG loses some diffability versus inline source; that loss is accepted in exchange for reader-side portability and contained by keeping the SVG set small and the source diffable in the sidecar. Neither render tool ships in the current environment — `d2` is not installed, `mmdc` is not installed, only mermaid's `node`/`npx` prerequisite is present — so `render-diagrams.sh` must check for the `d2` binary and print the install command on a miss, and `BOOK_BRIEF.md` must document the one-time `d2` install as an operator step. The render path does not run during this plan: D1 scopes the deliverable to the builder with no chapters authored, so the renderer first runs in a later production cycle, which is why the missing-binary case is an operator-doc requirement here, not an execution blocker for this plan.
- **Implemented in**: this track (step references added during execution); the convention is written into `DIAGRAMS.md` and `BOOK_BRIEF.md`, the script into `scripts/render-diagrams.sh`.

#### D7: The chapter plan / TOC is generated by the first production run, not shipped as a builder artifact
- **Alternatives considered**: Ship a pre-authored chapter map (`CHAPTER_PLAN.md`) as part of the machinery.
- **Rationale**: `BOOK_BRIEF.md` defines the audience and arc principles; the first step of the unified pipeline (D10) produces the table of contents and the per-chapter briefs, mirroring the internals book's cycle-1 first step. Keeping the chapter map an output of running the generator keeps the machinery generic and reusable. A pre-authored map couples the generic machinery to one chapter decomposition and front-loads content design into this plan.
- **Risks/Caveats**: The machinery cannot be validated against a concrete chapter list here, since none exists until a run; acceptance checks the brief and pipeline that will generate the TOC, not the TOC.
- **Implemented in**: this track (step references added during execution).

#### D8 + D10: One unified evolution-aware pipeline using the full role set; initial production is the empty-baseline case
- **Alternatives considered**: Keep the model's two separate hand-driven entry points — a lightweight drift-refresh prompt and a heavyweight author-wave cycle for new content — sharing the role prompts. A narrow line-number drift sweep that cannot add or restructure chapters.
- **Rationale**: Recorded together: the full-role evolution stance (D8) and the single unified pipeline that implements it (D10) are one decision, because the reason to use the full role set is the same reason to keep one pipeline doc. The book's subject (`.claude/workflow`) keeps changing, so evolution runs must be able to add and restructure chapters, not only sweep changed lines; that needs the full role set (author → technical-reviewer → copy-editor → beta-reader waves), the same set initial production uses. Folding initial production and ongoing evolution into one pipeline doc means one copy of the wave-orchestration prose and one fewer artifact to keep in sync. The unification is framed as evolution from an empty baseline. A run does the following:
  1. Compute the drift window over `.claude/workflow/**` plus skills and agents since the baseline workflow-SHA (the `.claude/workflow` commit the book pins, the way the internals book pins a source commit).
  2. Classify impact per area as clean, sweep, rewrite, or new-or-restructure.
  3. Edit the TOC for the new-or-restructure band.
  4. Run the author / technical-reviewer / copy-editor / beta-reader waves on the touched chapters.
  5. Bump the baseline and append an evolution-history row.
  Initial production is the case where the baseline is empty, so the drift window is everything, the TOC is built from scratch, and every chapter goes through the full waves. The model's split into two separate hand-driven entry points (see the model book at `../docs-ytdb-internals-book/`) is a working design, not a defect; folding it lets one operator path cover both refresh and add-content without choosing the mode up front. The two-entry-point alternative makes the operator pick the mode before knowing the drift size and duplicates the wave-orchestration prose.
- **Risks/Caveats**: The from-scratch case (empty baseline, TOC from scratch, every chapter) and the incremental case (drift window, triage, a few touched chapters) have genuinely different control flow. "One code path" means one document and one role set, not identical control flow: the pipeline doc must branch explicitly on baseline-empty versus non-empty, so it is neither over-general for the common incremental case nor under-specified for the from-scratch case.
- **Implemented in**: this track (step references added during execution); the pipeline is written into `PIPELINE.md` and the four role prompts under `prompts/`.

#### D9: The TOC is a living artifact owned by the book, modified during evolution
- **Alternatives considered**: A frozen TOC with append-only chapters.
- **Rationale**: The TOC lives at `docs/workflow-book/TOC.md` as the chapter map plus a cross-reference matrix (chapter → source-concept map). When the source workflow gains a concept with no chapter home, the pipeline edits the TOC — adding, reordering, or splitting chapters — and updates the matrix. A book that tracks an evolving subject cannot freeze its chapter structure, and this is consistent with D7 (the chapter plan is generated by the first run, never shipped frozen). The append-only alternative cannot absorb restructuring when the subject reorganizes.
- **Risks/Caveats**: A living TOC plus matrix is a maintenance surface the evolution runs must keep accurate; the cross-reference matrix can drift from the actual chapters if a run edits one without the other. The pipeline doc owns that coupling.
- **Implemented in**: this track (step references added during execution); the layout is stamped empty under `docs/workflow-book/`, filled by a later run.

#### D11: Concrete artifact layout (settled)
- **Alternatives considered**: Leave the layout scattered across the earlier decisions (D2/D3/D5/D6/D7/D9/D10) rather than consolidating it into one file list. Keep separate `PRODUCTION_PROMPT.md` and `MAINTENANCE_PROMPT.md` instead of collapsing them.
- **Rationale**: One explicit file list lets the plan derive from the research log alone, without re-reading the earlier decisions to reconstruct what to create. The machinery (`workflow-book-builder/`) holds `BOOK_BRIEF.md`, `PIPELINE.md`, `DIAGRAMS.md`, the four `prompts/*.md` role files, `scripts/render-diagrams.sh`, and empty `reviews/` and `beta-feedback/` run-output directories. The book target (`docs/workflow-book/`) holds `README.md`, `TOC.md`, an empty `chapters/`, and an empty `assets/diagrams/`. The separate production and maintenance prompts collapse into the single `PIPELINE.md` per D10.
- **Risks/Caveats**: The book-target directories ship empty, so each empty directory needs a `.gitkeep` or a one-line placeholder README to make the layout visible in version control without committing generated content (D1 — builder only).
- **Implemented in**: this track (step references added during execution).

#### D12: The operator's single entry point is a copy-paste START prompt embedded as the lead section of `PIPELINE.md`
- **Alternatives considered**: A separate `START.md` paste-target that points at `PIPELINE.md` as the detailed reference.
- **Rationale**: The unified pipeline (the grouped D8 + D10 record above) folded the model's two prompts into one `PIPELINE.md` but lost the pasteable-entry-point property, and D12 restores it. `PIPELINE.md` opens with a self-contained "paste this into a fresh session" block, followed by operator context; the operator pastes exactly one thing to start any run. This mirrors the base model exactly: the internals book's `MAINTENANCE_PROMPT.md` is a single file whose first section is the copy-paste block and whose remainder is operator context. The four `prompts/*.md` role files are spawned by the orchestrating session, not pasted by hand, so there is one human entry point. Embedding the block keeps D10's single-pipeline-file unification and avoids adding a file.
- **Risks/Caveats**: The start block must be self-contained: it re-reads `BOOK_BRIEF.md`, `DIAGRAMS.md`, the role prompts, `docs/workflow-book/TOC.md`, and the source tree on its own. It must also carry the empty-versus-non-empty-baseline branch (D10) so one paste covers both initial production and evolution.
- **Implemented in**: this track (the `PIPELINE.md` authoring step).

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (1 finding, 1 accepted) — 0 blockers. T1 (should-fix): corpus-size figure corrected from "about 1.3 MB" to "about 1.0 MB" for the 31 workflow docs (measured 0.99 MB; counts 31/11/20/16 verified exact). Risk and Adversarial dropped under `minimal` tier.

## Context and Orientation
This track produces two directory trees that, together, form a generator and an empty home for what it generates. The first tree, `workflow-book-builder/`, is the machinery: prose prompts and briefs an operator pastes into an agent and drives by hand. The second tree, `docs/workflow-book/`, is the book target: where a later run of the machinery writes the actual reading material. The split is the user's explicit requirement — reading material belongs under `docs/`, production machinery does not (D3) — and is the one place this design departs from its model.

The model the machinery imitates is the YouTrackDB internals book, which lives at `../docs-ytdb-internals-book/docs/ytdb-internals-book/` (a sibling repository) and is described in its `BOOK_BRIEF.md`. That book proved the prose-prompt production model: a `BOOK_BRIEF.md` fixes voice and audience, a maintenance prompt and an author-wave cycle drive author / technical-reviewer / copy-editor / beta-reader passes by hand, and a `TOC.md` carries the chapter map plus a cross-reference matrix. The internals book keeps machinery and content in one interleaved directory; this track splits them (D2, D3).

The subject the book documents is the YouTrackDB development workflow — the prose procedures under `.claude/workflow/` in this repository. That corpus is large: 31 workflow docs (about 1.0 MB) plus 11 prompts, 20 agents, and 16 skills. The book will teach phases 0–4, the change tiers (`full` / `lite` / `minimal`), tracks / steps / episodes, the review agents, drift and migration, the workflow-SHA stamps, and §1.7 staging. Chapters map to concepts, not one-to-one to files. A *workflow-SHA* is a git commit hash of the `.claude/workflow` tree; the book pins one as its *baseline* (the snapshot the current chapters describe), and evolution runs walk `git log <baseline>..HEAD` over the source paths to find what changed. The current baseline is `3e9c22298d`.

The concrete deliverables are the ~13 files in the layout below. This track authors every machinery file and stamps the book-target layout empty; it writes no chapters and runs no renderer (D1).

```text
repository root
├── workflow-book-builder/            # machinery (D2) — copy-paste prompts, not .claude/ skills
│   ├── BOOK_BRIEF.md                 # audience, voice, conventions, diagram rules, role defs (D4, D5)
│   ├── PIPELINE.md                   # opens with the embedded copy-paste START prompt, then the unified evolution-aware pipeline (D8, D10, D12)
│   ├── DIAGRAMS.md                   # hybrid ASCII + D2 convention, render step, "how to add a figure" (D5, D6)
│   ├── prompts/
│   │   ├── author.md                 # the four role prompts the pipeline spawns (D8)
│   │   ├── technical-reviewer.md
│   │   ├── copy-editor.md
│   │   └── beta-reader.md
│   ├── scripts/
│   │   └── render-diagrams.sh        # D2 source → committed SVG; checks for `d2`, prints install on a miss (D6)
│   ├── reviews/                      # empty run-output dir (.gitkeep)
│   └── beta-feedback/                # empty run-output dir (.gitkeep)
│
└── docs/
    └── workflow-book/                # book target (D3) — layout stamped now, filled by a later run (D1)
        ├── README.md                 # production record + source-tree baseline (workflow-SHA) + evolution history (D10)
        ├── TOC.md                    # living chapter map + cross-reference matrix (D7, D9)
        ├── chapters/                 # empty (.gitkeep)
        └── assets/
            └── diagrams/             # committed fig-N.svg + .d2 sidecars; empty now (.gitkeep)
```

## Plan of Work
Author the machinery files in dependency order, then stamp the book-target layout empty. `BOOK_BRIEF.md` comes first because it defines the audience (D4), the voice, the conventions, and the four role definitions that the pipeline and the role prompts reference. `PIPELINE.md` and the four `prompts/*.md` files come next, because the pipeline orchestrates the roles the brief defined and each role prompt is the expanded form of one role definition. `PIPELINE.md` opens with the self-contained copy-paste START prompt, the operator's single entry point, followed by the orchestration logic and operator context (D12). `DIAGRAMS.md` and `scripts/render-diagrams.sh` form a pair: the convention doc names the bounded SVG figure set and the "how to add a figure" procedure (D5), and the script implements the D2-source → committed-SVG render step with the missing-binary guard (D6). The book-target files come last: `docs/workflow-book/README.md` (the production record with the baseline workflow-SHA and evolution-history table) and `docs/workflow-book/TOC.md` (stamped as the living-artifact placeholder the first run fills, D7/D9), then the empty `chapters/` and `assets/diagrams/` directories with `.gitkeep` placeholders so the layout is visible in version control.

Two invariants hold across every step. First, the branch touches no `.claude/**` file, so it stays non-workflow-modifying and avoids §1.7 staging (a convention that mirrors `.claude/**` edits into a staged copy, D2); if a step finds itself editing under `.claude/`, the design has drifted and the step stops. Second, the book-target directories ship empty — no chapter and no rendered diagram is committed by this track (D1), only the layout and the placeholders.

The single ordering constraint that crosses files is that the pipeline branches on an empty versus non-empty baseline (D10). `PIPELINE.md` must make that branch explicit: the from-scratch path (empty baseline, TOC built from scratch, every chapter through the full waves) and the incremental path (drift window, clean/sweep/rewrite/new-or-restructure triage, a few touched chapters) are one document and one role set, but not one control flow. The Concrete Steps roster below names the per-file steps once decomposition runs.

Phase A decomposition produced two `low`-risk steps along the machinery/book-target seam (D2/D3). Step 1 authors the entire `workflow-book-builder/` generator (the brief, the pipeline, the diagram convention and render script, and the four role prompts); Step 2 stamps the empty `docs/workflow-book/` layout. The split is driven by footprint: the whole track is ~14 files, which sits at the ~14 overblown line, so it splits into these two coherent halves rather than landing in one over-large step. Step 1 runs first (its `BOOK_BRIEF.md` defines the roles the pipeline and prompts reference, and the README in Step 2 records what the machinery does), though the two directory trees are disjoint and neither modifies the other's files. Every step is `low` because no file is under `.claude/**` (D2, non-workflow-modifying), no Java is touched, and no behavioral code path is added — the lone script is an operator helper this plan never runs (D1).

## Concrete Steps
1. Author the `workflow-book-builder/` machinery generator — `BOOK_BRIEF.md` (audience, voice, conventions, diagram rules, the four role definitions; D4/D5), `PIPELINE.md` (the embedded copy-paste START prompt then the unified evolution-aware pipeline with an explicit empty-vs-non-empty-baseline branch; D8/D10/D12), `DIAGRAMS.md` (hybrid ASCII + D2 convention, the enumerated bounded SVG figure set, the render step, the "how to add a figure" procedure, and the one-time `d2` install as an operator step; D5/D6), the four role prompts `prompts/{author,technical-reviewer,copy-editor,beta-reader}.md` (the expanded role definitions the pipeline spawns; D8), `scripts/render-diagrams.sh` (D2-source → committed SVG with the missing-`d2` guard that prints the install command; authored but not run, D1/D6), and the empty `reviews/` and `beta-feedback/` run-output dirs with `.gitkeep` (D11). — risk: low (default: prose machinery plus one operator-run helper script, neither under `.claude/**` (D2, non-workflow-modifying) nor executed by this plan (D1); no HIGH or MEDIUM trigger fires) — size: ~10 files; the only other low unit is Step 2 (book-target), and absorbing it reaches ~14 and trips the overblown split line (closed-set reason a)  [x] commit: e4ef1c6916
2. Stamp the `docs/workflow-book/` book-target layout empty — `README.md` (production record + pinned baseline workflow-SHA `3e9c22298d` + evolution-history table; D10), `TOC.md` (the living chapter-map + cross-reference-matrix placeholder the first run fills; D7/D9), and the empty `chapters/` and `assets/diagrams/` directories with `.gitkeep` so the layout is visible in version control; no chapter and no rendered diagram is committed (D1). — risk: low (default: pure `docs/` prose plus empty `.gitkeep` placeholders; no HIGH or MEDIUM trigger fires) — size: ~4 files; the only other low unit is Step 1 (machinery, ~10 files), and merging into it reaches ~14 and trips the overblown split line (closed-set reason a)  [x] commit: e796695b8d

## Episodes
<!-- Continuous-log. Phase B appends one block per completed step. Empty
at Phase 1. -->

### Step 1 — commit e4ef1c6916, 2026-06-16T09:43Z [ctx=safe]
**What was done:** Authored the 10-file `workflow-book-builder/` machinery generator. `BOOK_BRIEF.md` fixes the new-engineer audience (D4), the voice, the conventions, the hybrid diagram rules, and the four role definitions. `PIPELINE.md` opens with a self-contained copy-paste START prompt (D12), then the unified evolution-aware pipeline that branches explicitly on an empty versus non-empty baseline (D8/D10). `DIAGRAMS.md` documents the ASCII-default convention with a closed three-figure committed-D2-SVG set, the render step, the "how to add a figure" procedure, and the one-time `d2` install as an operator step (D5/D6). The four `prompts/*.md` files expand the role definitions the pipeline spawns. `scripts/render-diagrams.sh` renders D2 source to committed SVG behind a missing-`d2` guard that prints the install command; it is authored, not run (D1/D6). The empty `reviews/` and `beta-feedback/` run-output directories carry `.gitkeep`. The machinery imitates the sibling internals book's prose-prompt production model.

**What was discovered:** Step 2's `docs/workflow-book/` layout is coupled to `PIPELINE.md`, which already names the exact run-target paths (`docs/workflow-book/README.md`, `TOC.md`, `assets/diagrams/`, and `maintenance/drift-*.md`). Step 2 must keep those filenames byte-consistent and pin baseline workflow-SHA `3e9c22298d` (the value `BOOK_BRIEF.md` already names) in `README.md`, with a living-artifact `TOC.md` placeholder carrying the chapter-map plus cross-reference-matrix shape the pipeline edits. Corpus figures the track pinned verified against disk: 31 workflow docs at 0.99 MB (the directory `du` of 1.3 MB folds in the `prompts/` subdir), plus 11 prompts, 20 agents, 16 skills. `d2` is not installed, as D6 anticipated.

**Key files:**
- `workflow-book-builder/BOOK_BRIEF.md` (new)
- `workflow-book-builder/PIPELINE.md` (new)
- `workflow-book-builder/DIAGRAMS.md` (new)
- `workflow-book-builder/prompts/author.md` (new)
- `workflow-book-builder/prompts/technical-reviewer.md` (new)
- `workflow-book-builder/prompts/copy-editor.md` (new)
- `workflow-book-builder/prompts/beta-reader.md` (new)
- `workflow-book-builder/scripts/render-diagrams.sh` (new)
- `workflow-book-builder/reviews/.gitkeep` (new)
- `workflow-book-builder/beta-feedback/.gitkeep` (new)

### Step 2 — commit e796695b8d, 2026-06-16T09:49Z [ctx=safe]
**What was done:** Stamped the `docs/workflow-book/` book-target layout empty (the book-target half of the D11 layout). `README.md` is the production record: it pins baseline workflow-SHA `3e9c22298d` in a source-tree-baseline table and seeds an empty evolution-history table (D10). `TOC.md` is the living-artifact placeholder carrying the chapter-map plus cross-reference-matrix shape the first run fills (D7/D9), marked explicitly empty. The `chapters/`, `assets/diagrams/`, and `maintenance/` directories each carry a `.gitkeep` so the layout is visible in version control. No chapter and no rendered diagram is committed (D1 — builder only). The README's section names match `PIPELINE.md`'s run-step wording so a later run can find and bump the baseline.

**What was discovered:** `PIPELINE.md` (committed in Step 1) writes a drift report under `docs/workflow-book/maintenance/`, a path the roster's four-file list did not enumerate. Per the orientation's standing instruction to create any `docs/workflow-book/` run target `PIPELINE.md` references, `maintenance/.gitkeep` was added so no pipeline run target dangles; the model internals book carries the same directory. This is an in-scope completion of the layout, not a plan deviation.

**Key files:**
- `docs/workflow-book/README.md` (new)
- `docs/workflow-book/TOC.md` (new)
- `docs/workflow-book/chapters/.gitkeep` (new)
- `docs/workflow-book/assets/diagrams/.gitkeep` (new)
- `docs/workflow-book/maintenance/.gitkeep` (new)

## Validation and Acceptance
A reviewer can check this track by inspection, because no chapter is produced and the renderer does not run (D1). The acceptance criteria are:

- Every machinery file in the D11 layout exists and is internally consistent: the role definitions in `BOOK_BRIEF.md`, the roles `PIPELINE.md` orchestrates, and the four `prompts/*.md` files name the same four roles (author, technical-reviewer, copy-editor, beta-reader) with no fifth role and none missing.
- `PIPELINE.md` branches explicitly on an empty versus non-empty baseline (the D10 risk): the from-scratch path and the incremental triage path are both described, and the doc says which steps differ between them.
- `PIPELINE.md` opens with a self-contained copy-paste START prompt that an operator pastes into a fresh session to start a run, and that block covers both the empty-baseline (initial production) and non-empty-baseline (evolution) cases (D12).
- `DIAGRAMS.md` names the bounded SVG figure set as an enumerated list, not an open category (D5), and documents the "how to add a figure" procedure plus the one-time `d2` install as an operator step.
- `scripts/render-diagrams.sh` checks for the `d2` binary before rendering and prints the install command when it is missing (D6); the script is not expected to run successfully in the current environment, because `d2` is not installed.
- `docs/workflow-book/README.md` records the production model and pins a baseline workflow-SHA, and `TOC.md` exists as the living-artifact placeholder (D7/D9).
- The book-target layout is present and empty: `chapters/` and `assets/diagrams/` exist with `.gitkeep` (or one-line placeholder) and contain no chapter or rendered diagram (D1).
- No file under `.claude/**` is added or modified (D2 — branch stays non-workflow-modifying).

<!-- Phase A: no per-step EARS/Gherkin lines. This track is acceptance-by-
inspection (D1 — no test is authored or run), so there are no test method
names to derive; the track-level acceptance criteria above cover both steps. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim
as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
Both steps are idempotent: each authors or stamps a fixed set of files, so re-running a step overwrites those files to the same end state rather than appending. No external state, no migration, and no on-disk data format is touched (D1).

- **Step 1 (machinery).** Recovery from a failed or partial attempt: `git reset --hard HEAD` discards the partial `workflow-book-builder/` tree; re-run from a clean tree. `render-diagrams.sh` is authored but never executed here (D1/D6), so a partial attempt cannot leave behind rendered SVGs or a half-run pipeline.
- **Step 2 (book-target).** Recovery: `git reset --hard HEAD` discards the partial `docs/workflow-book/` layout; re-run. The directories ship empty with `.gitkeep`, so there is no generated content to clean up.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
The in-scope file boundary is exactly the D11 layout: the `workflow-book-builder/` machinery files and the empty `docs/workflow-book/` book-target layout, ~13 files in all.

Out of scope: no file under `.claude/**` (the branch stays non-workflow-modifying, D2); no authored chapter and no rendered diagram asset (D1 — the book-target trees ship empty); the renderer does not run, and this plan does not execute the pipeline `PIPELINE.md` describes — that pipeline runs in a later production cycle.

This is a single-track plan, so there are no inter-track dependencies. The only intra-track ordering constraint is the authoring order in Plan of Work: `BOOK_BRIEF.md` precedes `PIPELINE.md` and the role prompts because the brief defines the roles they reference.

The one machinery contract worth pinning is `scripts/render-diagrams.sh`. Its contract is D2 source in (the `.d2` sidecars under `docs/workflow-book/assets/diagrams/`), committed `fig-N.svg` out (D2 → committed SVG, D6). Because neither render tool ships in the current environment (`d2` not installed, `mmdc` not installed, only `node`/`npx` present), the script must check for the `d2` binary first and print the install command on a miss rather than failing opaquely. The script is authored here but not run here (D1).

## Base commit
d5dc3ac496e2dd00f7542a375a36c900c2b3a9f8
