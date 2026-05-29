# Ephemeral Identifier Rule — Don't Leak Workflow IDs into Durable Content

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §When to load this file | implementer,orchestrator,final-designer | 3B,3C,4 | Read before authoring durable content (code, tests, Javadoc, final artifacts, PR title/body). |
| §Where the rule applies | implementer,final-designer | 3B,3C,4 | Source comments, PR title/body, the two Phase 4 final artifacts, and tests; branch-only commit messages are exempt. |
| §Forbidden | implementer,final-designer | 3B,3C,4 | Forbidden labels: Track/Step/compound labels, finding IDs, iteration counters, named invariants, un-restated DR IDs. |
| §Allowed (these survive in git or are self-contained) | implementer,final-designer | 3B,3C,4 | Allowed references: file paths, class/method/field names, commit SHAs, ADR-defined DR IDs, issue tracker IDs. |
| §How to rewrite a forbidden reference | implementer,final-designer | 3B,3C,4 | Replace an ephemeral label with prose, the modified file/class, or the implementing commit SHA; worked examples follow. |
| §Self-check before commit | implementer | 3B,3C | The hard pre-commit grep gate over staged additions outside _workflow and .claude/workflow. |

<!--Document index end-->

**Authoritative statement.** This is the single source of truth for the
rule; every phase-specific prompt that touches durable content points
here.

`implementation-plan.md`, `plan/track-N.md`, `design.md`,
`design-mechanics.md`, and `design-mutations.md` live under
`docs/adr/<dir-name>/_workflow/`. They are tracked on the branch during
development, but the entire `_workflow/` directory is removed in the
Phase 4 cleanup commit before the PR is merged — so any identifier
that lives only in those files becomes a dangling reference the moment
`develop` swallows the squash. The same applies to review-loop counters
and named invariants that live only in the plan. Therefore, **anything
that survives merge into `develop` must not cite those identifiers**.

## When to load this file
<!-- roles=implementer,orchestrator,final-designer phases=3B,3C,4 summary="Read before authoring durable content (code, tests, Javadoc, final artifacts, PR title/body)." -->

Read this file when about to author durable content:

- Implementer is about to write source code, tests, or Javadoc that
  will be committed (see
  implementer-rules.md:implementer:3B,3C).
- Phase C orchestrator is authoring a `Review fix:` commit's code
  changes (see track-code-review.md:orchestrator:3C).
- Phase 4 is composing `design-final.md`, `adr.md`, or the PR title
  and body (see
  prompts/create-final-design.md:final-designer:4).

The §"Self-check before commit" pre-commit gate below is mandatory
for the implementer sub-agent on every spawn and required by hand
for ad-hoc commits outside `/execute-tracks`. If the grep returns
zero matches on staged files, the full rule below is not needed
for that commit.

## Where the rule applies
<!-- roles=implementer,final-designer phases=3B,3C,4 summary="Source comments, PR title/body, the two Phase 4 final artifacts, and tests; branch-only commit messages are exempt." -->

- **Source code comments and Javadoc** — the most common leak. Comments
  like `// added per Track 2 Step 1`, `// fixes CQ33`, or `// see
  Single-authority invariant` tie the code to files that no longer
  exist after the cleanup commit.
- **PR titles and descriptions** — stored in GitHub, used as the body
  of the squashed merge commit (per CLAUDE.md § Git Conventions).
- **`design-final.md`** and **`adr.md`** — the only workflow files
  that survive merge into `develop`; see
  prompts/create-final-design.md:final-designer:4
  for Phase-4-specific examples.
- **Tests, test names, and test descriptions** — committed alongside
  the code.

**Branch-only commit messages are exempt.** Individual commit messages
on the development branch (Phase A/B/C session commits, step commits,
review-fix commits, episode commits, workflow-file commits) may freely
cite Track / Step / finding labels — they are squashed away on merge,
the squashed message is assembled from the PR title and body (not from
the individual commit messages), and the underlying workflow files are
present in the branch tree at the time those commits are made. The
forbidden list above is what lives durably on `develop`.

It does **not** apply to the working files themselves: track files
(`plan/track-N.md`) and the plan all cite tracks, steps, findings,
and iterations freely — that's exactly what those identifiers are
for inside the workflow. Review findings themselves no longer have a
separate on-disk home (they ride in the orchestrator's conversation
context for the iteration loop), so the rule against citing finding
IDs in durable content remains, just without a working file to cite
from.

