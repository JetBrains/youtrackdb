# design-author params — phase1-creation, round 3 (revise flagged passages)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/ir-filter-step-expand-step/docs/adr/ir-filter-step-expand-step/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/ir-filter-step-expand-step/docs/adr/ir-filter-step-expand-step/_workflow/research-log.md
- design_mechanics_path: null
- round: 3

## Instructions

The draft on disk is structurally sound (mechanical + absorption both PASS). The cold auditor
flagged 6 remaining prose passages — several are density introduced by round-2's own gloss/split
fixes. Revise **only these passages** in place; leave everything else byte-identical, including
the line-1 stamp, the H1, and all D-records. Edit by matching the quoted phrase, not absolute line.
These are all prose reshaping (split a sentence, turn a chained clause into a bullet list, add one
discriminating clause). Re-ground via PSI only for FP-C below if you need the mechanism detail.

### Flagged passages

**FP-A — Overview ~8-11 (hard-to-read, § Plain language).** "This design makes them the first
executor steps to filter over the immutable analyzed-expression IR (`AnalyzedExpr`) instead — the
data-only expression tree the query engine's separation-of-concerns umbrella (YTDB-901) is moving
all evaluation onto." The trailing em-dash appositive drops its relative pronoun and strands "onto".
Split into two sentences with an explicit relative pronoun, e.g.: "…to filter over the immutable
analyzed-expression IR (`AnalyzedExpr`) instead. `AnalyzedExpr` is the data-only expression tree
onto which the query engine's separation-of-concerns umbrella (YTDB-901) is moving all evaluation."

**FP-B — Class Design "analyzed evaluator's new arms" ~233-236 (over-dense, § Orientation / §
Mechanism traces).** "It keeps its S0 seams unchanged — arithmetic through the shared numeric
engine, comparison through freshly reconstructed `SQLBinaryCompareOperator` instances with the same
collate derivation the `Result` path applies." Break the two seams (arithmetic / comparison) onto
separate clauses or a short list, and gloss "collate derivation" and "the `Result` path" in one
clause at first use — or point to § "Collation parity" which explains it — so the reader isn't left
to reconstruct what derivation is copied from where.

**FP-C — "analyzed evaluator's new arms" ~238-245 [re-ground if needed] (too-terse, § Orientation).**
The `Param`-no-coercion argument asserts "a scalar comparison never takes it" (the
`bindFromInputParams` path) without the discriminating condition. Add the one missing link: name
what makes the `bindFromInputParams` sub-expression path distinct — it fires only when a resolved
parameter must be re-parsed as a sub-expression, which a direct scalar-comparison operand never
triggers — so the "never takes it" claim follows from the text.

**FP-D — Workflow "Serialization bridge" ~408-411 (hard-to-read, § Plain language).** "The plan
should prefer a lean `String` source form (`whereClause.toString()`, already what `prettyPrint`
reads) over retaining a live `SQLWhereClause` AST node, re-parsing on deserialize; structured
`SQLWhereClause.serialize` retention is the fallback only if a text round-trip loses fidelity the
test needs." Two recommendations in one semicolon-joined sentence. Split into two: (1) prefer the
lean string form (with the `toString()`/`prettyPrint` aside), re-parsed on deserialize; (2)
structured `SQLWhereClause.serialize` retention is the fallback used only when a text round-trip
loses needed fidelity.

**FP-E — Complex topics "In-place comparison fast path" ~480-484 (over-dense, § Mechanism traces).**
The "guarded on three conditions that must all hold: …" sentence chains all three conditions plus a
nested em-dash gloss of "early-calculated constant" inside condition 2. Turn it into a short
bulleted (or numbered) list — one condition per line — with the "early-calculated constant" gloss on
its own line under the right-operand condition.

**FP-F — Complex topics "Bind-parameter lowering" ~547-550 (hard-to-read, § Plain language).** "The
magnitude, from a research-time survey of the LDBC SF1 query set (…): ~37 of 64 … , 4 of the 9 … ,
and folding bind params in moves the … fraction from ~1/9 to ~5/9." The lead is a verbless fragment
before a comma-chained statistics dump. Give the lead a verb (e.g. "A research-time survey of the
LDBC SF1 query set … puts the magnitude at:") and present the three data points as a short list, one
per line.

## Reminders

- Do not touch D-record substance. Preserve line-1 stamp and H1. House-style AI-tell subset.
- Return ONLY a thin summary — never the drafted content.
