# Book Brief: *Inside the YouTrackDB Query Engine*

## Working title

**Inside the YouTrackDB Query Engine: A Java Developer's Guide to MATCH, Planning, and Execution**

## Audience

A Java engineer who:

- Reads production Java fluently, but has never opened the YouTrackDB source.
- Understands relational query basics — what a join is, what an index is for — but has not written a query planner.
- May or may not have used a graph database before; the book builds graph vocabulary from scratch.

The reader's goal is to become productive inside `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/` — to be able to extend the planner, debug a slow MATCH, or add a new optimisation.

## Voice and pacing rules (non-negotiable for all authors)

1. **Narrative, not reference.** Every chapter tells a story with a beginning, a middle, and an end. The reader should finish a chapter with one or two new mental models, not with a list of facts.
2. **Concrete before abstract.** Open every section with a worked example or a short snippet. Define the abstraction *after* the reader has seen what it does. The source material does the opposite — invert it.
3. **One concept per section.** If a section introduces two concepts, split it. Pacing matters more than chapter length.
4. **Earn every name.** Class and method names appear only after the role they play has been described in plain English. Never lead with `MatchExecutionPlanner.estimateRootEntries()`; lead with "the planner needs to guess how many records each alias will match — here is how it does it".
5. **Connect forward and backward.** Open with one paragraph reminding the reader what they already know that this chapter builds on. Close with one paragraph naming what is coming next and which open question this chapter has set up.
6. **Source citations stay precise.** When you cite code, use `path/to/File.java:line` format with line numbers verified against the current tree. Citations belong in *Further reading* footers and in the running prose where the precise line is part of the argument — not as decoration.
7. **Diagrams must teach.** Mermaid diagrams should each carry one idea the prose can lean on. If the diagram restates the prose, delete one of them.
8. **No bullet-point fact dumps.** Use bullets when the reader is enumerating cases (the six traverser strategies, the four return forms) — not when explaining a single concept.

## Structural principles

- **Gradual ramp.** Earlier chapters introduce one new concept at a time. Later chapters compose previously-introduced concepts. A reader who reads in order should never meet an undefined term.
- **MATCH is introduced after SELECT.** Chapter 3 walks a `SELECT FROM Person WHERE name='Alice'` end-to-end before MATCH is mentioned. This gives the reader the pull-based execution model in its simplest possible form.
- **The eight-phase planner is presented twice.** Once as an overview (Chapter 7) so the reader has a map, then in depth across Chapters 8–10 with each cost-related phase getting its own chapter.
- **Optimisations come last.** Hash joins and index pre-filters are the only chapters that *replace* parts of the basic pipeline rather than *building* on it. They appear in Part VI, after the reader has the full nested-loop pipeline in their head.

## Conventions

- **Code identifiers** in `monospace` (backticks). Always.
- **Defined terms** in *italics* on first use within a chapter; in **bold italics** if the term is in the glossary.
- **File paths** relative to repository root. Always with line numbers when the citation depends on a specific line.
- **Diagrams** captioned `**Figure N.K — caption.**` below the closing fence; tables `**Table N.K — caption.**` above the table. `N` = chapter number, `K` = index within the chapter.
- **Chapters** numbered with Arabic numerals; **parts** numbered with Roman numerals.

## What the book is *not*

- Not a user manual. The reader is not learning how to write MATCH queries — they are learning how the engine compiles and executes them. Surface SQL details appear only as motivation.
- Not exhaustive. The book covers the query engine — parser, planner, executor, optimisations — and does not document the storage layer, transaction system, WAL, or distributed protocol beyond what the query engine touches.
- Not a fork of the existing match-book. The match-book is a reference that lists facts; this book teaches. Authors should *use* the match-book as a source of facts, but they should *not* port its sentence structure or section ordering.

## Source mapping

Each chapter cites which source `match-book/` chapters it draws from. Authors must read those source chapters and then *re-arrange* the material to fit the teaching arc described in the chapter brief. Authors may also need to read the actual code referenced in the source.

## Production pipeline

1. **Authors** (one per chapter, parallel waves) — write the chapter from scratch using the brief and source files.
2. **Technical reviewers** (one per ~3 chapters, parallel) — verify every code citation, line number, and factual claim against the live tree.
3. **Copy editors** (one per ~5 chapters, parallel) — pass for voice consistency, pacing, and the rules above.
4. **Beta readers** (3 personas, parallel) — read the full book in order; report what they understood, what they did not, what felt rushed, and what felt over-explained.
5. **Revision pass** — apply beta-reader feedback to flagged chapters.

A chapter is "done" only when it has cleared all five steps.

Before authoring or editing a chapter, read `yql-internals-book-builder/PRODUCTION_CHECKLIST.md` for the executable version of this pipeline (including the verify-every-claim-against-source rule), and `yql-internals-book-builder/VOICE_EXEMPLAR.md` for the concrete fingerprint and anti-pattern rubric that makes the voice rules above checkable.
