# design-author params — phase1-creation, round 4 (residual prose polish)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 4

## flagged_passages (prose polish only — do NOT change any decision content)

1. **[should-fix] One name per algorithm (~lines 511, 523, 539).** The AST's
   precedence reduction is named three ways: "shunting-yard fold", "precedence-climbing
   reduction", "precedence-plus-associativity nesting". Pick ONE name and reuse it
   throughout the precedence-fold section (the mechanism is fully pinned by the
   `Operator.getPriority()` + `<=` left-associative description and the worked
   interleaving, so the label is illustrative). Consistent naming protects the
   load-bearing "the lowerer runs the same reduction the AST runs" equivalence.

2. **[should-fix] Split the `Identifiable`/`Result` sentence (~lines 666-669).** The
   four-clause sentence ("This also reinforces the single-`Result`-overload choice (D3):
   the AST's `Identifiable` overload — the bare-record input form … — deliberately skips
   collation, so the IR must follow the `Result` overload, the collation-applying path
   the executor uses.") packs two appositive glosses plus a causal "so" into one
   sentence. Split into two: state that the `Identifiable` overload (with its gloss)
   skips collation, then the consequence — the IR follows the `Result` overload because
   that is the collation-applying path the executor uses. Keep the gloss.

3. **[suggestion] Gloss "bimorphic" / linearize (~lines 534-535).** "would inject a
   functional-interface call into the hot AST eval loop, bimorphic across two call sites
   and likely not inlined" — split the chain: the shared fold would be called with two
   different combiner lambdas (the AST's `apply`, lowering's `new BinaryOp`), so the call
   site sees two receiver types (bimorphic), which the JIT cannot collapse to one
   inlinable target.

4. **[suggestion] Trim Core-Concepts meta-description (~lines 42-44).** Drop the
   self-describing tail "each entry pairs the concept with what it replaces, so the delta
   from today's AST-only world is visible at a glance" — the entries already demonstrate
   it. Keep "Each is named here and used without re-definition in the Parts that follow."

5. **[suggestion] Break the `Cast` rejected-alternatives enumeration onto lines (~lines
   272-275).** The dashed mid-sentence "two alternatives — … — were rejected: the
   first…, the second…" is a spliced enumeration. Break the two rejected alternatives
   onto separate lines, each with its rejection attached.
