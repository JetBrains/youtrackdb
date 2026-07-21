# Design Decisions in Scoped JavaDoc

Code design decisions are documented in JavaDoc at the scope they govern; separate ADR
files are retired. The umbrella PR description (which becomes the squash-commit body)
remains the point-in-time record of WHY a change was made; JavaDoc carries the living
design rationale of the code as it stands.

## Scoping ladder

The narrowest correct level wins:

- **Decision local to one method** (algorithm choice, tricky invariant) → method JavaDoc.
- **Decision shaping one class** (data structure, concurrency policy) → class JavaDoc,
  with an own `<h2>Design decisions</h2>` section when substantial.
- **Decision spanning classes within one package** → a "Design decisions" section in that
  package's `package-info.java`.
- **Decision spanning packages or modules** → exactly ONE owning `package-info.java`
  carries the full rationale; every other affected package or class carries only a
  `{@link}`/`@see` reference to it — rationale text is NEVER duplicated.
- **Decision with no Java home** (build system, CI, code-generation/parser pipeline,
  tooling) → the nearest prose document (module `README.md` or a `docs-internal/` doc) —
  never a new ADR.

## Entry structure

Entries are numbered (D1, D2, …) per documentation site. Each entry states the decision,
the rationale, and the rejected alternatives; cite the YTDB issue where one exists,
otherwise the PR number, for provenance. Process artifacts (review verdicts, telemetry,
workflow notes) never go into JavaDoc — they belong to the PR description.

## Depth exemplar

The MATCH executor package documentation (`core`,
`com.jetbrains.youtrackdb.internal.core.sql.executor.match` `package-info.java`) shows the
expected depth and quality. It predates this convention and is narrative prose — it is a
depth exemplar, NOT a structural template; new design-decision sections use the numbered
entry structure above.

## Staleness rule

A change that modifies code covered by a design-decision entry MUST update that entry in
the same change (this extends the "keep comments in sync" rule in
`docs-internal/agents/thread-guidelines.md`). Reviews compose the DD charter
(`docs-internal/agents/review-perspectives.md`) to guard this.

## Tooling notes

doclint is disabled and JavaDoc is generated only at release time, so design-decision
JavaDoc receives no automated validation — the DD review charter and the mandatory user
review are the quality gates; malformed `{@link}` targets will not fail CI, so reviewers
check them.

## ADR archive

`docs-internal/adr/` is a frozen historical archive of the previous workflow generation —
no new entries are added for code OR workflow/process decisions. Workflow decisions live
in the slate extension docs and the `docs-internal/` guidance docs.
