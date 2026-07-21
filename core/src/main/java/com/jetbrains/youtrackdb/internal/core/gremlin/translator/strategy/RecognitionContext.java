package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLPositionalParameter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * The recogniser-facing view of the walk. A {@link StepRecogniser} reads resolved flags and the
 * current boundary, mints aliases, and contributes to the pattern through the named methods here — it
 * cannot reach the traversal, the strategy list, the step cursor's position, or the pattern builder.
 * The concrete {@link WalkerContext} implements this interface and owns that full state.
 *
 * <p>Narrowing the surface this way is what keeps a new recogniser from perturbing the others: it
 * cannot add order-dependence on the raw traversal or scan strategies on a hot path, and every
 * contribution goes through a method whose effect is fixed here rather than through direct field
 * writes.
 *
 * <h2>No mutation discipline</h2>
 *
 * A recogniser may read and contribute in any order. A {@link Outcome#DECLINE} — its own or a later
 * recogniser's — makes {@link GremlinStepWalker} discard the whole walk, so a partial contribution
 * can never leak into a translated plan. "Validate before you mutate" is unnecessary here.
 */
interface RecognitionContext {

  // --- Resolved flags, each resolved once by the walker -----------------------------------------

  /**
   * Whether the traversal runs as a polymorphic query ({@code YTDBStrategyUtil.isPolymorphic}).
   * Resolved once by {@link GremlinStepWalker}; a {@code null} resolution declines the whole walk
   * before any recogniser runs, so this is always a resolved value. The {@code hasLabel(L)}
   * recogniser reads it to pick the boundary-node re-typing: polymorphic re-types to {@code {class:
   * L}} (MATCH matches subclasses, mirroring native hierarchy-aware {@code hasLabel}), while
   * non-polymorphic re-types to {@code L} and adds an exact {@code @class = 'L'} filter (leaf-exact,
   * mirroring native non-polymorphic {@code hasLabel}). The vertex-source and bare-hop recognisers
   * root every node at the generic {@code V} class regardless of it.
   */
  boolean polymorphic();

  /**
   * Whether the traversal opts into {@code EdgeLabelVerificationStrategy}. Resolved once by
   * {@link GremlinStepWalker} so a recogniser reads a boolean instead of scanning the strategy list.
   * A label-less hop declines when this is {@code true}: translating the hop away would suppress the
   * label-less error that strategy exists to raise (see
   * {@link GremlinPatternAssembler#resolveEdgeLabel}).
   */
  boolean edgeLabelVerificationEnabled();

  // --- Boundary read ----------------------------------------------------------------------------

  /**
   * The alias of the traversal's current terminator, or {@code null} before any step has pinned a
   * boundary. A hop reads this as its "from" endpoint; the start-step recogniser uses a {@code null}
   * boundary as its "I am the start" guard.
   */
  @Nullable String boundaryAlias();

  /**
   * The schema class registered for {@link #boundaryAlias()} in the positive pattern, or {@code null}
   * when the boundary is still the generic {@code V} root. {@code WherePredicateStep} uses this for
   * {@link GremlinPredicateAdapter.PropertyTypeGate} routing when a label comparison also names a
   * property key.
   */
  @Nullable String boundaryClassName();

  // --- Schema-aware type gating -----------------------------------------------------------------

  /**
   * Whether {@code propertyKey} is declared with the {@code STRING} schema type on {@code className}
   * (or a supertype it inherits from). This selects the {@code startingWith} translation form: a
   * declared-String property can only ever hold String values, so a {@code startingWith} on it uses
   * the index-aware half-open prefix range (a B-tree prefix scan); every other case (unknown /
   * undeclared type, a declared non-String type, or no schema) uses the strict full-scan {@code
   * STARTSWITH} node, which throws on a present non-String value exactly as native {@code
   * Text.startingWith} does. No subclass sweep: a subclass cannot override an inherited property's
   * type (type overrides are forbidden), and a subclass-only property is not declared on {@code
   * className}, so the check is exactly the class's own (superclass-walking) property lookup.
   * Returns {@code false} when {@code className} is {@code null} (a generic {@code V} boundary whose
   * leaf class is unknown), the class or property is not declared (schema-less / mixed), or the
   * schema is unavailable. Resolved against the schema snapshot {@link GremlinStepWalker} pins once
   * per walk.
   */
  boolean isDeclaredStringProperty(@Nullable String className, String propertyKey);

  /**
   * Whether {@code className} is a declared vertex class in the resolved schema. The {@code
   * hasLabel(L)} recogniser re-types the boundary node to {@code L}, which builds a {@code SELECT
   * FROM L} scan; a non-existent class (a typo'd or never-used label) or an edge class would make
   * that scan error or return the wrong element type, while native {@code hasLabel} simply matches no
   * vertex and returns empty. The recogniser declines to native when this is {@code false} so the two
   * pipelines agree. Returns {@code false} when the schema is unavailable, so a walk with no schema
   * never re-types.
   */
  boolean isVertexClass(String className);

  // --- Alias minting ----------------------------------------------------------------------------

  /** Mints the next anonymous vertex alias ({@code $g2m_anon_0}, {@code $g2m_anon_1}, …). */
  String nextAnonVertexAlias();

  /** Mints the next anonymous edge alias ({@code $g2m_edge_0}, {@code $g2m_edge_1}, …). */
  String nextEdgeAlias();

  // --- Contributions ----------------------------------------------------------------------------

  /** Registers a pattern node under {@code alias} rooted at {@code className}, non-optional. */
  void addNode(String alias, String className);

  /** Registers an unfiltered edge {@code fromAlias --dir(edgeLabel)--> toAlias} on the pattern. */
  void addEdge(
      String fromAlias, String toAlias, MatchPatternBuilder.Direction dir,
      @Nullable String edgeLabel);

  /**
   * Registers the edge-as-node form {@code fromAlias --edgeDir E(edgeLabel){edgeFilter}--> edgeAlias
   * --closingVertexDir V()--> toAlias}, the only IR shape that can filter an edge rather than the
   * target vertex.
   */
  void addEdgeAsNode(
      String fromAlias,
      String edgeAlias,
      String toAlias,
      MatchPatternBuilder.Direction edgeDir,
      @Nullable String edgeLabel,
      MatchPatternBuilder.Direction closingVertexDir,
      @Nullable SQLWhereClause edgeFilter);

  /**
   * Records a per-alias {@code WHERE} contributed outside the pattern builder (e.g. {@code @rid IN
   * [...]}). Merged into the built pattern's alias filters at result-build time, overriding a builder
   * entry on the same alias.
   */
  void putAliasFilter(String alias, SQLWhereClause where);

  /** Records the accumulated edge {@code WHERE} under an edge alias, so the edge filter is
   *  observable on the walk state. The filter also travels on the edge path item via
   *  {@link #addEdgeAsNode}. */
  void putEdgeFilter(String edgeAlias, SQLWhereClause where);

  /**
   * Whether {@code alias} is already registered in the positive pattern under construction. Edge-bearing
   * {@code NotStep} recognisers use this to pre-validate the planner's NOT-origin constraint before
   * emitting a detached {@link SQLMatchExpression}.
   */
  boolean positivePatternHasAlias(String alias);

  /**
   * Appends a detached NOT {@link SQLMatchExpression} to the walk's {@code notMatchExpressions} sink.
   * Edge-bearing {@code NotStep} recognisers reach this after a successful sub-walk; pure-filter NOT
   * shapes merge into {@link #putAliasFilter} instead.
   */
  void addNotMatchExpression(SQLMatchExpression expression);

  /**
   * Binds a predicate comparison value to the next positional parameter slot ({@code ?}) for this
   * walk. Slot numbering is a pure function of bind order (shape-pure), independent of the bound
   * value. Structural tokens (class names, {@code ~label}, RIDs) stay inline and must not call this.
   */
  SQLPositionalParameter bindParam(Object value);

  /**
   * Marks this walk as RID-bearing ({@code g.V(ids)} start ids or a {@code hasId(...)} filter).
   * RID-bearing shapes bypass the plan cache because their fingerprint would vary per id set.
   */
  void markRidBearing();

  /**
   * Appends a captured sub-walk pattern fragment into this context's positive pattern accumulator.
   * An {@code AndStep} edge-bearing child reaches this after its sub-walk completes; a nested
   * combinator forwards into its own capture buffer rather than the top-level {@link WalkerContext}.
   */
  void appendPattern(MatchPatternBuilder captured);

  /**
   * Pins the boundary metadata: the alias the matched element appears under in each row, how the row
   * projects onto a traverser, and the TinkerPop element class the boundary emits.
   */
  void pinBoundary(String alias, BoundaryOutputType type, Class<? extends Element> returnClass);

  /**
   * Replaces the RETURN projection with a single column {@code alias AS alias}. A chain hop calls this
   * to make its new target the traversal's one result column; the start step calls it to key the row
   * on the source vertex.
   */
  void setSingleReturnColumn(String alias);

  // --- Sub-walk seam ----------------------------------------------------------------------------

  /**
   * Drives a sub-walk of {@code child} against the same recogniser registry the top-level walk uses,
   * returning the {@link SubTraversalPredicateAdapter} that captured the child's contributions. This
   * is the one seam through which a logical-combinator recogniser (a later track) translates a child
   * sub-traversal without reaching the walker's private registry or dispatch loop: it sees only this
   * interface, and this method hands it a driven sub-context to read back.
   *
   * <p>The returned adapter carries the sub-walk {@link SubTraversalPredicateAdapter#outcome()} —
   * {@link Outcome#DECLINE} when any child step is unrecognised (or the child is empty) — and, on an
   * {@link Outcome#ACCEPTED}, the captured classification the combinator composes: {@link
   * SubTraversalPredicateAdapter#hasEdges()} plus the captured filters and pattern fragments. The
   * child's contributions are captured, not committed, so a declined child leaves this context
   * untouched — the caller commits the captured state itself only on success.
   */
  SubTraversalPredicateAdapter walkChild(Traversal.Admin<?, ?> child);
}
