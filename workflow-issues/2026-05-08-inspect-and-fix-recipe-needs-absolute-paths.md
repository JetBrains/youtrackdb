---
severity: medium
phase: phase-c
source-session: 2026-05-08 /execute-tracks read-cache-concurrency-bug
---

# `inspect-and-fix` recipe silently fails on project-relative paths

## Symptom

The Phase C pre-PR semantic pass invokes the `mcp-steroid://ide/inspect-and-fix`
recipe to run IntelliJ inspections over the cumulative track diff. The recipe
example uses a `findFile(filePath)` helper. Calling `findFile()` with
project-relative paths like
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java`
returns `null` for every file — even when the file exists on disk and is
indexed by the IDE. The output is a silent stream of `[skipped: not found]`
lines and `Total problems: 0` — no error, no diagnostic, no hint that the
path resolution was the problem.

Concretely this session, the first inspection invocation processed 9
production Java files and reported 0 problems with all 9 marked
`[skipped: not found]`. After switching to
`LocalFileSystem.getInstance().findFileByPath(absoluteBase + relPath)`
with a manually-prefixed `/home/andrii0lomakin/.../read-cache-concurrency-bug/`,
the same 9 files returned 18 real findings.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved:
  - `.claude/workflow/track-code-review.md` §"Pre-PR semantic pass via the
    `inspect-and-fix` recipe" — the doc says "Use the `inspect-and-fix`
    recipe (see `conventions.md` §1.4 *Recipes*); intersect the findings
    with `git diff {base_commit}..HEAD --name-only`" with no path-resolution
    caveat.
  - `.claude/docs/mcp-steroid/recipes.md` §"IntelliJ inspections on changed
    code (pre-PR pass)" — points at `mcp-steroid://ide/inspect-and-fix`
    without noting the path-resolution constraint.
- Tool / sub-agent involved (if any): `mcp__localhost-6315__steroid_execute_code`
  running the `mcp-steroid://ide/inspect-and-fix` recipe template
- ADR directory at the time: `docs/adr/read-cache-concurrency-bug/`
- Trigger condition: any Phase C session that runs the inspect-and-fix
  recipe on files listed via `git diff --name-only` (which returns
  project-relative paths). The natural agent reflex is to pass those
  paths verbatim into the recipe's `findFile()` call, which fails
  silently.

## Why it's a problem

Three concrete impacts:

1. **Wasted MCP turn per Phase C.** The first inspection invocation
   silently returns 0 findings, the agent investigates why (re-reading
   the recipe template, comparing path conventions), then retries with
   absolute paths. One full MCP `steroid_execute_code` round-trip is
   wasted per Phase C session.
2. **False-pass risk.** An agent that doesn't notice the `[skipped:
   not found]` prefix (it scrolls fast in large file sets) could
   conclude "no inspections found" and proceed to gate-check. The
   silent-skip output looks superficially similar to a clean inspection
   run.
3. **Recurring rediscovery.** The friction is encountered by every
   fresh Phase C agent that has never run the recipe before. No
   memory or rule prevents the second-encounter.

The recipe's example template uses a hardcoded
`val filePath = "/path/to/your/File.java"` (a fully-qualified path) with
a `// TODO: Set your file path` comment, which is correct in form but
doesn't call out *why* it's absolute. The friction is in the gap
between the example and the agent's natural reflex of passing
`git diff --name-only` output verbatim.

## Proposed fix

Two complementary edits.

### Edit 1 — Add a path-resolution note to `track-code-review.md`

Edit `.claude/workflow/track-code-review.md` §"Pre-PR semantic pass via
the `inspect-and-fix` recipe" to append:

> The recipe's `findFile()` helper requires **absolute filesystem
> paths**, not project-relative paths. `git diff --name-only` returns
> project-relative paths — prefix each with the repo root (e.g.,
> `repo_root + "/" + relPath`) before passing to `findFile()`, or use
> `LocalFileSystem.getInstance().findFileByPath(absolutePath)` directly.
> A silent `[skipped: not found]` output means the path was relative,
> not that the file is missing.

### Edit 2 — Update the recipe template at `mcp-steroid://ide/inspect-and-fix`

The recipe template in `mcp-steroid://ide/inspect-and-fix` currently
shows:

```kotlin
val filePath = "/path/to/your/File.java" // TODO: Set your file path
val (psiFile, document) = readAction {
    val virtualFile = findFile(filePath) ?: return@readAction null to null
    ...
}
```

Change to:

```kotlin
// findFile() requires an ABSOLUTE path. For project-relative paths from
// `git diff --name-only`, prefix with the repo root or use:
//   LocalFileSystem.getInstance().findFileByPath(absolutePath)
val filePath = "/absolute/path/to/your/File.java" // TODO: Set your absolute file path
val (psiFile, document) = readAction {
    val virtualFile = findFile(filePath) ?: return@readAction null to null
    ...
}
```

Edit 2 is upstream (in the mcp-steroid plugin repo), so it may not be
directly editable from this project. Edit 1 alone is sufficient to
unblock future agents from within this repo.

## Acceptance criteria

- `.claude/workflow/track-code-review.md` §"Pre-PR semantic pass" carries
  the absolute-path note (and/or a short Kotlin snippet that prepends
  the repo root or uses `LocalFileSystem.findFileByPath`).
- Optionally, `.claude/docs/mcp-steroid/recipes.md` §"IntelliJ
  inspections on changed code" cross-references the path-resolution
  caveat.
- A grep for `findFile\(` in the workflow + project docs returns at
  least one site that documents the absolute-path requirement
  alongside the `git diff --name-only` flow.
- A repro test: a Phase C agent following the docs verbatim on the
  next ADR successfully runs the inspect-and-fix recipe on the first
  attempt (no `[skipped: not found]` rediscovery).
