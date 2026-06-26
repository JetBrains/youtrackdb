# design-author params — phase1-creation, round 5 (budget-exhaustion wrap-up polish)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 5

## flagged_passages (final prose polish — do NOT change decision content)

1. **[should-fix] Gloss "monomorphic-dispatch grain" (~line 537).** "That runs directly
   against the codebase's monomorphic-dispatch grain (the same grain D1/D2 cite)" appeals
   to a codebase-wide convention the document never establishes. Gloss it as a defined
   property, e.g. "the codebase prefers monomorphic call sites the JIT can inline (the
   same preference D1/D2's static dispatch follows)".

2. **[should-fix] Derive the `Result`/`Identifiable` collation link (~lines 668-670).**
   "the IR must follow the `Result` overload, because that is the collation-applying path
   the executor uses" asserts the overload→collation link without deriving it. Add one
   link sentence: a `Result` carries the projection/property context that `getCollate`
   reads, while a bare `Identifiable` has none — which is why the `Identifiable` overload
   skips collation and the `Result` overload applies it.

3. **[suggestion] Name the cast taxonomy concretely (~lines 264-271).** "a cast-target
   type taxonomy that no S0 lowering or evaluator path would read" — replace the abstract
   phrase with the concrete thing, e.g. "a target-type tag (the `INTEGER`/`DATE`/… a
   `CAST` would name) that no S0 path reads".

4. **[suggestion] Soften the structural-sharing forward-reference (~lines 160-163).**
   In Class Design, "the structural-sharing recursion" names a mechanism not introduced
   until Part 1 §"Transform passes". Either make the forward pointer explicit ("the
   structural-sharing logic — Part 1 §Transform passes") or trim to "the recurse-and-
   rebuild logic" so it does not presuppose a mechanism defined two sections later.
