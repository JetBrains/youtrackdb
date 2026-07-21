package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * The recognition context a logical-combinator recogniser drives a child sub-traversal against: a
 * {@link RecognitionContext} that wraps its parent, delegates the reads a child needs (resolved
 * flags, the current boundary, schema gating, and — critically — alias minting), but <em>captures</em>
 * every contribution the child makes rather than committing it to the parent. The combinator
 * recogniser (a later track) drives one adapter per child through {@link RecognitionContext#walkChild},
 * inspects the captured classification, and commits the captured state to its own context in the way
 * its connective requires (AND merges pure-filter clauses, OR composes them out-of-band, NOT detaches
 * edge fragments). This step lands only the adapter and the sub-walk seam; the combinator recognisers
 * that consume it land in later steps.
 *
 * <h2>Why a delegating capture context, not a fresh one</h2>
 *
 * A naive fresh {@link WalkerContext} per child is silently wrong, not a clean decline:
 *
 * <ul>
 *   <li><b>Alias minting must delegate to the parent.</b> A per-child sequence restarts at 0, so two
 *       children of {@code and(__.out("a"), __.out("b"))} would both mint {@code $g2m_anon_0}; in
 *       MATCH one alias is one binding, silently over-constraining to "both edges reach the
 *       <em>same</em> vertex" and dropping every source whose two targets differ. The adapter forwards
 *       {@link #nextAnonVertexAlias()} / {@link #nextEdgeAlias()} to the parent so the whole walk
 *       shares one alias space.
 *   <li><b>{@link #boundaryAlias()} reads the parent's boundary</b>, so a child's filters and hops key
 *       on the alias the child is actually filtering.
 *   <li><b>{@link #pinBoundary} / {@link #setSingleReturnColumn} are swallowed.</b> A hop child
 *       dispatches {@link VertexHopRecogniser}, which re-pins the boundary and single RETURN column to
 *       its own target; on a delegating context that would move the outer traversal's result column
 *       onto the child's target. The child changes the parent's <em>filter</em>, never its result
 *       shape, so the adapter drops both re-pins.
 * </ul>
 *
 * <h2>Capture boundary</h2>
 *
 * Contributions land in the adapter's own buffers, never the parent's committed state. A declined
 * child therefore leaves the parent untouched with no per-recogniser rollback: the whole adapter (with
 * its partial buffers) is discarded and the combinator recogniser declines. On a successful child the
 * combinator reads the buffers and commits them itself, per-connective. This is the capture boundary
 * the {@code decline_doesNotCommitPartialStateToOuterContext} unit test pins.
 *
 * <h2>Classification the combinator reads back</h2>
 *
 * After a driven walk the adapter exposes {@link #outcome()} and the captured state:
 *
 * <ul>
 *   <li>a <b>pure-filter</b> child adds no hop ({@link #hasEdges()} is {@code false}). It contributes
 *       alias filters ({@link #capturedAliasFilters()} carries its conjoined WHERE) and at most a
 *       boundary-node re-type: a folded {@code hasLabel(L)} narrows the existing boundary alias's
 *       class through {@link #addNode}, which lands in {@link #capturedPattern()} but introduces no
 *       edge, so it stays pure-filter;
 *   <li>an <b>edge-bearing</b> child contributes at least one hop through {@link #addEdge} / {@link
 *       #addEdgeAsNode} / {@link #appendPattern} of an edge-bearing fragment ({@link #hasEdges()} is
 *       {@code true}, {@link #capturedPattern()} carries the edge fragment).
 * </ul>
 *
 * The distinction drives every combinator: AND supports both, OR declines any edge-bearing child, NOT
 * routes pure-filter to {@code WHERE NOT} and edge-bearing to a detached anti-join pattern. Keying it
 * on the edge/hop contribution (not on any {@code addNode}) is what keeps an all-pure-filter {@code
 * or(hasLabel, hasLabel)} translatable and a {@code not(hasLabel)} on the {@code WHERE NOT} path.
 */
final class SubTraversalPredicateAdapter implements RecognitionContext {

  /** The context this adapter delegates reads and alias minting to — the top-level {@link
   *  WalkerContext} for a direct child, or an enclosing adapter for a nested combinator. */
  private final RecognitionContext parent;

  /** The recogniser registry a nested {@link #walkChild} drives against — the same registry the
   *  top-level walk uses, threaded through so nested combinators dispatch identically. */
  private final Map<Class<?>, StepRecogniser> recognisers;

  /** Stateless builder used to AND-compose same-alias captured filters, mirroring {@link
   *  WalkerContext#putAliasFilter}: within one child the steps are conjunctive, so a second
   *  contribution to one alias is ANDed with the first. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  /** Captured per-alias WHERE clauses — the child's pure-filter contributions, keyed on the parent's
   *  boundary alias (or any alias a hop introduced). Not committed to the parent. */
  private final Map<String, SQLWhereClause> capturedAliasFilters = new LinkedHashMap<>();

  /** Captured per-edge-alias WHERE clauses for the non-adjacent edge-filter shape — observability
   *  only, mirroring {@link WalkerContext#edgeFilters}; the filter also travels on the edge path item
   *  via {@link #addEdgeAsNode}. */
  private final Map<String, SQLWhereClause> capturedEdgeFilters = new LinkedHashMap<>();

  /** Captured pattern fragments the child contributed — the edge-bearing output an AND child forwards
   *  to the parent pattern and a NOT child detaches into an anti-join. Buffered here so a declined
   *  child never leaves a partial fragment on the parent's pattern builder. */
  private final MatchPatternBuilder capturedPattern = new MatchPatternBuilder();

  /** Whether the child contributed an edge/hop ({@link #addEdge}, {@link #addEdgeAsNode}, or an
   *  {@link #appendPattern} merge of an edge-bearing fragment). {@code false} marks a pure-filter
   *  child; {@code true} an edge-bearing one. A bare {@link #addNode} does not flip it — a folded
   *  {@code hasLabel(L)} re-types the boundary node through {@code addNode} without adding a hop,
   *  which is pure-filter. */
  private boolean hasEdges;

  /**
   * The boundary alias subsequent filter steps in this child sub-traversal should key on. A hop's
   * {@link #pinBoundary} is swallowed (it must not move the outer traversal's result column) but the
   * alias is recorded here so a chained {@code out().has(...)} inside one child applies its filter to
   * the hop target, not the parent's boundary.
   */
  @Nullable private String effectiveBoundaryAlias;

  /** The sub-walk outcome, {@code null} until {@link GremlinStepWalker#subWalk} finishes driving this
   *  adapter. A caller reads it only after {@link RecognitionContext#walkChild} returns. */
  @Nullable private Outcome outcome;

  SubTraversalPredicateAdapter(
      RecognitionContext parent, Map<Class<?>, StepRecogniser> recognisers) {
    this.parent = parent;
    this.recognisers = recognisers;
  }

  // --- Sub-walk driving: the outcome and the captured classification --------------------------

  /** Records the driven sub-walk's outcome; called once by {@link GremlinStepWalker#subWalk}. */
  void markOutcome(Outcome result) {
    this.outcome = result;
  }

  /** The sub-walk outcome, or {@code null} before the adapter has been driven. */
  @Nullable Outcome outcome() {
    return outcome;
  }

  /** Whether the child was edge-bearing (contributed an edge/hop) rather than pure-filter. A folded
   *  {@code hasLabel(L)} boundary re-type is pure-filter, so this stays {@code false} for it. */
  boolean hasEdges() {
    return hasEdges;
  }

  /** The captured pure-filter contributions, keyed on the alias each applies to. */
  Map<String, SQLWhereClause> capturedAliasFilters() {
    return capturedAliasFilters;
  }

  /** The captured edge filters (observability), keyed on the edge alias. */
  Map<String, SQLWhereClause> capturedEdgeFilters() {
    return capturedEdgeFilters;
  }

  /** The captured pattern fragments the child contributed. */
  MatchPatternBuilder capturedPattern() {
    return capturedPattern;
  }

  // --- RecognitionContext: reads and alias minting delegate to the parent ---------------------

  @Override
  public boolean polymorphic() {
    return parent.polymorphic();
  }

  @Override
  public boolean edgeLabelVerificationEnabled() {
    return parent.edgeLabelVerificationEnabled();
  }

  @Nullable @Override
  public String boundaryAlias() {
    return effectiveBoundaryAlias != null ? effectiveBoundaryAlias : parent.boundaryAlias();
  }

  @Nullable @Override
  public String boundaryClassName() {
    return parent.boundaryClassName();
  }

  @Override
  public boolean isDeclaredStringProperty(@Nullable String className, String propertyKey) {
    return parent.isDeclaredStringProperty(className, propertyKey);
  }

  @Override
  public boolean isVertexClass(String className) {
    return parent.isVertexClass(className);
  }

  @Override
  public String nextAnonVertexAlias() {
    return parent.nextAnonVertexAlias();
  }

  @Override
  public String nextEdgeAlias() {
    return parent.nextEdgeAlias();
  }

  // --- RecognitionContext: contributions are captured, not committed --------------------------

  @Override
  public void addNode(String alias, String className) {
    // Classification-neutral: a bare addNode does not mark the child edge-bearing. It is either a
    // boundary-node re-type (a folded hasLabel(L) narrows the existing boundary alias's class through
    // addNode — a pure-filter contribution with no hop), or a hop target, which is always preceded by
    // the addEdge / addEdgeAsNode that already flipped hasEdges. Deriving hasEdges from addNode would
    // misclassify a hasLabel-bearing pure-filter child as edge-bearing, so only the edge contributions
    // below flip the flag.
    capturedPattern.addNode(alias, className, null, false);
  }

  @Override
  public void addEdge(
      String fromAlias,
      String toAlias,
      MatchPatternBuilder.Direction dir,
      @Nullable String edgeLabel) {
    capturedPattern.addEdge(fromAlias, toAlias, dir, edgeLabel, null, null, null);
    hasEdges = true;
  }

  @Override
  public void addEdgeAsNode(
      String fromAlias,
      String edgeAlias,
      String toAlias,
      MatchPatternBuilder.Direction edgeDir,
      @Nullable String edgeLabel,
      MatchPatternBuilder.Direction closingVertexDir,
      @Nullable SQLWhereClause edgeFilter) {
    capturedPattern.addEdgeAsNode(
        fromAlias, edgeAlias, toAlias, edgeDir, edgeLabel, closingVertexDir, edgeFilter);
    hasEdges = true;
  }

  @Override
  public void putAliasFilter(String alias, SQLWhereClause where) {
    var existing = capturedAliasFilters.get(alias);
    if (existing == null) {
      capturedAliasFilters.put(alias, where);
      return;
    }
    // A second same-alias contribution AND-composes, mirroring WalkerContext.putAliasFilter: within a
    // single child the filter steps are conjunctive (has(a).has(b) is a AND b), so the connective's
    // own composition (AND across AND children, OR across OR children) is applied later to this one
    // conjoined clause per alias.
    var merged = WHERE.and(existing.getBaseExpression(), where.getBaseExpression());
    capturedAliasFilters.put(alias, WHERE.wrap(merged));
  }

  @Override
  public void putEdgeFilter(String edgeAlias, SQLWhereClause where) {
    capturedEdgeFilters.put(edgeAlias, where);
  }

  @Override
  public boolean positivePatternHasAlias(String alias) {
    return parent.positivePatternHasAlias(alias);
  }

  @Override
  public void addNotMatchExpression(SQLMatchExpression expression) {
    parent.addNotMatchExpression(expression);
  }

  @Override
  public void appendPattern(MatchPatternBuilder captured) {
    // Nested combinators (e.g. and(and(out(a), out(b)), has(...))) merge grandchild hops through
    // appendPattern rather than addEdge. Flip hasEdges when the source contributed any hop so the
    // enclosing adapter is classified edge-bearing and commitEdgeBearingChild keeps the topology.
    var sourceHadEdges = captured.edgeCount() > 0;
    capturedPattern.appendFrom(captured);
    if (sourceHadEdges) {
      hasEdges = true;
    }
  }

  /**
   * Swallowed: a child changes the parent's filter, never its result shape. A hop child would
   * otherwise re-pin the boundary to its own target and move the outer result column onto it.
   */
  @Override
  public void pinBoundary(String alias, BoundaryOutputType type,
      Class<? extends Element> returnClass) {
    // Record the hop target for subsequent filter steps in this child, but do not re-pin the outer
    // traversal's boundary or RETURN column — see the class Javadoc "Why a delegating capture context".
    effectiveBoundaryAlias = alias;
  }

  /** Swallowed for the same reason as {@link #pinBoundary}: the child never replaces the result
   *  column. */
  @Override
  public void setSingleReturnColumn(String alias) {
    // Intentionally no-op — see the class Javadoc "Why a delegating capture context".
  }

  @Override
  public SubTraversalPredicateAdapter walkChild(Traversal.Admin<?, ?> child) {
    // A nested combinator (e.g. and(and(...), ...)) drives a grandchild against the same registry with
    // a fresh adapter wrapping this one, so alias minting still bottoms out at the top-level context
    // and the grandchild's captures stay isolated until this adapter's own combinator commits them.
    return GremlinStepWalker.subWalk(child, this, recognisers);
  }
}
