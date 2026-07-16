package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * The full walk state {@link GremlinStepWalker} owns and recognisers contribute to. It implements
 * {@link RecognitionContext}, the narrow view handed to recognisers: they reach the fields here only
 * through that interface's named methods, while the walker reads the fields directly to build the
 * result. The walker creates one context per walk.
 *
 * <h2>No mutation discipline</h2>
 *
 * A recogniser may contribute in any order. A {@link Outcome#DECLINE} makes the walker discard the
 * whole context (and its cursor), so a partial contribution never leaks — see
 * {@link RecognitionContext}.
 */
final class WalkerContext implements RecognitionContext {

  /** Pattern under construction. Recognisers contribute through {@link #addNode} / {@link #addEdge} /
   *  {@link #addEdgeAsNode}; the walker calls {@code build()} once at the end of a successful walk. */
  final MatchPatternBuilder patternBuilder = new MatchPatternBuilder();

  /** Per-alias WHERE clauses contributed outside the pattern builder (e.g. {@code @rid IN [...]}).
   *  Merged with the builder's own alias filters at result-build time; entries here override builder
   *  entries on the same alias. */
  final Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();

  /** Per-edge-alias WHERE clauses for non-adjacent edge filtering (the {@code outE(L).has(...).inV()}
   *  shape). Populated by {@link #putEdgeFilter} for observability; the same clause also travels on
   *  the edge path item via {@link #addEdgeAsNode}, so it is not re-read at result-build time. */
  final Map<String, SQLWhereClause> edgeFilters = new LinkedHashMap<>();

  /** RETURN-clause projection items. One entry per output column. */
  final List<SQLExpression> returnItems = new ArrayList<>();

  /** {@code AS} aliases for each entry in {@link #returnItems}. Same length, parallel positions;
   *  null entries are allowed when an item has no alias. */
  final List<SQLIdentifier> returnAliases = new ArrayList<>();

  /** Optional nested projections per entry in {@link #returnItems}. Same length, parallel positions;
   *  null entries are allowed when an item has no nested projection. */
  final List<SQLNestedProjection> returnNestedProjections = new ArrayList<>();

  /** Alias under which the matched element appears in each result row. Pinned by the recogniser
   *  owning the traversal's terminator. Required for a successful walk. */
  String boundaryAlias;

  /** How the boundary step projects each result row onto a TinkerPop traverser. Pinned alongside
   *  {@link #boundaryAlias}. Required for a successful walk. */
  BoundaryOutputType outputType;

  /** TinkerPop element class the boundary step emits (e.g. {@code Vertex.class}). Pinned alongside
   *  {@link #boundaryAlias}. Required for a successful walk. */
  Class<? extends Element> returnClass;

  /** Whether the traversal runs as a polymorphic query, resolved once from the traversal's YTDB
   *  session and query options ({@code YTDBStrategyUtil.isPolymorphic}) by {@link GremlinStepWalker}.
   *
   *  <p>The {@code hasLabel(L)} recogniser reads it to pick the boundary-node re-typing (see {@link
   *  RecognitionContext#polymorphic()}): polymorphic re-types to {@code {class: L}} (MATCH matches
   *  subclasses), non-polymorphic re-types to {@code L} plus an exact {@code @class = 'L'} filter.
   *  The vertex-source and bare-hop recognisers root every node at the generic {@code V} class
   *  ({@link #VERTEX_ROOT_CLASS}) regardless of it — native Gremlin never class-filters those
   *  shapes, so narrowing one would drop subclass instances the native pipeline keeps. The
   *  resolution also carries a decline side effect: a {@code null} result declines the whole walk in
   *  the walker. */
  private final boolean polymorphic;

  /** Whether the traversal opts into {@code EdgeLabelVerificationStrategy}, resolved once by
   *  {@link GremlinStepWalker} so {@link GremlinPatternAssembler#resolveEdgeLabel} reads a boolean
   *  rather than scanning the strategy list per hop. */
  private final boolean edgeLabelVerification;

  /** Schema snapshot the walk resolves types against, or {@code null} when the traversal has no
   *  attached YTDB session. Used only by {@link #isNonStringProperty(String, String)} for the
   *  {@code has(...)} recogniser's non-String {@code Text} decline; a {@code null} schema resolves
   *  every property as translate-best-effort (no type gate). */
  @Nullable private final Schema schema;

  /** Stateless builder used to AND-compose same-alias filter contributions in {@link
   *  #putAliasFilter}; construction is trivial so a shared instance is fine. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

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
   * value, though they react differently: {@link GremlinStepWalker}'s reserved-prefix {@code as(...)}
   * label pre-flight <em>rejects</em> a label starting with it (throwing a {@code
   * ReservedAliasException} — a user alias colliding with the minted namespace is prohibited input),
   * while {@link GremlinPredicateAdapter}'s {@code has(...)}-key guard <em>declines</em> to native
   * through {@link #isReservedHasKey(String)}.
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

  /** Convenience constructor with no schema snapshot — used by unit tests that exercise recogniser
   *  logic without a live session. Every property resolves as translate-best-effort (no non-String
   *  {@code Text} type gate). */
  WalkerContext(boolean polymorphic, boolean edgeLabelVerification) {
    this(polymorphic, edgeLabelVerification, null);
  }

  WalkerContext(boolean polymorphic, boolean edgeLabelVerification, @Nullable Schema schema) {
    this.polymorphic = polymorphic;
    this.edgeLabelVerification = edgeLabelVerification;
    this.schema = schema;
  }

  // --- RecognitionContext: resolved flags -------------------------------------------------------

  @Override
  public boolean polymorphic() {
    return polymorphic;
  }

  @Override
  public boolean edgeLabelVerificationEnabled() {
    return edgeLabelVerification;
  }

  // --- RecognitionContext: boundary read --------------------------------------------------------

  @Nullable @Override
  public String boundaryAlias() {
    return boundaryAlias;
  }

  // --- RecognitionContext: schema-aware type gating ---------------------------------------------

  @Override
  public boolean isNonStringProperty(@Nullable String className, String propertyKey) {
    if (schema == null || className == null || propertyKey == null) {
      // No class context or no schema: cannot prove the property is non-String, so translate
      // best-effort (the schema-less / generic-V reality where the value type is unknown).
      return false;
    }
    var clazz = schema.getClass(className);
    if (clazz == null) {
      return false;
    }
    // The named class covers a property declared on className or any supertype: getProperty walks
    // superclasses (per its own contract), so a property inherited by the leaf class is found too.
    if (declaresNonString(clazz, propertyKey)) {
      return true;
    }
    // Polymorphic hasLabel(L) also matches subclasses of L, so an included-subclass row reaches
    // the same predicate. A property declared non-String on a subclass alone -- absent from L and
    // its supertypes -- makes a native Text predicate error on that row, while the translated
    // CONTAINSTEXT silently returns false; that divergence is the escape this branch closes.
    // Non-polymorphic mode adds an exact @class = 'L' leaf filter (see HasStepRecogniser), so
    // subclass rows never reach the predicate and the named-class check above suffices there.
    if (polymorphic) {
      for (var subclass : clazz.getAllSubclasses()) {
        if (declaresNonString(subclass, propertyKey)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether {@code clazz} resolves {@code propertyKey} to a declared, non-String schema type.
   * {@code getProperty} walks superclasses, so a property {@code clazz} inherits counts; an
   * undeclared property (schema-less / mixed) or a String property returns {@code false}.
   */
  private static boolean declaresNonString(SchemaClass clazz, String propertyKey) {
    var property = clazz.getProperty(propertyKey);
    if (property == null) {
      return false;
    }
    var type = property.getType();
    // A declared type other than STRING means every value is non-String, so a native Text predicate
    // errors on it; report non-String so the adapter declines. An undeclared property returns above.
    return type != null && type != PropertyType.STRING;
  }

  @Override
  public boolean isVertexClass(String className) {
    if (schema == null || className == null) {
      // No schema to verify against: decline the re-type so a hasLabel never builds a scan over an
      // unverifiable class (the walker already declines a schema-less traversal, so this is defensive).
      return false;
    }
    var clazz = schema.getClass(className);
    return clazz != null && clazz.isVertexType();
  }

  // --- RecognitionContext: alias minting --------------------------------------------------------

  /** Mints the next anonymous vertex alias ({@code $g2m_anon_0}, {@code $g2m_anon_1}, …). Each call
   *  returns a fresh alias and advances the per-context counter, so a multi-hop chain gets distinct
   *  intermediate-node names. */
  @Override
  public String nextAnonVertexAlias() {
    return anonVertexAliases.next();
  }

  /** Mints the next anonymous edge alias ({@code $g2m_edge_0}, {@code $g2m_edge_1}, …). Each call
   *  returns a fresh alias and advances the per-context counter. */
  @Override
  public String nextEdgeAlias() {
    return edgeAliases.next();
  }

  // --- RecognitionContext: contributions --------------------------------------------------------

  @Override
  public void addNode(String alias, String className) {
    patternBuilder.addNode(alias, className, null, false);
  }

  @Override
  public void addEdge(
      String fromAlias,
      String toAlias,
      MatchPatternBuilder.Direction dir,
      @Nullable String edgeLabel) {
    patternBuilder.addEdge(fromAlias, toAlias, dir, edgeLabel, null, null, null);
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
    patternBuilder.addEdgeAsNode(
        fromAlias, edgeAlias, toAlias, edgeDir, edgeLabel, closingVertexDir, edgeFilter);
  }

  @Override
  public void putAliasFilter(String alias, SQLWhereClause where) {
    var existing = aliasFilters.get(alias);
    if (existing == null) {
      aliasFilters.put(alias, where);
      return;
    }
    // A second contribution to the same alias AND-composes rather than replaces: a has(...)
    // recogniser routinely contributes two clauses to one alias — a g.V(ids) @rid IN then a
    // has(...) predicate, or a hasLabel(L) @class narrowing then a has(...) predicate. Overwriting
    // would silently drop the earlier filter and return a wrong (over-large) multiset.
    var merged = WHERE.and(existing.getBaseExpression(), where.getBaseExpression());
    aliasFilters.put(alias, WHERE.wrap(merged));
  }

  @Override
  public void putEdgeFilter(String edgeAlias, SQLWhereClause where) {
    edgeFilters.put(edgeAlias, where);
  }

  @Override
  public void pinBoundary(String alias, BoundaryOutputType type,
      Class<? extends Element> returnClass) {
    this.boundaryAlias = alias;
    this.outputType = type;
    this.returnClass = returnClass;
  }

  @Override
  public void setSingleReturnColumn(String alias) {
    // Clear first so a re-pin (a chain hop replacing the prior boundary's column) cannot leave a
    // stale column keyed on the previous alias; the three parallel lists stay in lock-step.
    returnItems.clear();
    returnAliases.clear();
    returnNestedProjections.clear();
    returnItems.add(new SQLExpression(new SQLIdentifier(alias)));
    returnAliases.add(new SQLIdentifier(alias));
    returnNestedProjections.add(null);
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
