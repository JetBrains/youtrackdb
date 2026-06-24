# design-author params — phase1-creation, round 6 (sliced-auditor prose-nit fix)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 6

## Scope (read this first)

Prose-only polish round. **Do NOT change decision content** (D1–D14, D5-R),
diagrams, code identifiers, invariants, or section structure. **Preserve the
line-1 `<!-- workflow-sha: ... -->` stamp exactly** — edit prose below it, never
the stamp line. These are word-choice / grammar fixes; none needs a worked
example, so no new code grounding is required. Edit in place with `Edit`.
Verify line numbers before editing (they may drift as you apply earlier edits) —
locate each by its verbatim quote, not its line number.

## flagged_passages

1. **Passive/subjectless lead — Overview (~line 25).** "Four pieces are built to
   fit the substrate: a visitor/transform framework …" — give it a subject and an
   active verb (e.g. "S0 builds four pieces over the sealed type: …" or "The
   substrate carries four pieces: …"). Keep the four-item list and the
   round-trip-parity sentence that follows unchanged.

2. **Subjectless concept-glossary leads — Core Concepts (~lines 47-49, 55-56,
   61-63, 67-70, 76-77).** Each of the six bolded concepts ends with a subjectless
   "Replaces …" or "Extracted whole from …" fragment ("Replaces the
   abstract-class-plus-subclasses idiom…", "Replaces the classic Visitor pattern's
   per-node virtual `accept` call.", "Replaces nothing in the AST…", "Extracted
   whole from `SQLMathExpression.Operator`…", "Replaces nothing — the AST keeps its
   own fold untouched."). Give each an explicit subject so it reads as a complete
   sentence, applied **consistently across all six bullets** for parallelism (e.g.
   "It replaces …" / "It is extracted whole from …"). Do not change *which* thing
   each replaces or extracts.

3. **Read-twice sentence — Overview (~lines 20-23).** "Sealing lets the compiler
   enforce an exhaustive `switch` over the variant set, so dispatch needs no
   `accept(visitor)` method on the nodes and each visitor call stays a direct
   (monomorphic) call after one central switch resolves." — the tail clause "after
   one central switch resolves" restates the "exhaustive `switch`" named at the head
   of the same sentence. Split into two short sentences (one for "sealing enforces
   an exhaustive switch, so nodes need no `accept` method"; one for "each visitor
   call is then a direct (monomorphic) call") and drop the redundant tail
   restatement. Keep the "(monomorphic)" gloss.

4. **Unglossed domain term — Part 1 (~lines 248-250).** "S10 will replace `Var`
   with range-table-resolved references, which is why S0 does not bake in a
   resolution model." — "range-table-resolved references" is used with no gloss. Add
   a one-clause in-place gloss (e.g. "range-table-resolved references (names bound to
   their `FROM`-clause source)"). Do not delete the term or change the S10/S0
   rationale.

5. **Coined hyphen-compound predicate — Part 2 (~line 423).** "The walk does not
   enumerate-and-assume-complete." — replace the coined compound with a plain clause
   (e.g. "The walk does not assume the recognized fields are the complete set."). The
   next sentence ("It dispatches on the recognized in-subset fields and throws on
   everything else as the default.") already states the mechanism plainly; keep it.

6. **Metaphor — Part 2 (~line 425).** "the field inventory is a moving target" →
   literal phrasing (e.g. "the field set is not fixed across parser changes" / "the
   field set changes as the parser evolves").

7. **Referential drift / elegant variation — Part 2 (~lines 416-428, also 458).**
   The prose names "The current field set (confirmed via PSI on develop)" (~line
   416), then "the original inventory" (~line 427), then "the inventory" (~line 458)
   for what reads as the same artifact. Use **one consistent name** for the field
   set and state explicitly which list was incomplete (e.g. "were missing from the
   field set inventoried above"). Do not change the soundness argument
   ("Asserting field-walk completeness over an incomplete inventory would be
   unsound.").

8. **Idiom — Part 3 (~line 536).** "That cuts against the codebase's preference for
   monomorphic call sites the JIT can inline" → replace "cuts against" with
   "conflicts with" or "works against". Leave the rest of the sentence intact.

9. **Clipped head noun / subject-verb agreement — Part 3 (~line 539).** "Every
   value semantic — null sentinel, numeric promotion, `Date + Long`, `String` concat
   — comes from the shared `NumericOps` …" → "All value semantics — null sentinel,
   numeric promotion, `Date + Long`, `String` concat — come from the shared
   `NumericOps` …" (plural head noun + verb agreement). Keep the en-dash list.

10. **Metaphor — Part 3 (~line 610).** "only the larger AST-side blast radius is the
    cost" → literal phrasing (e.g. "the only cost is the larger set of AST-side call
    sites the extraction touches").

## Do NOT touch (held by design — dense-but-followable expert prose)

- The worked interleavings / sequence traces.
- The `NumericOps` member inventory sentence (~lines 584-588) — a structural
  inventory the SQL-layer maintainer can parse; do not restructure to bullets.
- The seams summary line (~line 803) and the precedence-fold rationale.
- The `suffix path` collation call chain (~line 669).
- The `session-threading` summary phrasing (~line 214, matrix ~line 756, prose
  ~line 761) — the mechanism is glossed in the Comparison section (~lines 662-664),
  which precedes both uses on a linear read. Leave these.
