package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
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
 * to {@link #patternBuilder} and entries to {@link #aliasFilters} / {@link #aliasRids} /
 * the three return-projection lists, and pin the boundary metadata
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

  /** Per-alias single-RID hints — the planner's fast path that resolves to
   *  {@code SELECT FROM #X:Y} when populated. The MATCH SQL grammar accepts only one
   *  RID per alias here; multi-RID lookups go through {@link #aliasFilters} as
   *  {@code WHERE @rid IN [...]}. */
  final Map<String, SQLRid> aliasRids = new LinkedHashMap<>();

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
   *  GremlinStepWalker} at construction and read — never re-resolved — by later node-introducing
   *  recognisers (the vertex-step chain hops, {@code hasLabel}), which narrow a new alias with
   *  {@code @class = '<class>'} when {@code false} so every alias in the pattern honours one
   *  setting. The walker owns the resolution so no recogniser initialises the flag; a {@code null}
   *  resolution (no attached YTDB graph, or an unresolvable setting) declines the whole walk in
   *  the walker before this context is built, so the field is always a resolved primitive. */
  final boolean polymorphic;

  /** Index of the step the walker is currently dispatching to recognisers, advanced by
   *  the walker after each successful recognise. Recognisers read it (e.g. the
   *  start-step recogniser only accepts at index 0). */
  int stepIndex;

  WalkerContext(Traversal.Admin<?, ?> traversal, boolean polymorphic) {
    this.traversal = traversal;
    this.polymorphic = polymorphic;
  }
}
