# design-author params — phase1-creation, round 7 (dual-clean inner loop, readability findings)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 7

## Scope (read this first)

Prose / presentation polish round. **Do NOT change decision content** (D1–D14,
D5-R), diagrams, code identifiers, or invariants. **Preserve the line-1
`<!-- workflow-sha: ... -->` stamp exactly.** Edit in place with `Edit`. Locate
each passage by its verbatim quote, not its line number (numbers drift as you
edit). None of these needs new code grounding except (2), where you may read the
design's own `## Comparison: replicate the AST sequence` section (~lines 628-696,
already in the document) to name the concrete behavior.

## flagged_passages

1. **Unglossed coined term `field-walk` at first prose use (~line 215, Workflow
   section).** "the lowerer's field-walk is exhaustive-or-throw (Part 2), so a
   covered fragment lowers fully and an out-of-subset shape throws rather than
   mis-reading." — `field-walk` is used here before it is defined (its section is
   Part 2, ~line 406). Add a one-clause in-place gloss at this first prose use, e.g.
   "the lowerer's field-walk — it visits each recognized field of the AST node and
   throws on any field outside the S0 subset — is exhaustive-or-throw (Part 2) …".
   Keep the forward pointer to Part 2.

2. **Folded sentence + passive + vague "session-threading nuances" (~lines
   212-214, Workflow section).** "The comparison delegates to the parser's own
   `SQLBinaryCompareOperator` instance — the same operator object the AST holds — so
   collation and session-threading nuances are reproduced by construction (Part 3,
   §\"Comparison: replicate the AST sequence\")." — split into two sentences, name
   the actor, drop the passive "are reproduced by construction", and replace the
   vague "session-threading nuances" with the concrete behavior (from the Comparison
   section: the EQ branch passes the real session and NE passes a null session, which
   changes how type coercion resolves). For example: "The evaluator calls the AST's
   own `SQLBinaryCompareOperator` instance for the comparison. Because it is the same
   operator object, the comparison inherits the AST's collation and its EQ/NE session
   handling unchanged (Part 3, §\"Comparison: replicate the AST sequence\")." For
   consistency, apply the same concrete wording to the matrix-summary prose "the two
   comparison rows pin the collation and session-threading nuances of D11." (~line
   760) — e.g. "pin the collation and the EQ/NE session difference of D11." Leave the
   terse matrix *cell* "NE null-session threading vs EQ (D11)" (~line 756) as-is — a
   table cell may stay terse.

3. **Subjectless list lead-in / dropped copula (~lines 433-435, Field-walk
   section).** "The leaf shapes the walk descends into, once it reaches
   `mathExpression` (`SQLBaseExpression extends SQLMathExpression`, fields `number`,
   `identifier`, `inputParam`, `string`, `modifier`):" — this trails into the bullet
   list with no main verb. Restore a subject and verb before the colon, e.g. "Once
   the walk reaches `mathExpression` (`SQLBaseExpression extends SQLMathExpression`,
   fields `number`, `identifier`, `inputParam`, `string`, `modifier`), it descends
   into these leaf shapes:".

4. **Negative-parallelism + stacked closer (~lines 539-541, Precedence-fold
   section).** "So the duplicated logic is a textbook precedence-climbing reduction
   (low risk), not the value engine, and the genuine drift surface (promotion) stays
   single-homed." — split into two sentences and drop the "is X … not Y" contrast,
   e.g. "So the duplicated logic is a textbook precedence-climbing reduction — low
   risk. The genuine drift surface, promotion, stays single-homed in `NumericOps`."

5. **API-member inventory crammed into one prose sentence (~lines 583-587,
   NumericOps section).** "The promotion engine all 12 share is: five abstract
   typed-pair `apply(...)` overloads (`Integer`, `Long`, `Float`, `Double`,
   `BigDecimal`), a fallback `apply(Object, Object)`, the shared widening entry
   `apply(Number a, Operator operation, Number b)` that widens by the right operand's
   runtime type, and a private static `toLong` helper." — convert the four members
   to a bulleted list (one bullet each: the five typed-pair `apply` overloads; the
   fallback `apply(Object, Object)`; the widening entry `apply(Number, Operator,
   Number)` with its "widens by the right operand's runtime type" note; the private
   static `toLong` helper). Lead the list with a short stem ("The promotion engine
   all 12 share has four parts:"). Presentation only — do not change the members.

6. **Idiom `lift-and-shift` (~line 625, NumericOps Decisions & invariants).**
   "D5-R (`NumericOps` extraction is whole-enum lift-and-shift)" — replace the
   cloud-migration idiom with the literal phrasing already used in the section, e.g.
   "D5-R (`NumericOps` extraction moves the whole enum out unchanged)".

7. **Idiom + metaphor + negative parallelism (~line 764, test-matrix Edge cases).**
   "The matrix is the floor, not the ceiling. Any covered fragment is fair game;
   these are the cases that pin a specific mechanism a naive implementation would get
   wrong." — state it literally, e.g. "The matrix is the minimum required set, not an
   exhaustive one. Any covered fragment may be tested; these rows pin a mechanism a
   naive implementation would get wrong."

8. **Dangling reference "the prior sketch" (~line 815, Track decomposition).**
   "T3 is the heaviest track — it owns parenthesis recursion and the precedence fold
   — so its scope indicator must say so; the prior sketch under-weighted it." —
   "the prior sketch" names an artifact the reader cannot see. Drop the dangling
   clause (keep the surviving main claim), e.g. "T3 is the heaviest track — it owns
   parenthesis recursion and the precedence fold — so its scope indicator must
   reflect that weight."

## Do NOT touch

- The worked interleavings / sequence traces (the `(1 + 2) * x` fold, the
  comparison interleaving) — these are model worked traces, held by design.
- Any decision content, Mermaid diagram, code identifier, or invariant.
