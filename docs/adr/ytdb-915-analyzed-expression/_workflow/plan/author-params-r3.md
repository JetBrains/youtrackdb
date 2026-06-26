# design-author params — phase1-creation, round 3 (re-ground flagged passages only)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 3

## flagged_passages

1. **[absorption — must fix, blocks dual-clean] Missing D5 placement D-record.**
   The design seeds D5-R (whole-enum extraction scope) but has no `D5` anchor. In the
   research log, D5-R supersedes only the *extraction-boundary (scope) half* of D5;
   D5's *placement* decision is still separately load-bearing. Add a `D5` seed D-record
   to the `NumericOps` section's `### Decisions & invariants` block (Part 3 §"NumericOps:
   one shared promotion engine"): D5 decides `NumericOps` lives at a neutral
   `core/.../sql/util/` location that both the AST package and the new `query/analyzed/`
   package can depend on. Rejected alternatives (from research-log.md `### D5`):
   duplicate the promotion logic in the IR evaluator (guarantees drift); place it inside
   `query/analyzed/` (forces a backward dependency from the AST package to a sibling);
   place it under `sql/method/` (that package already means typed method dispatch). State
   that D5-R supersedes only D5's scope half — the placement fork remains live. The
   section prose already covers this rationale; this just adds the traceable `D5` anchor.

2. **[readability should-fix] `Identifiable` unglossed in load-bearing D3 prose.**
   `Identifiable` (~lines 654-655 and 682) is the pivot of the D3 single-overload
   justification ("the AST's `Identifiable` overload deliberately skips collation, so the
   IR must follow the `Result` overload") but is never glossed, and the Overview's
   prerequisite sentence (~line 34) names only `Result`/`CommandContext`. Gloss
   `Identifiable` in one clause at first load-bearing use (e.g. "the AST's `Identifiable`
   overload — the bare-record input form that carries no projection/collation context —
   deliberately skips collation…"), or add `Identifiable` to the Overview prerequisite
   sentence alongside `Result`.

3. **[readability suggestion] Linearize the `PropertyTypeInternal.convert` chain.**
   ~lines 650-652: "The session changes `PropertyTypeInternal.convert` behavior for
   type-coercing comparisons, so calling one shared `equals(session, ...)` for both `=`
   and `!=` drifts from the AST on `!=`." Linearize one link per sentence: what `convert`
   does (cross-type coercion before the compare), that it consults the session, that a
   `null` session takes a different (no-coercion) branch, hence feeding `null` for both
   operators drifts on `!=`.
