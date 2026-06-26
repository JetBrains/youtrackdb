# design-author params — Step 4b track authoring, round 2 (re-draft flagged passages)

## Inputs
- target: tracks
- output_path / plan_dir: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- design_path (frozen seed): /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- round: 2

## What changed since round 1
The four track files are written and the absorption check passed (decision coverage is
complete — do not change any Decision Record content, the four-track split, or the
D-record assignment). The cold readability auditors flagged the passages below. Re-draft
**only** these passages in place, preserving each file's line-1 workflow-sha stamp and the
H1 on line 2. Re-ground through PSI only where a finding asks for a worked trace or a
mechanism explanation; the gloss/idiom/restatement fixes need no new code read.

Two recurring fixes apply across tracks:
1. **Gloss every YouTrackDB-specific term at first use** with a one-clause plain-language
   gloss (`Result`, `Identifiable`, `Collate`, `Var`, `SecurityEngine`, `S10`, the
   invariants `I2`, the "merge floor", `LDBC SNB` / `SF1` / "neutrality gate",
   `doCompare`-vs-0). Keep the term; add the gloss.
2. **Unfold over-dense inline enumerations and folded causal chains** — one idea per
   sentence; where a numbered/lettered call chain is crammed into one sentence, break it
   onto separate lines or point to the existing Mermaid diagram instead of re-inlining it.

## flagged_passages

