# design-author params — Step 4b track authoring, round 3 (final inner-loop round)

## Inputs
- target: tracks
- output_path / plan_dir: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- design_path (frozen seed): /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- round: 3

## What changed since round 2
Absorption is still clean (decision coverage complete — do not touch Decision Record
content, the track split, or the D-record assignment). Round 2 cut the round-1 findings
from 25 to 17; the residuals below remain. This is the **final** inner-loop round — fix
every passage below. They are all cheap (glosses, sentence splits, one confusing notation,
two name-drops to cut). Preserve each file's line-1 stamp and the H1 on line 2.

Most fixes are: gloss a term at first use (`golden tests`, `I1`, `FALLBACK`,
`method-call coercion`, "the slow comparison sequence"), split a long sentence into one
idea each, or cut an unmotivated external name-drop. Re-ground through PSI only if a fix
needs a code fact you do not already have.

## flagged_passages

### track-1.md
- **F1 (line ~48, glossary):** gloss `golden tests` at first use, e.g. "golden tests (tests
  that assert a node tree equals a recorded reference tree)".
- **F2 (lines ~160-163, too-terse — CUT the name-drop):** the shared visitor shape is now
  stated ("a visitor that returns a rewritten node and shares unchanged subtrees by
  reference"). The trailing "the same shape as Calcite's `RexShuttle`, Spark's
  `TreeNode.transform`, and ANTLR's `*BaseVisitor`" adds nothing a reader can reconstruct —
  **delete** the external name-drop, keep the stated shape.
- **F3 (lines ~67-69, explanatory register):** "the JIT lowers a sealed `switch` (no
  `default`) to a table jump" is asserted. Add the link: the sealed permits-list is a
  closed, known set of types, which lets the JIT emit a dense dispatch table rather than a
  chain of type tests.
- **F4 (lines ~104-108, plain language):** `Var(rtable_index, attnum)` uses two unexpanded
  abbreviations. Name the fields in plain words — e.g. `Var(range-table index, column
  number)` — or expand `rtable_index`/`attnum` in a half-clause.
- **F5 (lines ~120-131, glossary):** the bare label `I2` points to a definition not in this
  track (only `I3` is stated here). Either drop the bare `I2` label and rely on the inline
  gloss already present, or mark it forward-defined ("invariant I2, owned and stated in
  Track 3").

### track-2.md
- **F1 (line ~158, hard-to-read):** `apply(Number, BinaryOperator-or-Operator, Number)` — the
  hyphenated alternation in a code span reads as a contradiction against the `apply(Number,
  Operator, Number)` form used everywhere else (lines ~70, ~96, ~227). Write it as
  `apply(Number, Operator, Number)` to match; if the type is genuinely the open T2 choice,
  state that in plain prose, not inside the code span.
- **F2 (lines ~67-71, over-dense):** the "four parts:" engine inventory is a four-item
  semicolon chain in one sentence. Break it into a numbered or bulleted list under the
  "four parts:" lead, each part (and its aside) on its own line. The readable flowing form
  already exists at ~137-141 to mirror.
- **F3 (lines ~111-112, hard-to-read):** "S0's acceptance gate stays the existing math-test
  suite (correctness) green after the delegation" is tangled (the "(correctness)" aside
  wedged between noun and "green"). Split: "S0's acceptance gate is the existing math-test
  suite staying green after the delegation; that gate covers correctness."

### track-3.md
- **F1 (lines ~38, ~78, glossary — load-bearing):** `I1` is the load-bearing reason the
  precedence fold matters but is never glossed in this track (sibling `I2` is). Add `I1` to
  `## Invariants & Constraints` with a one-line gloss ("I1 — round-trip parity:
  lower-then-evaluate yields the same value as evaluating the AST directly"), or gloss it
  inline at its first use (~line 38).
- **F2 (lines ~126-127, glossary):** "the slow comparison sequence" is dropped with no gloss
  and is a Track-4 detail. Gloss it in one clause (the AST's per-row comparison path the IR
  evaluator replicates) or drop the qualifier and say the comparison evaluator does not
  reproduce property-iteration.
- **F3 (lines ~56-59, too-terse):** "bimorphic across its two call sites … unlikely to inline
  … cuts against the codebase's monomorphic-dispatch grain (the same grain D1/D2 cite)"
  needs JIT-specialist vocabulary and an unresolvable cross-track ref. Add a one-clause plain
  gloss ("a shared lambda makes one call site see two implementations, which the JIT will not
  inline, unlike the single-implementation call sites the codebase favors") and drop the
  "D1/D2 cite" cross-reference (not resolvable from this track).
- **F4 (lines ~266-269, plain language):** the sentence chains three ideas (this track's test
  asserts nesting shape / Track 4 asserts value / "which backs D10/D12") across an em-dash and
  semicolon. Split into two sentences — what this track's test asserts (IR-tree shape + throw
  behavior), then that the value assertion is Track 4's round-trip matrix.

### track-4.md
- **F1 (lines ~272-273, too-terse):** "`visitFuncCall` evaluates the method-call coercion" is
  a bare assertion. Add a one-clause gloss of what `FuncCall` carries and what the coercion
  produces (e.g. "evaluates the wrapped method call, coercing the result to the column type"),
  matching the self-contained `visitVar`/`visitConst` clauses beside it.
- **F2 (lines ~91-95, ~150, glossary):** gloss `FALLBACK` once at first use — the sentinel
  `tryInPlaceComparison` returns to signal "could not decide; defer to the slow path" — then
  the recurrence reads cleanly.
- **F3 (lines ~18-23, hard-to-read):** one subject governs two parenthetical-laden predicates.
  Split into two sentences, one mechanism each ("It has two load-bearing mechanisms. First,
  arithmetic delegates to the shared `NumericOps`, so AST/IR arithmetic cannot drift. Second,
  the comparison path replicates the AST's exact sequence, so parity is structural — the IR
  runs the same code the AST runs").
- **F4 (line ~344, glossary):** "the Part 4 matrix" uses design.md's sectioning label, which
  the track reader cannot resolve. Point at the in-file section: "asserting the matrix in
  § Validation and Acceptance (I1)", matching the resolved form already used at line ~291.
- **F5 (lines ~121-127, hard-to-read):** the ~12-callers sentence runs the subject through a
  mid-clause em-dash aside and a colon expansion before the consequence. Split: state the
  caller count and the two named callers in one sentence, then start a new sentence with "So
  the S1+ convergence is an observable behavior change: …" (the concrete `name = 'admin'`
  instance already follows).

## Stamp / return contract (unchanged)
Preserve each file's line-1 `<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->`
and the H1 on line 2. Touch only the flagged prose. Return only a thin summary per track —
never the drafted content.
