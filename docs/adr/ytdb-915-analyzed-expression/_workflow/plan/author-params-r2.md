# design-author params — phase1-creation, round 2 (re-ground flagged passages only)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 2

## flagged_passages

1. **[absorption — must fix, blocks dual-clean] Missing seed D-record for D4.**
   The research log's `### D4 — Cast variant dropped from S0 scope` is a load-bearing
   decision (real rejected alternatives: keep `Cast` and lower method calls into it;
   ship `Cast` as a placeholder with no lowering = dead code) but the design has no
   D-record and no mention of it. Add a seed D-record for D4 where the IR variant set
   is introduced (Part 1, the sealed-IR / variant-list section), so a reader sees that
   `Cast` was considered and dropped: YouTrackDB grammar has no `CAST(x AS T)` — type
   coercion is method-call syntax (`.asInteger()`, routed through `SQLModifier`,
   structurally a `FuncCall`), so a dedicated `Cast` variant would carry a cast-target
   type taxonomy with no S0 consumer. Keep it to the design's seed-D-record shape;
   ground the grammar claim via PSI if you cite an anchor.

2. **[readability suggestion] design.md:19 inflated-abstraction label.**
   "The enabling primitive is a Java 21 sealed interface with five immutable record
   variants" — drop the "enabling primitive" label and lead with the concrete subject,
   e.g. "S0's core is a Java 21 sealed interface with five immutable record variants…".
   No rewrite beyond the opening clause.

3. **[readability suggestion] design.md:250 term used before its gloss.**
   "a literal value (integer, string, boolean, a `sign`-folded negative number)" uses
   `sign`-folded ten lines before its gloss (~:260). Add a short in-place gloss (e.g.
   "a negative literal whose `sign` flag the parser already folded in") or reorder so
   the `sign`-flag explanation precedes the `Const` bullet.