### track-1.md
- **F1 (line ~106, glossary):** `S10` is used as a load-bearing actor ("S10 will replace
  `Var`…", "when S10 lands") but never glossed. Gloss at first use, e.g. "S10, the later
  identifier-resolution slice".
- **F2 (lines ~138-148, explanatory register):** the `transformChildren` worked trace folds
  the mechanism — "allocates only the new node plus the parent chain back to the root" is
  asserted. Walk the depth-10 example one ancestor at a time: each ancestor on the path sees
  one changed child and rebuilds; off-path subtrees return by reference. State the per-node
  rule, then the trace.
- **F3 (lines ~149-150, too-terse):** "same shape as Calcite's `RexShuttle`, Spark's
  `TreeNode.transform`, ANTLR's `*BaseVisitor`" is an unmotivated name-drop. Either state in
  one clause *what* the shared shape is (a visitor that returns a rewritten node and shares
  unchanged subtrees by reference) so the names illustrate a stated property, or cut it.
- **F4 (lines ~124-128, glossary):** invariant `I2` is named but not stated. State it in one
  clause at first use, the way `I3` is stated in the Invariants section.
- **F5 (lines ~5-24, over-dense / restatement padding):** the Purpose / Big Picture opens
  with three paragraphs that restate the same no-live-consumer scope three times. Fold to
  one: keep the variant list and the AST/IR gloss once, state the no-live-consumer scope
  once, drop the duplicate restatements.
- **F6 (lines ~43-50, hard-to-read):** "removes the megamorphic virtual-dispatch cost …
  stays monomorphic" asserts the payoff without the link. Add the missing link: name why
  `accept` on every node is megamorphic (one call site, many node types) before stating that
  resolving the `switch` first leaves each `visitX` call with a single receiver type.

### track-2.md
- **F1 (lines ~94-96, over-dense + mislabeled):** the dispatch chain `operator.apply` →
  `apply(Object,Object)` → `apply(Number,Operator,Number)` → `operation.apply(typed,typed)`
  is folded into one sentence and the "two-hop" label disagrees with the four arrows shown.
  Present it as a fenced trace / numbered lines, one call per line, and reconcile "two-hop"
  with the arrows (state which hops are the two virtual dispatches). This is the load-bearing
  D17 perf passage — make it verifiable from the text.
- **F2 (line ~108, hard-to-read):** "a relevance-blind perf claim rides until S1 measures it"
  — drop the coined "relevance-blind" and the metaphor "rides"; state it literally (the perf
  claim stays unverified until S1 measures it against a live consumer).
- **F3 (lines ~88-89, hard-to-read):** "a heavyweight Hetzner run … sooner than the change
  reaches a hot consumer" is a tangled verbless clause. State the reason directly: S1's gate
  already covers this low-risk change; an S0 run would benchmark before any hot consumer
  exists, so it measures nothing the S1 gate does not.
- **F4 (line ~77, hard-to-read):** "a larger **blast radius** on `SQLMathExpression`" — use
  the literal term ("touches more of `SQLMathExpression`"); the next clause already names the
  regression surface.
- **F5 (line ~61, idiom):** "Both were on the table at re-validation" — state literally
  ("Both were open options at re-validation").
- **F6 (lines ~5-24, over-dense / restatement):** the first and second paragraphs both state
  that the whole engine moves to `NumericOps` and `Operator.apply` becomes a delegator. Keep
  the first as the outcome; start the second with what it adds (the concrete enum lifted, the
  AST-side-only scope), dropping the restatement.

### track-3.md
- **F1 (lines ~57-60, hard-to-read):** replace "contra" with "against"; split the
  stacked clause — one sentence that the injected functional-interface call is bimorphic
  across two call sites and so unlikely to inline, a second that this cuts against the
  codebase's monomorphic-dispatch grain.
- **F2 (lines ~118-123, hard-to-read):** the `levelZero` sentence spans six lines with a
  multi-line parenthetical suspending subject and predicate. Split: first define the
  `levelZero` form and its three payloads, then state that because `identifierToPath` handles
  only the single-segment `suffix` shape, a `levelZero` identifier hits the D14 throw-default.
- **F3 (lines ~308-313, glossary):** gloss "the merge floor" at first use — one clause naming
  the file-count threshold below which tracks are normally folded together (~12 in-scope
  files).
- **F4 (lines ~13-16, over-dense):** the three mechanisms are spliced into one colon-comma
  chain. Present them as a short bulleted list here, or defer the enumeration to where each
  is unpacked (the Mermaid diagram / Plan of Work), leaving the sentence to name only that
  the track owns three mechanisms.

### track-4.md
- **F1 (lines ~18-49, 70-85, glossary):** gloss `Result` (a query-result row exposing typed
  columns), `Identifiable` (a bare record reference / RID), `Collate` (a per-property
  comparison transform; a `ci` property compares `'Foo'` and `'foo'` as equal), and `Var` (a
  lexical name path in the IR, holding no parse-node reference) at first use in Purpose /
  Context.
- **F2 (lines ~64-69, over-dense):** the four-step `(1)…(4)` comparison sequence is crammed
  into one sentence and is already drawn as the sequence diagram. Break onto numbered lines
  or point to the diagram below rather than re-inlining.
- **F3 (lines ~73-78, over-dense causal chain):** split the EQ/NE divergence into three
  sentences — the session difference between EQ and NE; that `equals` runs a session-consulting
  coercion on mixed-type operands; therefore the two branches can diverge.
- **F4 (lines ~80-84, hard-to-read):** the fast-path/parity sentence stacks a definition, a
  nested parenthetical, and two conclusions. Split: the fast path exists and is named; it is
  parity-equivalent because it falls through on any case collation or coercion could affect;
  therefore S0 targets the slow path only.
- **F5 (lines ~109-117, too-terse):** gloss `SecurityEngine` (the component evaluating
  access-control predicates) and add one sentence with the concrete consequence — a
  previously case-sensitive `name = 'admin'` security check would begin matching `'Admin'` —
  so the reader sees why the S1/S7 validation obligation exists.
- **F6 (lines ~131-139, over-dense):** the `(a)…(b)` two-fast-path enumeration with nested
  rationale is a run-on. Break the two fast paths into separate list items, each with its
  sub-mechanism on its own line.
- **F7 (lines ~157-164, hard-to-read):** the collate-fetch arrow chain `result.asEntity()` →
  `getImmutableSchemaClass(session)` → `getProperty(name)` → `property.getCollate()` is wedged
  mid-sentence with a trailing fragment. State the chain as its own fenced trace / numbered
  line, then say separately it returns null for non-`Var` operands and is tried left operand
  first, then right.
- **F8 (lines ~182-188, glossary):** gloss `LDBC SNB` (a standardized fixed graph-benchmark
  query set), `SF1` (its scale factor), and "neutrality gate" (proves a change does not
  regress those fixed queries) so the reader can follow why a new path the queries never hit
  escapes measurement.
- **F9 (line ~80, 234, 254-255, too-terse):** add one clause where the `doCompare`-vs-0
  mapping first appears — `doCompare` returns a sign, and each ordering operator maps that
  sign against 0 (`<` is `doCompare < 0`, `>=` is `doCompare >= 0`, …).

## Stamp / return contract (unchanged)
Preserve each file's line-1 `<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->`
and the H1 on line 2. Do not touch Decision Record content, the track split, or section
structure beyond the flagged prose. Return only a thin summary of what you re-drafted per
track — never the drafted content.