## Forbidden
<!-- roles=implementer,final-designer phases=3B,3C,4 summary="Forbidden labels: Track/Step/compound labels, finding IDs, iteration counters, named invariants, un-restated DR IDs." -->

- Track labels: `Track 1`, `Track N`, `Track N: <title>`
- Step labels: `Step 1`, `Step N`, `Step M of Track N`
- Compound labels: `Track 2 Step 1`, `Track 4 iteration 1`
- Review finding IDs and prefixes: `CQ33`, `F-12`, `R-4`, `A-7`,
  `S-2`, or any other `<PREFIX><number>` finding label produced by
  the workflow's review loops
- Review-loop iteration counters: `iteration 1`, `round 2`, when they
  refer to ephemeral review loops
- Named invariants or rule names cited **by label only** —
  "Single-authority invariant", "Load-bearing-file rule",
  "Byte-identity discipline", etc. If the rule matters for a future
  reader of committed content, either restate it in prose or cross-
  reference the stable `.claude/workflow/` location that defines it
  (e.g. `conventions.md` `§1.2`, `conventions-execution.md` `§2.1`).
- Plan-file Decision Record IDs (`D1`, `D2`, …) that are **not**
  restated in `adr.md`. IDs that ARE restated in `adr.md` are stable
  (the ADR owns them post-implementation) and may be cited.

## Allowed (these survive in git or are self-contained)
<!-- roles=implementer,final-designer phases=3B,3C,4 summary="Allowed references: file paths, class/method/field names, commit SHAs, ADR-defined DR IDs, issue tracker IDs." -->

- File paths under the project — source files, workflow docs in
  `.claude/workflow/`, committed artifacts
- Class, interface, method, and field names
- Commit SHAs — the stable way to point at "where this was implemented"
- `adr.md`-defined Decision Record IDs, when `adr.md` itself is the
  reader's reachable context
- Issue tracker IDs (e.g. `YTDB-123`) — these survive in the tracker

## How to rewrite a forbidden reference
<!-- roles=implementer,final-designer phases=3B,3C,4 summary="Replace an ephemeral label with prose, the modified file/class, or the implementing commit SHA; worked examples follow." -->

Replace the ephemeral label with (a) a prose description of what was
done, (b) the file/class that was modified, or (c) the commit SHA that
implemented it.

Examples:

- ❌ `// added during Track 2 Step 1 to unblock Phase A resume`
- ✅ `// part of the description-amendment + clarifications-append ordering that keeps Phase A pre-flight resume idempotent`

- ❌ `Review fix: address CQ72 (anchor drift in slim rendering)`
- ✅ `Review fix: restore byte-identical phrasing at the two cross-reference sites in plan-slim-rendering.md`

- ❌ `// see Track 4 iteration 1 for context`
- ✅ `// see commit abc1234 for the follow-up that restored these sites; the first pass missed two of them`

When in doubt: if a reader on `main` (without the branch, without the
plan) couldn't resolve the reference, the reference is forbidden.

## Self-check before commit
<!-- roles=implementer phases=3B,3C summary="The hard pre-commit grep gate over staged additions outside _workflow and .claude/workflow." -->

The implementer sub-agent runs this as a **hard pre-commit gate**.
See implementer-rules.md:implementer:3B,3C sub-step 3
§"Pre-commit gate, ephemeral-identifier check" (and its mirror in
commit-conventions.md:orchestrator,implementer:3A,3B,3C §"Ephemeral-identifier
pre-commit gate"). For ad-hoc commits outside the workflow, run the
same grep on staged files yourself. It applies to code, tests, and
the two Phase 4 artifacts (NOT to commits whose staged paths are
entirely under `_workflow/` or `.claude/workflow/` — both directories
hold workflow machinery whose rule examples and working files cite
labels by intent):

```bash
git diff --cached -- ':(exclude)docs/adr/*/_workflow/**' ':(exclude).claude/workflow/**' | grep -nE '^\+.*\b(Track|Step)[ ]?[0-9]+|^\+.*\b[A-Z]{1,3}-?[0-9]+\b'
```

The `^\+`-anchored form narrows to additions so the gate stays fast
on large refactor diffs by ignoring context lines. The optional `-?`
catches the hyphenated finding-ID shapes (`F-12`, `R-4`, `A-7`,
`S-2`) listed in §Forbidden alongside the unhyphenated ones
(`CQ33`). Anything this catches is either a genuine leak to rewrite
(per §"How to rewrite a forbidden reference" above) or an allowed
exception (e.g. issue tracker IDs, class names that happen to match
the pattern). Inspect, then proceed.
