/**
 * Execution engine for the SQL {@code MATCH} statement — YouTrackDB's graph pattern matching
 * query language.
 *
 * <p>A {@code MATCH} query describes a pattern of vertices and edges, and the engine finds all
 * sub-graphs in the database that satisfy it. For example:
 *
 * <pre>{@code
 * MATCH {class: Person, as: p, where: (name = 'Alice')}
 *         .out('Knows') {as: friend, optional: true}
 *         .out('Lives') {as: city}
 * RETURN p.name, friend.name, city.name
 * }</pre>
 *
 * <h2>End-to-End Pipeline</h2>
 *
 * <pre>
 *   SQL text
 *     |
 *     v
 *   JavaCC Parser (YouTrackDBSql.jjt)
 *     |
 *     v
 *   SQLMatchStatement  (AST node)
 *     |  .createExecutionPlan()
 *     v
 *   MatchExecutionPlanner  ---------- 8 planning phases ----------+
 *     |  Phase 1: Build pattern graph  (PatternNode / PatternEdge)|
 *     |  Phase 2: Split disjoint sub-patterns (connected components)
 *     |  Phase 3: Estimate root cardinalities                     |
 *     |  Phase 4: Prefetch small alias sets (&lt; 100 records)   |
 *     |  Phase 5: Topological scheduling + step generation        |
 *     |  Phase 6: NOT pattern filters                             |
 *     |  Phase 7: Optional cleanup (EMPTY_OPTIONAL -&gt; null)    |
 *     |  Phase 8: Return projection                               |
 *     +-----------------------------------------------------------+
 *     |
 *     v
 *   SelectExecutionPlan  (chain of ExecutionSteps)
 *     |  .start()
 *     v
 *   ExecutionStream  (lazy result rows)
 * </pre>
 *
 * <h2>Pattern Graph Data Model</h2>
 *
 * <p>The parser converts each {@code MATCH} expression into an in-memory directed graph of
 * {@code PatternNode}s and {@code PatternEdge}s, held together by the
 * {@link com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern} container.
 *
 * <pre>
 *   PatternNode("p")                PatternNode("friend")          PatternNode("city")
 *   +-----------------+             +-------------------+          +------------------+
 *   | alias  = "p"    |--outEdge--&gt;| alias  = "friend" |--outEdge-&gt;| alias  = "city" |
 *   | class  = Person |  .out(     | optional = true   |  .out(   | class  = City    |
 *   | where  = (...)  |  'Knows')  |                   |  'Lives')| where  = (...)   |
 *   +-----------------+             +-------------------+          +------------------+
 *
 *   Each PatternEdge stores:
 *     - out (PatternNode) : source vertex
 *     - in  (PatternNode) : target vertex
 *     - item (SQLMatchPathItem) : traversal method, filter, WHILE clause, depth limits
 * </pre>
 *
 * <h2>Planning Phase Detail</h2>
 *
 * <h3>Phase 1 — Build Pattern Graph</h3>
 *
 * <p>Assigns auto-generated aliases (prefix {@code $YOUTRACKDB_DEFAULT_ALIAS_}) to unnamed
 * nodes. Merges constraints when the same alias appears in multiple expressions: WHERE clauses
 * are AND-combined, class constraints resolve to the most specific subclass.
 *
 * <h3>Phase 2 — Split Disjoint Sub-patterns</h3>
 *
 * <p>Decomposes the pattern graph into connected components via flood-fill. Each component is
 * planned independently. If multiple components exist, a {@code CartesianProductStep} joins
 * their result streams.
 *
 * <h3>Phase 3 — Estimate Root Cardinalities</h3>
 *
 * <p>For each alias with a class or RID constraint, estimates the number of matching records.
 * RID constraint = 1; class with WHERE uses index statistics; class alone uses total record
 * count. If any non-optional alias has 0 estimated records, the plan short-circuits to an
 * {@code EmptyStep}.
 *
 * <h3>Phase 4 — Prefetch Small Alias Sets</h3>
 *
 * <p>Aliases with fewer than 100 estimated records whose filters do not reference
 * {@code $matched} (the context variable holding the current result row — see
 * <b>Context Variables</b> below) are eagerly loaded into memory by
 * {@code MatchPrefetchStep}. The cached results are stored in a context variable for
 * later use by {@code MatchFirstStep}. Aliases that reference {@code $matched} in their
 * WHERE clause cannot be prefetched because the filter depends on values bound at runtime
 * by earlier traversal steps (e.g. {@code WHERE ($matched.p.age < age)}).
 *
 * <h3>Phase 5 — Topological Scheduling + Step Generation</h3>
 *
 * <p>The core planning algorithm determines edge traversal order using cost-driven,
 * dependency-aware depth-first search:
 *
 * <pre>
 *   1. Build dependency map: alias A depends on B if A's WHERE clause references
 *      $matched.B (i.e. it reads a property of the record already bound to alias B)
 *   2. Sort candidate roots by estimated cardinality (ascending)
 *   3. Loop while unscheduled edges remain:
 *        a. Pick cheapest unvisited root with no unmet dependencies
 *        b. DFS from root: mark node visited, iterate neighbor edges
 *           b1. Neighbor has unmet dependencies           -&gt; skip
 *           b2. Neighbor visited, edge unscheduled        -&gt; add edge
 *               (may reverse traversal direction so the visited node becomes
 *               the starting point — see direction-flipping rules below)
 *           b3. Neighbor unvisited, dependencies met      -&gt; add edge, recurse
 *   4. Circular dependencies cause CommandExecutionException
 *
 *   Direction-flipping rules (when must / may / must not the scheduler reverse an edge?):
 *     - Target node is optional  : flip so it is reached FROM the visited side
 *     - Edge is bidirectional    : direction is arbitrary, flip freely
 *     - Edge has a WHILE clause  : NEVER flip (recursive semantics depend on direction)
 * </pre>
 *
 * <p>Worked example — diamond pattern {@code {as:a}.out('X'){as:b}, {as:a}.out('Y'){as:b}}:
 *
 * <pre>
 *   Pattern graph:       a --(.out('X'))--&gt; b
 *                        a --(.out('Y'))--&gt; b
 *
 *   Cardinality estimates: a = 10, b = 500
 *
 *   Step 1: Pick root 'a' (cheapest, no dependencies)
 *   Step 2: DFS from 'a':
 *             visit 'a'
 *             edge a-&gt;b via X: 'b' unvisited, no deps -&gt; add edge (forward), recurse into 'b'
 *               visit 'b'
 *               edge a-&gt;b via Y: 'a' already visited, edge unscheduled
 *                 -&gt; add edge (reversed: traverse from 'b' back through Y to check 'a')
 *   Step 3: All edges scheduled, done.
 *
 *   Schedule: [ a-&gt;b via X (traverse, forward),
 *               a-&gt;b via Y (consistency check, reversed) ]
 *
 *   At runtime, Edge 2 verifies that 'b' (already bound by Edge 1) is also
 *   reachable from 'a' via Y — see the diamond-pattern example under Nested-Loop Join.
 * </pre>
 *
 * <p>The schedule is a list of {@code EdgeTraversal} objects, each recording which
 * {@code PatternEdge} to traverse and in which direction (forward or reverse).
 *
 * <h3>Phase 6 — NOT Patterns</h3>
 *
 * <p>{@code NOT { ... }} sub-patterns become {@code FilterNotMatchPatternStep}s. For each
 * upstream row, a temporary sub-plan is built and executed; if it produces any result, the
 * upstream row is discarded.
 *
 * <h3>Phase 7 — Optional Cleanup</h3>
 *
 * <p>If any node was marked {@code optional: true}, a {@code RemoveEmptyOptionalsStep}
 * replaces the {@code EMPTY_OPTIONAL} sentinel with {@code null}.
 *
 * <h3>Phase 8 — Return Projection</h3>
 *
 * <p>Maps the RETURN clause to the appropriate step:
 * <ul>
 *   <li>{@code $elements} — {@code ReturnMatchElementsStep} (unrolls user-defined aliases)
 *   <li>{@code $pathElements} — {@code ReturnMatchPathElementsStep} (unrolls all aliases)
 *   <li>{@code $paths} — {@code ReturnMatchPathsStep} (pass-through, full row)
 *   <li>{@code $patterns} — {@code ReturnMatchPatternsStep} (strips auto-generated aliases)
 *   <li>Custom expressions — delegates to {@code SelectExecutionPlanner} for standard
 *       SELECT-style projection, GROUP BY, ORDER BY, UNWIND, SKIP, LIMIT
 * </ul>
 *
 * <h2>Physical Plan Structure</h2>
 *
 * <p>The execution steps form a pipeline, processed lazily via {@code ExecutionStream}.
 * Each step pulls from its predecessor and produces zero or more expanded rows:
 *
 * <pre>
 *   MatchPrefetchStep     (side-effect only: loads small alias sets into context
 *       |                  variable, returns empty stream. Consumed later by
 *       |                  MatchFirstStep which checks context before scanning.)
 *       v
 *   MatchFirstStep        (scans/lookups first alias; checks prefetch cache first)
 *       |                 row: { p: #10:0 }
 *       v
 *   OptionalMatchStep     (traverses .out('Knows'), LEFT JOIN semantics)
 *       |                 row: { p: #10:0, friend: #10:1 }
 *       |                   or: { p: #10:0, friend: EMPTY_OPTIONAL }
 *       v
 *   MatchStep             (traverses .out('Lives'))
 *       |                 row: { p: #10:0, friend: #10:1, city: #11:0 }
 *       v
 *   FilterNotMatchPatternStep  (if NOT patterns exist)
 *       |
 *       v
 *   RemoveEmptyOptionalsStep   (EMPTY_OPTIONAL -&gt; null)
 *       |                 row: { p: #10:0, friend: null, city: #11:0 }
 *       v
 *   ReturnMatch*Step / ProjectionCalculationStep
 *       |                 row: { p.name: "Alice", friend.name: null, city.name: "Berlin" }
 *       v
 *   ExecutionStream       (consumed by caller)
 *
 *   Note: WhileMatchStep (plan-level WHILE wrapper) is currently unused — recursive
 *   traversal is handled inline by MatchEdgeTraverser. WhileMatchStep is reserved
 *   for potential future use as a plan-level recursion boundary.
 * </pre>
 *
 * <h2>Traverser Hierarchy</h2>
 *
 * <p>Each {@code MatchStep} wraps its traverser in a
 * {@code ResultSetEdgeTraverser} (from the {@code resultset} package), which drives
 * iteration and updates the {@code $matched} context variable to point to the current
 * result row after each successful traversal. This makes all previously bound aliases
 * available to downstream WHERE filters via {@code $matched.<alias>}. The traverser
 * itself encapsulates per-edge navigation, filtering, and join logic:
 *
 * <pre>
 *   MatchEdgeTraverser  (base: forward traversal, filtering, join checks)
 *     |
 *     +-- MatchReverseEdgeTraverser   (reverse direction traversal)
 *     +-- MatchFieldTraverser         (field/property access instead of graph hop)
 *     +-- MatchMultiEdgeTraverser     (compound multi-step path pipeline)
 *     +-- OptionalMatchEdgeTraverser  (LEFT JOIN: emits EMPTY_OPTIONAL on no match)
 * </pre>
 *
 * <p>Traverser selection in {@code MatchStep.createTraverser()}:
 *
 * <pre>
 *   Edge AST type          | Direction | Traverser
 *   -----------------------+-----------+---------------------------
 *   SQLMultiMatchPathItem  | any       | MatchMultiEdgeTraverser
 *   SQLFieldMatchPathItem  | any       | MatchFieldTraverser
 *   other, forward         | forward   | MatchEdgeTraverser
 *   other, reverse         | reverse   | MatchReverseEdgeTraverser
 *   (OptionalMatchStep overrides to always use OptionalMatchEdgeTraverser)
 * </pre>
 *
 * <h3>Optional Merge Rules</h3>
 *
 * <p>When {@code OptionalMatchEdgeTraverser} processes a target alias that may have been
 * bound by a previous traversal, it applies identity-based merging. The sentinel
 * {@code EMPTY_OPTIONAL} is a singleton {@code ResultInternal} instance always compared
 * by reference identity ({@code ==}), never by {@code .equals()} — this ensures that
 * the sentinel is distinguishable from any real record:
 *
 * <pre>
 *   Previous value for alias | Traversal result    | Outcome
 *   -------------------------+---------------------+----------------------------------
 *   null (not yet bound)     | record R            | bind alias to R
 *   null (not yet bound)     | EMPTY_OPTIONAL      | bind alias to null
 *   EMPTY_OPTIONAL           | (any)               | return upstream row unchanged
 *   record P                 | same record P       | keep P (join condition satisfied)
 *   record P                 | different record R  | return null (consistency violation,
 *                            |                     |   row is dropped)
 * </pre>
 *
 * <p>The {@code RemoveEmptyOptionalsStep} runs after all traversals to convert any
 * remaining {@code EMPTY_OPTIONAL} sentinels to {@code null} in the final output.
 *
 * <h2>Key Algorithms</h2>
 *
 * <h3>Nested-Loop Join</h3>
 *
 * <p>Pattern matching is implemented as a nested-loop join over the pattern graph. The first
 * node scans records; each subsequent edge acts as a nested loop that, for each upstream row,
 * traverses the edge and produces zero or more expanded rows. At each hop, class, RID, and
 * WHERE constraints are applied.
 *
 * <p><b>Consistency check (join condition):</b> if a target alias was already bound in a
 * previous traversal, the newly traversed record must equal the previously bound value.
 * Mismatches silently drop the row. This is critical for <em>diamond patterns</em> where
 * two paths converge on the same alias:
 *
 * <pre>
 *   MATCH {as: a}.out('X'){as: b}, {as: a}.out('Y'){as: b}
 *
 *   Schedule: Edge 1 (a-&gt;b via X, traversal), Edge 2 (a-&gt;b via Y, consistency check)
 *
 *   For row { a: #1:0 }:
 *     Edge 1 traverses a.out('X') -&gt; finds #2:0 -&gt; row: { a: #1:0, b: #2:0 }
 *     Edge 2 traverses a.out('Y') -&gt; finds #2:0 -&gt; b already bound to #2:0
 *       #2:0 == #2:0  -&gt; row passes consistency check (kept)
 *     Edge 2 traverses a.out('Y') -&gt; finds #3:0 -&gt; b already bound to #2:0
 *       #3:0 != #2:0  -&gt; row fails consistency check (dropped)
 * </pre>
 *
 * <h3>WHILE / Recursive Traversal</h3>
 *
 * <p>{@code MatchEdgeTraverser} supports two modes:
 * <ul>
 *   <li><b>Simple (single-hop)</b>: traverse one hop, filter neighbors, return matches.
 *   <li><b>Recursive (WHILE/maxDepth)</b>: depth 0 evaluates the start point; depths
 *       1..N expand neighbors level by level while the WHILE condition holds and
 *       depth &lt; maxDepth. Each result carries {@code $depth} and {@code $matchPath}
 *       metadata. Note: no visited-set tracking; cycles are bounded by maxDepth/WHILE.
 * </ul>
 *
 * <h3>Multi-Edge Pipeline</h3>
 *
 * <p>{@code MatchMultiEdgeTraverser} handles the {@code .( )( )...} syntax
 * ({@code SQLMultiMatchPathItem}), executing a left-to-right fold over sub-items:
 *
 * <pre>
 *   left = { input record }
 *   for each sub-item in the multi-path:
 *       right = empty set
 *       for each record in left:
 *           execute sub-item traversal on record
 *           apply sub-item's WHERE filter to results
 *           add filtered results to right
 *       left = right
 *   return left   (final traversal result)
 * </pre>
 *
 * <p>If a sub-item carries a WHILE clause, it delegates to a standard
 * {@code MatchEdgeTraverser} for recursive expansion of that sub-item before
 * continuing the fold.
 *
 * <h3>Cartesian Product for Disjoint Sub-patterns</h3>
 *
 * <p>Disconnected sub-graphs are planned independently, then joined via
 * {@code CartesianProductStep} which computes the cross product of independent result streams.
 *
 * <h2>Context Variables</h2>
 *
 * <pre>
 *   Variable                                     | Set by                          | Purpose
 *   ---------------------------------------------+---------------------------------+-----------------------------
 *   $matched                                     | ResultSetEdgeTraverser.next()    | The current result row containing
 *                                                | and MatchFirstStep              | all aliases bound so far. After
 *                                                |                                 | matching p and friend, the value
 *                                                |                                 | is {p: Person#1, friend: Person#2}.
 *                                                |                                 | This enables correlated WHERE
 *                                                |                                 | filters in later nodes, e.g.:
 *                                                |                                 |   WHERE ($matched.p.age < age)
 *                                                |                                 | which reads the 'age' property of
 *                                                |                                 | the record already bound to 'p'.
 *                                                |                                 | Also drives the dependency graph
 *                                                |                                 | in the topological scheduler
 *                                                |                                 | (Phase 5) and the prefetch
 *                                                |                                 | exclusion rule (Phase 4)
 *   $currentMatch                                | MatchEdgeTraverser              | Candidate record being
 *                                                |                                 | evaluated in a filter
 *   $current                                     | MatchEdgeTraverser              | Starting-point record for
 *                                                |                                 | method invocation
 *   $depth                                       | MatchEdgeTraverser              | Recursion depth during
 *                                                |                                 | WHILE traversals
 *   $matchPath                                   | MatchEdgeTraverser              | List of RIDs visited during
 *                                                |                                 | recursive (WHILE) traversal;
 *                                                |                                 | set alongside $depth
 *   $$YouTrackDB_Prefetched_Alias_Prefix__&lt;a&gt; | MatchPrefetchStep               | Cached List&lt;Result&gt;
 *                                                |                                 | for prefetched alias sets
 * </pre>
 *
 * <h2>Class Index</h2>
 *
 * <p><b>Data structures:</b>
 * <ul>
 *   <li>{@code PatternNode} — vertex in the pattern graph ({@code {as: alias, class: ...}})
 *   <li>{@code PatternEdge} — directed edge between two PatternNodes with traversal metadata
 *   <li>{@code EdgeTraversal} — scheduling decision: which PatternEdge to traverse and in
 *       which direction
 * </ul>
 *
 * <p><b>Planner:</b>
 * <ul>
 *   <li>{@code MatchExecutionPlanner} — converts parsed AST into a physical execution plan
 * </ul>
 *
 * <p><b>Execution steps:</b>
 * <ul>
 *   <li>{@code MatchPrefetchStep} — eagerly loads small alias sets into memory
 *   <li>{@code MatchFirstStep} — produces initial rows for the first node
 *   <li>{@code MatchStep} — traverses a single edge, expanding each upstream row
 *   <li>{@code OptionalMatchStep} — like MatchStep with LEFT JOIN semantics
 *   <li>{@code FilterNotMatchPatternStep} — discards rows matching a NOT sub-pattern
 *   <li>{@code RemoveEmptyOptionalsStep} — replaces EMPTY_OPTIONAL sentinels with null
 *   <li>{@code WhileMatchStep} — plan-level WHILE wrapper (reserved for future use)
 * </ul>
 *
 * <p><b>Return projections:</b>
 * <ul>
 *   <li>{@code ReturnMatchElementsStep} — {@code $elements}: unrolls user-defined aliases
 *   <li>{@code ReturnMatchPathElementsStep} — {@code $pathElements}: unrolls all aliases
 *   <li>{@code ReturnMatchPathsStep} — {@code $paths}: pass-through
 *   <li>{@code ReturnMatchPatternsStep} — {@code $patterns}: strips auto-generated aliases
 * </ul>
 *
 * <p><b>Traversers:</b>
 * <ul>
 *   <li>{@code MatchEdgeTraverser} — base: forward traversal with filtering and join checks
 *   <li>{@code MatchReverseEdgeTraverser} — reverse direction traversal
 *   <li>{@code MatchFieldTraverser} — field/property access instead of graph traversal
 *   <li>{@code MatchMultiEdgeTraverser} — left-to-right fold over {@code .( )( )} sub-items
 *   <li>{@code OptionalMatchEdgeTraverser} — emits EMPTY_OPTIONAL sentinel on no match
 * </ul>
 *
 * @see com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner
 * @see com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern
 * @see com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor.match;
