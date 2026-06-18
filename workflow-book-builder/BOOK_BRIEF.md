# Book brief: *Running the YouTrackDB development workflow*

This brief fixes the audience, voice, conventions, diagram rules, and the four production roles for a teaching book about the YouTrackDB development workflow. Authors and reviewers treat the voice rules and conventions as non-negotiable. The pipeline that drives the roles is in [`PIPELINE.md`](PIPELINE.md); the diagram convention this brief summarises is specified in full in [`DIAGRAMS.md`](DIAGRAMS.md).

The book is produced by the machinery in this `workflow-book-builder/` directory and lands in a separate tree, `docs/workflow-book/`. This brief is machinery; the book it describes is the machinery's output. No chapter exists until a production run writes one.

## Working title

**Running the YouTrackDB development workflow: an onboarding guide to phases, tiers, tracks, and reviews.**

## Subject

The book documents the YouTrackDB development workflow: the prose procedures under `.claude/workflow/` in the YouTrackDB repository, plus the skills under `.claude/skills/` and the review agents under `.claude/agents/` that those procedures invoke. The workflow takes a change from a first request through research, planning, track-by-track implementation, and review, to a final set of merged artifacts. The book teaches a new engineer how to run that workflow and why it is shaped the way it is.

The subject corpus is large: about 31 workflow documents (about 1.0 MB of prose) under `.claude/workflow/`, plus 11 prompt files, 20 review agents, and 16 skills. Chapters map to concepts, not one-to-one to files. A single concept (a phase, a tier gate, the track/step/episode hierarchy) often draws from several source files, and one large source file often feeds several chapters.

The book pins a *baseline workflow-SHA*: the git commit of the `.claude/workflow` tree that the current chapters describe. Every claim in the book is true as of that commit. When the workflow changes, an evolution run walks the commits since the baseline to find what drifted. The baseline is recorded in `docs/workflow-book/README.md`; the current value is `3e9c22298d`.

## Audience

A new engineer joining the YouTrackDB project who:

- Reads and writes code fluently, and has shipped changes through a pull-request review process before.
- Has used git, branches, and CI, but has never run this project's structured workflow.
- Wants to make a real change soon and needs to know which procedure to follow, in what order, and why each step exists.

The reader's goal is to run a change through the workflow end to end without a mentor narrating each step: to pick the right change tier, write a plan that passes review, decompose a track into steps, drive the implement-test-commit loop, and read a review report. The book builds the vocabulary (phase, tier, track, step, episode, gate, drift) from scratch.

## Voice and pacing rules (non-negotiable for all authors)

1. **Narrative, not reference.** Every chapter tells a story with a beginning, a middle, and an end. The reader should finish a chapter with one or two new mental models, not a list of facts. The source procedures are reference documents; the book inverts them into a teaching arc.
2. **Concrete before abstract.** Open every section with a worked example or a short walkthrough of one real run. Define the abstraction after the reader has seen it work. Lead with "you have a one-line change and no design question, so you take the minimal tier", not with the tier-gate definition.
3. **One concept per section.** If a section introduces two concepts, split it. Pacing matters more than chapter length.
4. **Earn every name.** A workflow file, a phase number, a role name, or a gate appears only after the job it does has been described in plain English. Never lead with "`step-implementation.md` orchestrates sub-steps 1 through 7"; lead with "after the plan is approved, each step is implemented, tested, and committed one at a time, then reviewed."
5. **Connect forward and backward.** Open each chapter with one paragraph reminding the reader what they already know that this chapter builds on. Close with one paragraph naming what is coming next and which question this chapter has set up.
6. **Source citations stay precise.** When you cite a workflow file, use `path/to/file.md` (and a section anchor where the precise section is part of the argument), verified against the tree at the baseline SHA. Citations belong in *Further reading* footers and in running prose where the exact file is part of the argument, not as decoration.
7. **Diagrams must teach.** Each diagram carries one idea the prose leans on. If a diagram restates the prose, delete one of them. The diagram convention is in [`DIAGRAMS.md`](DIAGRAMS.md): every diagram is an inline Mermaid fenced block, placed where the prose leans on it.
8. **No bullet-point fact dumps.** Use bullets when the reader is enumerating cases (the three tiers, the four early-return outcomes). Do not use them to explain a single concept.

## Structural principles

- **Gradual ramp.** Earlier chapters introduce one new concept at a time. Later chapters compose concepts the reader already has. A reader who reads in order never meets an undefined term.
- **Run a change before naming the phases.** An early chapter walks one small change through the whole workflow at low altitude, so the reader has the shape in their head before any phase is opened in depth. This mirrors the way the source workflow is learned in practice: you run a minimal change first.
- **Tiers before the full machinery.** The change tiers (`full` / `lite` / `minimal`) decide how much of the workflow applies. Teach the tier gate early so the reader knows which later chapters apply to their change, then open the full-tier machinery only after the reader has seen a minimal run.
- **Reviews and drift come last.** The dimensional review agents and the drift/migration machinery build on the track and step structure. They appear after the reader has the implement-test-commit loop in their head.

## Conventions

