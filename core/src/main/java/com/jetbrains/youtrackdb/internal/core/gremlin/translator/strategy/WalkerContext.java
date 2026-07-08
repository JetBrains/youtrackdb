package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * Mutable accumulator that recognisers populate as {@link GremlinStepWalker} walks a
 * traversal. The walker creates one context per call; recognisers append nodes/edges
 * to {@link #patternBuilder} and entries to {@link #aliasFilters} / {@link #edgeFilters} /
 * the three return-projection lists, mint anonymous aliases for intermediate nodes and
 * edges via {@link #nextAnonVertexAlias()} / {@link #nextEdgeAlias()}, advance the step
 * cursor {@link #stepIndex} past every step they consume, and pin the boundary metadata
 * ({@link #boundaryAlias}, {@link #outputType}, {@link #returnClass}) when their step
 * is the traversal's terminator.
 *
 * <p>Package-private — held entirely inside the walker / recogniser conversation. The
 * {@link GremlinToMatchTranslator.TranslationResult} returned to the strategy is built
 * by the walker from this context's final state.
 *
 * <h2>Mutation discipline</h2>
 *
 * Recognisers must not mutate any field unless they are about to return {@code true}.
 * The walker does not roll back partial mutations on a {@code false} return — see the
 * contract on {@link StepRecogniser#recognize}.
 */
final class WalkerContext {

  /** Traversal currently being walked. Recognisers read traversal-level information (the
   *  attached graph, strategies) directly from this reference. The one traversal-level value the
   *  walker pre-resolves is the polymorphism setting (see {@link #polymorphic}): {@code
   *  YTDBStrategyUtil.isPolymorphic} is null-safe, so the walker resolves it once up front and
   *  declines the whole walk on a null result, rather than each recogniser re-resolving it. */
  final Traversal.Admin<?, ?> traversal;

  /** Pattern under construction. Recognisers call {@code addNode}/{@code addEdge}; the
   *  walker calls {@code build()} once at the end of a successful walk. */
  final MatchPatternBuilder patternBuilder = new MatchPatternBuilder();

  /** Per-alias WHERE clauses contributed outside the pattern builder (e.g.
   *  {@code @rid IN [...]}, {@code @class = '...'}). Merged with the builder's own
   *  alias filters at result-build time; entries here override builder entries on
   *  the same alias. */
  final Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();

  /** Per-edge-alias WHERE clauses accumulated for non-adjacent edge filtering (the
   *  {@code outE(L).has(...).inV()} shape). The edge recogniser mints an edge alias via
   *  {@link #nextEdgeAlias()}, AND-merges the interleaved {@code has(...)} predicates into
   *  this map under that alias, and hands the accumulated filter to the edge-as-node
   *  assembler. Kept separate from {@link #aliasFilters} because an edge filter attaches to
   *  the edge's own match item, not to a vertex alias. Empty until the edge recogniser
   *  populates it (added by a later step); the walker infrastructure only owns the field. */
  final Map<String, SQLWhereClause> edgeFilters = new LinkedHashMap<>();

  /** RETURN-clause projection items. One entry per output column. */
  final List<SQLExpression> returnItems = new ArrayList<>();

  /** {@code AS} aliases for each entry in {@link #returnItems}. Same length, parallel
   *  positions; null entries are allowed when an item has no alias. */
  final List<SQLIdentifier> returnAliases = new ArrayList<>();

  /** Optional nested projections per entry in {@link #returnItems}. Same length,
   *  parallel positions; null entries are allowed when an item has no nested
   *  projection. */
  final List<SQLNestedProjection> returnNestedProjections = new ArrayList<>();

  /** Alias under which the matched element appears in each result row. Set by the
   *  recogniser owning the traversal's terminator (in Phase 1, the start-step
   *  recogniser). Required for a successful walk. */
  String boundaryAlias;

  /** How the boundary step projects each result row onto a TinkerPop traverser. Set
   *  alongside {@link #boundaryAlias}. Required for a successful walk. */
  BoundaryOutputType outputType;

  /** TinkerPop element class the boundary step emits (e.g. {@code Vertex.class}). Set
   *  alongside {@link #boundaryAlias}. Required for a successful walk. */
  Class<? extends Element> returnClass;

  /** Whether the traversal runs as a polymorphic query, resolved from the traversal's YTDB
   *  session and query options ({@code YTDBStrategyUtil.isPolymorphic}). Resolved once by {@link
   *  GremlinStepWalker} at construction and read — never re-resolved — by the recognisers.
   *
   *  <p>The flag governs class narrowing for an <em>explicit</em> user-named class only — the
   *  folded {@code hasLabel(L)}, added later, which narrows through the shared {@link
   *  MatchClassFilters} seam. It does <em>not</em> apply to a bare chain hop: {@code out(L)} /
   *  {@code in(L)} / {@code both(L)} and the start step root at the generic {@code V} class
   *  polymorphically and emit no {@code @class} filter regardless of this flag, because native
   *  Gremlin never class-filters a hop target — narrowing one (even under {@code false}) would
   *  drop subclass instances the native pipeline keeps (a subclass undercount; see {@link
   *  VertexStepRecogniser} and {@link StartStepRecogniser}).
   *
   *  <p>The walker owns the resolution so no recogniser initialises the flag; a {@code null}
   *  resolution (no attached YTDB graph, or an unresolvable setting) declines the whole walk in
   *  the walker before this context is built, so the field is always a resolved primitive. */
  final boolean polymorphic;

  /** Cursor into the traversal's step list. The walker's index-driven loop reads {@code
   *  steps.get(stepIndex)} and dispatches it; the claiming recogniser then advances this cursor
   *  past every step it consumed (one for a single-step claim, N for a multi-step claim such as
   *  the {@code outE(L).has(...).inV()} chain). The walker no longer advances it — a recogniser
   *  that returns {@code true} MUST advance it by at least one, which is the consumed-step count
   *  the {@link StepRecogniser#recognize} contract requires. Recognisers also read it (e.g. the
   *  start-step recogniser only accepts at index 0). */
  int stepIndex;

  /** Reserved prefix for translator-minted anonymous vertex aliases: {@code $g2m_anon_0},
   *  {@code $g2m_anon_1}, … The {@code $g2m_} namespace is the translator's private space,
   *  distinct from GQL's {@code $c} and from {@code MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX},
   *  so a minted alias cannot collide with either front-end. User labels starting with {@code $}
   *  are refused by the walker's reserved-prefix pre-flight scan, so the namespace stays private. */
  static final String ANON_VERTEX_ALIAS_PREFIX = "$g2m_anon_";

  /** Reserved prefix for translator-minted anonymous edge aliases: {@code $g2m_edge_0},
   *  {@code $g2m_edge_1}, … Used by the non-adjacent edge-filter recogniser to name the edge in
   *  the edge-as-node MATCH form. Same reserved {@code $g2m_} namespace as {@link
   *  #ANON_VERTEX_ALIAS_PREFIX}. */
  static final String EDGE_ALIAS_PREFIX = "$g2m_edge_";

  /** Monotonic counter behind {@link #nextAnonVertexAlias()}. Per-context (reset each walk), so
   *  the alias sequence is deterministic per query rather than monotonic across the JVM. */
  private int anonVertexCounter;

  /** Monotonic counter behind {@link #nextEdgeAlias()}. Per-context (reset each walk); see {@link
   *  #anonVertexCounter}. */
  private int edgeCounter;

  WalkerContext(Traversal.Admin<?, ?> traversal, boolean polymorphic) {
    this.traversal = traversal;
    this.polymorphic = polymorphic;
  }

  /** Mints the next anonymous vertex alias ({@code $g2m_anon_0}, {@code $g2m_anon_1}, …). Each
   *  call returns a fresh alias and advances the per-context counter, so a multi-hop chain gets
   *  distinct intermediate-node names. */
  String nextAnonVertexAlias() {
    return ANON_VERTEX_ALIAS_PREFIX + anonVertexCounter++;
  }

  /** Mints the next anonymous edge alias ({@code $g2m_edge_0}, {@code $g2m_edge_1}, …). Each call
   *  returns a fresh alias and advances the per-context counter. */
  String nextEdgeAlias() {
    return EDGE_ALIAS_PREFIX + edgeCounter++;
  }
}
