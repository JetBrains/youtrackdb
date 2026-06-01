# Design mutation log — YTDB-1039

Append-only log of every `design.md` mutation. Read by `edit-design`'s
`design-sync` step to find the last sync point. Not stamped (see
`conventions.md` §1.6(f)).

## Mutation 1 — 2026-05-31 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` for the two-axis open() speedup. Overview,
Core Concepts (orphan page, orphan pass, dirty gate, no-op shrink), Class Design
and Workflow diagrams, and four content sections: why the dirty gate is safe,
crash recovery and the orphan lifecycle, read-cache purge skip, and the
relationship to the read-cache-concurrency-bug ADR. Single file (no mechanics
companion — 8 sections, well under the length trigger).

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: whole-doc): not run — design.md seeded directly via
`/create-plan` planning-transition; the autonomous Phase 2 consistency +
structural review at `/execute-tracks` startup provides the whole-doc review.

**Findings**: none

**Iterations**: 1 of 3 (PASS)

## Mutation 2 — 2026-06-01 — content-edit (design.md)

**Diff summary**: Appended a bullet to §"Why the dirty gate is safe" → "### Edge
cases / Gotchas" recording the extend-on-read threat-model finding from
research-phase PSI verification. The crash-only orphan premise also rests on
reads never extending a file: `WOWCache.loadOrAdd` (reached via
`LockFreeReadCache.doLoad`) does extend for `pageIndex >= currentSize`, but every
production read is bounded below the file extent (logical horizon via
`getLastPage`/entry point, physical size for EP-less components, or a stored page
pointer), so a correct component never triggers it, and the residual out-of-range
case is itself crash-only and WAL-repaired. Makes the section TL;DR's implicit
"reads never extend" assumption explicit and discharges it. Backed by a
64-call-site sweep of `loadPageForRead` callers (no read-until-null EOF scan).

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: bounded): PASS (1 suggestion)

**Findings**:
- suggestion: the new bullet runs ~11 lines vs 2-4 for sibling bullets; within
  the template-bound exemption for a load-bearing safety argument (not a rule
  violation). Left as-is — density is appropriate for the argument.

**Iterations**: 1 of 3 (PASS)
