# Beta feedback synthesis — revision plan

The three beta readers converge on a consistent picture: the book works for its target reader, the veteran acknowledges depth in the core (Parts III–VI) but names three content gaps, and the practitioner can solve the diagnostic test in ~12 minutes but hits navigational friction. None of the three asks for structural reorganisation. All three ask for targeted tightening.

## High-value revisions (applied in this cycle)

| # | Chapter / file | Change | Source | Why |
|---|---|---|---|---|
| 1 | Ch 8 | Replace flowing-prose description of `SelectivityEstimator`'s three tiers with an explicit decision table + numbered rule list, paralleling Ch 9's presentation of the four root-estimation rules. | BR1 #1 | Load-bearing concept; readability gap. |
| 2 | Ch 7 | Expand Phase 5 paragraph: sub-list `optimizeScheduleWithIntersections`, `rebindFilters`, and `identifyHashJoinBranches` with one-line descriptions and forward anchors. | BR1 #3 | Enables Chs 10, 13, 14 to cross-reference by name. |
| 3 | Ch 13 | Before §"the optional back-reference" and §"recursive edges that cannot be reversed", add a one-paragraph bridge making clear the eligibility tree applies only to the first variant; the other two have different triggers. | BR1 #2 | Prevents significant re-reading. |
| 4 | Ch 10 | After §10.11 (greedy DFS limitation), add one paragraph making the connection to Chapter 13 explicit: when the greedy DFS commits to a bad order, the hash-join threshold can convert the penalty into a bounded cost. | BR2 | Cross-chapter coherence. |
| 5 | Ch 14 | Terminology sweep: rename every "cluster" / "cluster ID" in the chapter to "collection" / "collection ID" (matching Ch 2's canonical vocabulary), except when the variable name literally reads `clusterId` in source — cite those as `clusterId` (the Java field) with the parenthetical "(collection ID)". | BR2 | Book-wide consistency. |
| 6 | Ch 16 | Move the PROFILE pointer from §16.8 to the end of §16.1. §16.3 should also gain a one-line aside noting PROFILE provides the runtime counts that EXPLAIN lacks. | BR3 #2 | Practitioner friction reduction. |
| 7 | Ch 17 | Add a glossary entry for "intersection descriptor" redirecting to "pre-filter / `RidFilterDescriptor`", and cross-reference the EXPLAIN token form. | BR3 #3 | Discoverability. |
| 8 | TOC | Add a short "Symptoms index" section before the Part listings: a small table mapping common symptoms (wrong root, missing reversal, missing pre-filter, hash-join explosion, Cartesian explosion) to the chapter or chapters that diagnose them. | BR3 #1 | Practitioner on-ramp. |

## Deferred to next revision cycle (V2)

Three items from Beta Reader 2 (the veteran) call for substantive new content rather than tightening. They are correct but should be treated as genuine additions, not revision-pass work:

1. **Histogram bucket interpolation arithmetic** (Ch 8). A new sub-section is warranted. Requires detailed reading of `EquiDepthHistogram` / `SelectivityEstimator` to derive the precise formula.
2. **`LazyRecursiveTraversalStream` section for WHILE semantics** (Ch 12). Half a section: BFS/DFS policy, visited-RID deduplication, `pathAlias` opt-out. Requires reading the stream class.
3. **Plan/statement cache follow-through** (Ch 4 or a new appendix). The deep-copy paragraph sets up the cache question but leaves it unanswered. Requires checking the actual caching in `MatchExecutionPlanner` call sites.

These three are tracked but not applied in this cycle. They should go into a next iteration of the production pipeline.

## Summary for the record

- Beta Reader 1 (target reader): verdict positive, three readability fixes, confidence in reading source and diagnosing queries.
- Beta Reader 2 (veteran): depth good in core Parts III–VI, three content gaps named, terminology inconsistency.
- Beta Reader 3 (practitioner): solved the diagnostic test in ~12 minutes using the book; three navigation fixes.

The revision cycle closes with eight applied changes and three deferred. A second beta pass would be the right way to validate the deferred additions once they are drafted.
