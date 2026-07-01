# design-author params — phase1-creation, round 2 (revise flagged passages)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/ir-filter-step-expand-step/docs/adr/ir-filter-step-expand-step/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/ir-filter-step-expand-step/docs/adr/ir-filter-step-expand-step/_workflow/research-log.md
- design_mechanics_path: null
- round: 2

## Instructions

Round 1's draft is on disk at output_path and is structurally sound (mechanical checks + the
absorption check both PASS — every D1–D5 decision is seeded; do NOT drop or restructure any
Decision record). The cold readability auditor flagged 12 prose passages below. Revise **only
these passages** in place; leave everything else byte-identical. Preserve the line-1
`<!-- workflow-sha: ... -->` stamp and the H1.

Most are pure prose rewording (split a run-on, replace a metaphor with the literal claim). Three
are too-terse / unglossed-term findings that may want a one-clause gloss — re-ground those
against the live code via PSI only if you need the mechanism detail to gloss them (they are
marked **[re-ground]**). Do not re-ground the whole document.

Line numbers are from the current on-disk file; if an edit shifts lines, apply the later edits to
the shifted text (edit by matching the quoted phrase, not by absolute line).

### Flagged passages

**FP1 — design.md ~13-16 (over-dense, § Mechanism traces).** "Most real WHERE clauses cannot be
represented in the IR yet: the lowering subset covers single-segment comparisons, arithmetic,
`NOT`, method calls, and — added here — bind parameters and boolean `AND`/`OR`, while `IN`,
`BETWEEN`, `@rid`, subqueries, and multi-segment paths stay un-lowerable and are the charter of
later slices." One sentence carries two comma-chained enumerations across a `while`-pivot. Split
into two sentences or a "covers / does not cover yet" pair on separate lines.

**FP2 — design.md ~16-17 (hard-to-read, § Plain language).** "The two load-bearing shapes that
make the migration work around that:" — verbless noun-phrase fragment + ambiguous phrasal-verb
collision ("make the migration work / around that" vs "work-around"). Give it a verb and drop the
ambiguity, e.g. "Two load-bearing shapes let the migration proceed despite that gap:".

**FP3 — design.md ~137-140 (hard-to-read, § Plain language).** The escape-hatch-IR-node sentence
stacks a `but`-contrast + colon expansion + per-implementer consequence + `whereas`-contrast in
one sentence. Split into 2–3 sentences: (a) the escape-hatch re-couples AST into the IR; (b) the
concrete cost — every `AnalyzedExprVisitor`/`AnalyzedExprTransform` implementer grows an
escape-hatch arm; (c) what the split does instead (step-local `SQLWhereClause` field touched only
by `filterMap`'s branch).

**FP4 — design.md ~137 (hard-to-read, § Plain language).** "with a wider blast radius" — metaphor
where a literal noun fits. Replace with "touching more code" / "with a wider impact", or fold into
the "forces every implementer…" clause that already states the concrete scope.

**FP5 — design.md ~141-142 [re-ground] (too-terse, § Orientation).** "LDBC shows the un-lowered
cases are operand-blocked, not condition-type-blocked, so widening barely moves the rate." The
coined compounds `operand-blocked` / `condition-type-blocked` are dropped with no gloss, and this
carries the load-bearing reason widening lowering is not worth it. Gloss the distinction in a
clause at first use (the un-lowered clauses use supported operators but on operand shapes lowering
cannot yet build — multi-segment paths, subqueries — so adding condition types leaves them
blocked). Also expand LDBC once on first use (line ~141) — it is a benchmark suite name.

**FP6 — design.md ~280-286 (over-dense, § Mechanism traces).** The `new FilterStep(...)` site
enumeration embeds a six-item call-site list mid-clause between subject and verb. State the claim
first ("Every FilterStep construction site routes through `filterStepFor`."), then list the sites
as a trailing bullet list or parenthetical after the verb.

**FP7 — design.md ~376-379 (over-dense, § Mechanism traces).** The "dead production code" sentence
comma-chains four independent evidence points (default-throw, zero callers, EXPLAIN path,
plan-cache path). Break them onto separate lines — a short bullet list under "dead production
code:", one evidence point per line.

**FP8 — design.md ~383-385 (hard-to-read, § Plain language).** "a per-step amputation, where the
honest fix is the repo-wide removal" — metaphor + value-loaded phrasing. State literally, e.g.
"removes serialize from only two of the sibling steps while the rest keep their dead serialize; the
consistent fix is the repo-wide removal (deferred to YTDB-1185)".

**FP9 — design.md ~236-238 [re-ground] (too-terse, § Orientation).** The `Param` no-coercion clause
stacks two causal "because" hops and leans on `bindFromInputParams` / "a distinct sub-expression
path" with no gloss. Gloss what `bindFromInputParams` is in one clause (or point to the
`## Bind-parameter lowering` section) and split the two "because" hops into one link per sentence.

**FP10 — design.md ~468/478 [re-ground] (glossary-introduction, § Orientation).** "early-calculated
constant" is a load-bearing fast-path guard condition, never glossed. Gloss it in place at first
use (~468): what "early" is relative to and what qualifies (a constant resolved before the
comparison runs — a literal or a resolved bind param), then use the term.

**FP11 — design.md ~542 (glossary-introduction, § Orientation).** "The AST's `toParsedTree`
coercion lives only in `bindFromInputParams`…" names `toParsedTree` with no gloss. Either gloss
`toParsedTree` in one clause (what it is, that the coercion is its behavior) or drop the method name
and keep the plain claim ("the AST's coercion lives only in `bindFromInputParams`").

**FP12 — design.md ~410 (hard-to-read, § Plain language).** "the fast-path port that keeps it hot"
— metaphorical predicate with an unclear antecedent. Replace with the literal outcome, e.g. "the
fast-path port that keeps the migrated step off the per-record deserialize regression".

## Reminders

- Do not touch the D-records' substance (absorption is clean). Preserve line-1 stamp and H1.
- Apply house-style AI-tell subset. Return ONLY a thin summary — never the drafted content.