- **File and concept names** in `monospace` (backticks). Always, for any workflow file or identifier.
- **Defined terms** in *italics* on first use within a chapter; in **bold italics** if the term is in the glossary.
- **Reserved terms keep their defined meaning.** The vocabulary the book defines — *phase*, *tier*, *track*, *step*, *episode*, *gate*, *drift* — each names one concept, and none of these words is used in a loose or generic sense that is not that concept. For a generic stage of a process, write "phase" (when it is one of the five), "stage", or "part" — never "step", which is the unit a track is decomposed into. The collision is worst before the defining chapter: an early chapter that calls the five phases "a sequence of steps" mis-trains the reader against the later definition.
- **File paths** are relative to the repository root, with a section anchor when the citation depends on a specific section.
- **Diagrams** captioned `**Figure N.K — caption.**` below the closing fence; tables `**Table N.K — caption.**` above the table. `N` is the chapter number, `K` the index within the chapter.
- **Chapters** numbered with Arabic numerals; **parts** numbered with Roman numerals.
- **Chapter cross-references are links.** When the prose refers to another chapter by number, write it as a relative Markdown link to that chapter's file: `[Chapter 3](03-tiers-and-the-tier-gate.md)`. In a range or a list, link each numeral: `Chapters [4](04-phase-0-research.md) through [6](06-phase-1-plan-and-tracks.md)`. Do not link a non-numbered reference ("the next chapter", "later chapters"), a chapter's reference to its own number, a Figure or Table reference, a source-file citation in *Further reading*, or a chapter number inside a Mermaid node label — a link there renders as literal text and corrupts the diagram.
- **House style applies.** All book prose follows the project house style at `.claude/output-styles/house-style.md`: BLUF lead, the banned-vocabulary list, em-dash discipline, and the structural rules. The book is a durable artifact under `docs/`, so it is in scope for the full rule set.

## What the book is *not*

- Not a copy of the workflow files. The reader is learning to run the workflow, not memorising the procedures verbatim. Authors use the source files as the record of fact, then rearrange the material into the teaching arc.
- Not exhaustive. The book teaches the workflow a new engineer runs: phases 0 through 4, the tiers, tracks and steps and episodes, the review agents, and drift and migration. It does not document every gate, every recovery branch, or every agent prompt in full; it teaches the shape and points at the source for the rest.
- Not a design rationale archive. Where a decision's rationale helps the reader run the workflow (why a tier exists, why staging keeps the branch off the live workflow), the book explains it. It does not reproduce the full decision history.
- Not a change log. The book describes the workflow as it stands at the baseline SHA, in the present tense. It does not narrate how the workflow got there: no "older versions", "was retired", "used to", "previously". When a source file keeps its own historical note about a since-removed feature, the book takes the current-state fact and drops the history.

## Source mapping

Each chapter records which source files under `.claude/workflow/`, `.claude/skills/`, and `.claude/agents/` it draws from, in its per-chapter brief in `docs/workflow-book/TOC.md`. Authors read those source files, verify every claim against the tree at the baseline SHA, then rearrange the material into the chapter's teaching arc. The cross-reference matrix in `TOC.md` is the chapter-to-source map that evolution runs use to find which chapters a source change touches.

## The four production roles

A chapter is produced by four roles, run in waves. The expanded prompt for each role is in [`prompts/`](prompts/); the pipeline that spawns them is in [`PIPELINE.md`](PIPELINE.md). The four roles are fixed: every part of the machinery names these same four, with none added and none missing.

1. **Author** — writes or rewrites one chapter from the source files plus this brief. One author per chapter, run in parallel waves. The author reads the source files the chapter's brief names, verifies them against the tree at the baseline SHA, and produces a chapter that follows the voice and pacing rules above. Prompt: [`prompts/author.md`](prompts/author.md).
2. **Technical reviewer** — verifies every source citation, file reference, and factual claim in a chapter against the tree at the baseline SHA. One reviewer per roughly three chapters, run in parallel. Flags each finding as a blocker, a fix, or a nit, and writes a report under the run scratch directory the producer creates in an OS temp dir (see `PIPELINE.md` Step 0), not inside the repository. Prompt: [`prompts/technical-reviewer.md`](prompts/technical-reviewer.md).
3. **Copy editor** — passes a chapter for voice consistency, pacing, and the house style, without changing factual claims. One editor per roughly five chapters, run in parallel. Prompt: [`prompts/copy-editor.md`](prompts/copy-editor.md).
4. **Beta reader** — reads the book (or the touched chapters) in order, as the target reader would, and reports what they understood, what they did not, what felt rushed, and what felt over-explained. Three personas, run in parallel, writing reports under the run's OS-temp scratch directory (see `PIPELINE.md` Step 0), not inside the repository. Prompt: [`prompts/beta-reader.md`](prompts/beta-reader.md).

A chapter is done only when it has cleared all four roles. The pipeline orders the waves and decides which roles run on which chapters; this brief only fixes what each role is.

## Diagram convention

Every diagram in the book is an inline Mermaid fenced code block, placed where the prose leans on it. There are no committed image files and no render step. The convention is specified in full in [`DIAGRAMS.md`](DIAGRAMS.md). Mermaid renders natively on GitHub, matches the convention the workflow's own design documents already use (their class and sequence diagrams are Mermaid), and needs no build step or committed binaries.
