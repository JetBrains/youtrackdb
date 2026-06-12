# Design Mutations — explanation-style

Append-only log of every `design.md` mutation: diff summary, mechanical-check
result, cold-read verdict, iteration count. Unstamped per `conventions.md §1.6(f)`.

## Mutation 1 — 2026-06-12 — phase1-creation (design.md)

**Diff summary.** Seeded `design.md` (single file, no mechanics companion — 8
top-level sections, well under the length trigger). Overview + Core Concepts
(five terms) + Enforcement surface map + How a prose finding is produced + four
topic sections (the Orientation rule, Over-dense prose enforcement, Subset sync,
the §1.7 opt-out). Seed D-records D1–D6 from the frozen research log, each in its
owning section's `### Decisions & invariants` footer with a `Full design:`
pointer back to the log.

**Mechanical-check result.** `design-mechanical-checks.py --target design --scope
whole-doc` → PASS (0 findings).

**Cold-read verdict.** PASS. Mental-model verdict YES; absorption complete (all
D1–D6 seeded, no invented decisions). One should-fix (a `(D5)` label on the regex
additions read as a standalone decision while log-D5's headline is staging) —
**applied**: relabeled the regexes as YTDB-1084 scope with D5's A9 clause
governing only their severity. One suggestion (footer named
`### Decisions & invariants` vs house-style's `§ References footer shape`) —
**ratified**: design-document-rules D11 renames that footer for `design.md`
seeds, so the name is correct; the reviewer flagged it only because its reading
rules exclude design-document-rules.md.

**Iteration count.** 1 (cold-read passed on the first pass; the should-fix was a
post-PASS clarity fix, not an iterate-to-PASS loop).
