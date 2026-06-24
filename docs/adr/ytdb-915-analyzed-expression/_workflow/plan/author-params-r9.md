# design-author params — phase1-creation, round 9 (final targeted nit cleanup)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 9

## Scope (read this first)

Final prose-nit cleanup. **Do NOT change decision content** (D1–D14, D5-R),
diagrams, code identifiers, or invariants. **Preserve the line-1
`<!-- workflow-sha: ... -->` stamp exactly.** Edit in place with `Edit`. Locate
each passage by its verbatim quote. None needs new code grounding. Fix exactly
the five passages below — do NOT touch anything in the "Do NOT touch" list.

## flagged_passages

1. **Read-twice field-walk sentence (~lines 220-222, Workflow section).** "The
   lowerer's field-walk — it visits each recognized field of the AST node and
   throws on any field outside the S0 subset — is exhaustive-or-throw (Part 2), so a
   covered fragment lowers fully and an out-of-subset shape throws rather than
   mis-reading." — the inline em-dash gloss splits the subject from its verb and
   folds four ideas. Split into separate sentences: define the field-walk first as
   its own sentence ("The lowerer's field-walk visits each recognized field of the
   AST node and throws on any field outside the S0 subset."), then state the
   exhaustive-or-throw label and its consequence ("It is exhaustive-or-throw (Part
   2): a covered fragment lowers fully, and an out-of-subset shape throws rather than
   mis-reading.").

2. **Dropped relative pronoun in the `Cast` parenthetical (~lines 273-274, Sealed
   IR section).** "A dedicated `Cast` variant would have to carry a target-type tag
   (the `INTEGER` / `DATE` / … a `CAST` would name) that no S0 lowering or evaluator
   path would read, so it would be a variant with no consumer." — the parenthetical
   drops the relative pronoun, forcing a re-read. Restore it and detach the example,
   e.g. "carry a target-type tag — the type a `CAST` would name, e.g. `INTEGER` or
   `DATE` — that no S0 lowering or evaluator path would read, so it would be a
   variant with no consumer."

3. **Uncommon verb "classing" (~line 484, Parenthesis section).** "classing every
   `ParenthesisExpression` as a throw-case would make round-trip parity (I1)
   unsatisfiable on it" — replace "classing … as" with the common word, e.g.
   "treating every `ParenthesisExpression` as a throw-case …".

4. **12-constant enum list plus trailing fact in one sentence (~lines 586-589,
   NumericOps section).** "`SQLMathExpression.Operator` is an inner enum with 12
   constants — `STAR`, `SLASH`, `REM`, `PLUS`, `MINUS`, three shifts, `BIT_AND`,
   `XOR`, `BIT_OR`, `NULL_COALESCING` — and the numeric argument on each is its
   precedence priority." — split into two sentences: one naming the 12 constants,
   one stating that each constant's numeric argument is its precedence priority.

5. **Stacked narrow-alternative sentence (~lines 598-602, NumericOps section).**
   "The narrow alternative — extract only the `+ - * /` paths — leaves the shared
   widening helper split across `NumericOps` and `Operator`, an unclean seam,
   because the other eight operators invoke the same widening." — split the cause
   out, e.g. "The narrow alternative extracts only the `+ - * /` paths. The other
   eight operators invoke the same widening, so the shared widening helper would be
   split across `NumericOps` and `Operator` — an unclean seam."

## Do NOT touch (calibrated holds — leave exactly as written)

- The "second nuance: session threading" paragraph in the Comparison section
  (the EQ/NE session reasoning, ~lines 667-685) — held expert prose; do not
  restructure or split it.
- The `currentResult.asEntity()` → `schemaClass.getProperty(...)` →
  `property.getCollate()` collation call chain — leave it as-is.
- The `Result` vs `Identifiable` collation-overload reasoning — leave as-is.
- All worked interleavings / sequence traces, decision content, Mermaid diagrams,
  code identifiers, and invariants.
