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

  /** Whether the traversal runs as a polymorphic query, resolved once from the traversal's YTDB
   *  session and query options ({@code YTDBStrategyUtil.isPolymorphic}) by {@link GremlinStepWalker}
   *  at construction.
   *
   *  <p>No recogniser in this track reads the resolved value. The resolution is kept for its decline
   *  side effect: a {@code null} result (no attached YTDB graph, or an unresolvable setting) declines
   *  the whole walk in the walker before this context is built, so the field is always a resolved
   *  primitive. The resolved value itself is reserved for the explicit-class narrowing path -- the
   *  folded {@code hasLabel(L)} of a later track, which narrows through the shared {@code
   *  MatchWhereBuilder.classEquals} seam when {@code polymorphic} is {@code false}.
   *
   *  <p>The flag deliberately does <em>not</em> govern a bare chain hop or the start step: {@code
   *  out(L)} / {@code in(L)} / {@code both(L)} and {@code g.V()} root at the generic {@code V} class
   *  ({@link #VERTEX_ROOT_CLASS}) polymorphically and emit no {@code @class} filter regardless of it,
   *  because native Gremlin never class-filters those shapes -- narrowing one (even under {@code
   *  false}) would drop subclass instances the native pipeline keeps (a subclass undercount; see
   *  {@link VertexStepRecogniser} and {@link StartStepRecogniser}). */
  final boolean polymorphic;

  /** Cursor into the traversal's step list, owned and advanced solely by the walker. The walker's
   *  index-driven loop reads {@code steps.get(stepIndex)}, dispatches it, and advances this cursor
   *  by the consumed-step count the recogniser returns (one for a single-step claim, N for a multi-
   *  step claim such as the {@code outE(L).has(...).inV()} chain). Recognisers read it (e.g. the
   *  start-step recogniser only accepts at index 0, the edge recogniser peeks ahead from it) but
   *  MUST NOT write it — see the {@link StepRecogniser#recognize} contract. */
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

  /**
   * Generic vertex root class {@code "V"} -- the polymorphic base every vertex-rooted traversal
   * roots at when no explicit user class is given. Shared by {@link StartStepRecogniser} (the {@code
   * g.V()} boundary node) and {@link GremlinPatternAssembler} (each bare-hop and edge-as-node target
   * node), which both register their node under it with no {@code @class} filter, so the emitted
   * MATCH keeps the full polymorphic vertex set native Gremlin returns. One definition so the two
   * sites cannot drift onto different roots -- a drift would silently reintroduce a subclass
   * undercount.
   */
  static final String VERTEX_ROOT_CLASS = "V";

  /**
   * The single definition of the reserved {@code $} alias-namespace prefix. The translator mints
   * every internal alias under it ({@link #ANON_VERTEX_ALIAS_PREFIX} and {@link #EDGE_ALIAS_PREFIX}
   * both begin with {@code $}), so a user identifier in this space could reach a MATCH WHERE
   * identifier the executor resolves as a query context variable ({@code $parent}, or any {@code
   * $name} bound in the execution context) rather than a record property — diverging from native
   * Gremlin, which treats {@code $foo} as a plain, absent property name.
   *
   * <p>Both reserved-namespace guards read this one constant so they cannot drift apart on its
   * value: {@link GremlinStepWalker}'s reserved-prefix {@code as(...)} label pre-flight declines a
   * label starting with it, and {@link GremlinPredicateAdapter}'s {@code has(...)}-key guard folds
   * it into {@link #isReservedHasKey(String)}.
   */
  static final String RESERVED_ALIAS_PREFIX = "$";

  /** TinkerPop's hidden-key namespace prefix ({@code ~label} / {@code ~id}, produced by {@code
   *  hasLabel} / {@code hasId}). A {@code has(...)} key in this space is a reserved token, not a
   *  plain property, so {@link #isReservedHasKey(String)} declines it. */
  static final String HIDDEN_KEY_PREFIX = "~";

  /** YouTrackDB's record-attribute namespace prefix ({@code @class} / {@code @rid} / {@code
   *  @version}). The shared identifier resolver treats a bare {@code @}-prefixed identifier as
   *  record metadata rather than a property, so a {@code has(...)} key in this space would diverge
   *  from native Gremlin — which treats {@code @foo} as an ordinary, absent property name.
   *  {@link #isReservedHasKey(String)} declines it. */
  static final String RECORD_ATTRIBUTE_PREFIX = "@";

  /**
   * The one place the {@code has(...)}-key reserved-namespace decline set is expressed. Returns
   * {@code true} when {@code key} lands in a namespace whose bare identifier the MATCH executor
   * would resolve as something other than a plain record property — the minted-alias {@code $}
   * space ({@link #RESERVED_ALIAS_PREFIX}), TinkerPop's hidden-key {@code ~} space ({@link
   * #HIDDEN_KEY_PREFIX}), or YouTrackDB's record-attribute {@code @} space ({@link
   * #RECORD_ATTRIBUTE_PREFIX}) — so the predicate adapter declines rather than translate a
   * divergent filter. Centralising the three prefixes here keeps the decline set from drifting as
   * predicate coverage grows in later tracks. Null / blank keys are the caller's concern (not a
   * namespace one). The walker's label pre-flight deliberately consumes only {@link
   * #RESERVED_ALIAS_PREFIX}: an {@code as(...)} label can collide only with the minted-alias
   * namespace, never with {@code ~} / {@code @}.
   */
  static boolean isReservedHasKey(String key) {
    return key.startsWith(RESERVED_ALIAS_PREFIX)
        || key.startsWith(HIDDEN_KEY_PREFIX)
        || key.startsWith(RECORD_ATTRIBUTE_PREFIX);
  }

  /** Anonymous-vertex alias sequence ({@code $g2m_anon_0}, {@code $g2m_anon_1}, …), minted by
   *  {@link #nextAnonVertexAlias()}. Per-context: a fresh {@link WalkerContext} per walk restarts
   *  it at 0, so the sequence is deterministic per query rather than monotonic across the JVM. */
  private final AliasSequence anonVertexAliases = new AliasSequence(ANON_VERTEX_ALIAS_PREFIX);

  /** Anonymous-edge alias sequence ({@code $g2m_edge_0}, {@code $g2m_edge_1}, …), minted by
   *  {@link #nextEdgeAlias()}; see {@link #anonVertexAliases}. */
  private final AliasSequence edgeAliases = new AliasSequence(EDGE_ALIAS_PREFIX);

  WalkerContext(Traversal.Admin<?, ?> traversal, boolean polymorphic) {
    this.traversal = traversal;
    this.polymorphic = polymorphic;
  }

  /** Mints the next anonymous vertex alias ({@code $g2m_anon_0}, {@code $g2m_anon_1}, …). Each
   *  call returns a fresh alias and advances the per-context counter, so a multi-hop chain gets
   *  distinct intermediate-node names. */
  String nextAnonVertexAlias() {
    return anonVertexAliases.next();
  }

  /** Mints the next anonymous edge alias ({@code $g2m_edge_0}, {@code $g2m_edge_1}, …). Each call
   *  returns a fresh alias and advances the per-context counter. */
  String nextEdgeAlias() {
    return edgeAliases.next();
  }

  /**
   * Prefixed monotonic alias generator: one instance per alias namespace. Each {@link #next()}
   * returns {@code prefix + n} and advances the counter, so a namespace's aliases are distinct and
   * ordered ({@code prefix0}, {@code prefix1}, …). Reset is by construction — the enclosing {@link
   * WalkerContext} is rebuilt per walk, so each sequence restarts at 0 and stays deterministic per
   * query rather than monotonic across the JVM.
   */
  private static final class AliasSequence {

    private final String prefix;
    private int n;

    AliasSequence(String prefix) {
      this.prefix = prefix;
    }

    String next() {
      return prefix + n++;
    }
  }
}
