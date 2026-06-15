package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Node;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFunctionCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maps a parsed statement to the {@link CacheableShape} that drives its delta-reconciliation path.
 * Computed once per entry on the first cache put, off the parsed AST alone (no session, no schema
 * lookup) so it can run on the {@code query()} hot path before any execution plan is built.
 *
 * <p><b>Classification order.</b> The check sequence is deliberate; a query matches the first branch
 * it satisfies:
 *
 * <ol>
 *   <li><b>SKIP / LIMIT &rarr; {@link CacheableShape#K0_NONE}</b>, checked before any shape-specific
 *       test. A paginated query must never reach the {@code RECORD} branch: with {@code ORDER BY} +
 *       {@code LIMIT} the executor materialises only the top-N rows and discards the rest, so an
 *       in-transaction delete of a cached top-N row could not promote row N+1 into view — the cached
 *       prefix is structurally incomplete. Routing such queries to {@code K0_NONE} (re-execute on any
 *       mutation) is the only correct option, so this gate runs first.
 *   <li><b>Plain record SELECT &rarr; {@link CacheableShape#RECORD}.</b> A SELECT with no GROUP BY,
 *       no LET, no UNWIND, a class (not subquery) target, and no aggregate function anywhere in its
 *       projection. Its rows are individual records the delta builder reconciles one at a time.
 *   <li><b>Single-aggregate SELECT &rarr; {@code AGGREGATE_*}.</b> A SELECT whose single projection
 *       item is exactly one of the recognised scalar aggregates over a plain property, with no
 *       arithmetic applied. An aggregate buried under arithmetic ({@code count(*) + 1}) is not the bare
 *       scalar the replay path produces and routes to {@code K0_NONE} instead. Bare {@code COUNT(*)
 *       FROM C} (no WHERE) is hardwired in the planner to an O(1) {@code CountFromClassStep} that the
 *       aggregate side-tap can never reach, so it also routes to {@code K0_NONE}; a {@code COUNT(*)}
 *       with a (non-indexed) WHERE does build the aggregation step and stays {@code AGGREGATE_COUNT}.
 *   <li><b>MATCH &rarr; {@link CacheableShape#RECORD}, {@link CacheableShape#MATCH_TUPLE_MULTI}, or
 *       {@link CacheableShape#K0_NONE}.</b> A MATCH statement routes to {@code K0_NONE} when any shape
 *       the per-tuple delta floor cannot reconcile is present (see {@link #classifyMatch}). Otherwise
 *       a single-alias MATCH (one bound alias, no traversal edge, record-local ORDER BY) folds onto
 *       the {@code RECORD} delta path via a stored {@code returnProjector} (Etap A), and every other
 *       MATCH stays {@code MATCH_TUPLE_MULTI} for the per-tuple delta floor.
 *   <li><b>Everything else &rarr; {@link CacheableShape#K0_NONE}.</b> GROUP BY, LET, UNWIND,
 *       subquery target, expression aggregates — deterministically reproducible but not
 *       record-by-record reconcilable.
 * </ol>
 *
 * <p>Non-deterministic queries are intercepted upstream by {@link NonDeterministicQueryDetector} and
 * never reach this classifier; classify assumes its input is deterministic.
 */
public final class ShapeClassifier {

  private ShapeClassifier() {
  }

  /**
   * The per-shape facts the aggregate cache-miss path needs to build an {@link AggregateState} and shape
   * its output row: the {@code AGGREGATE_*} {@code kind}, the {@code propertyName} the value aggregate
   * reads from each contributing record ({@code null} for {@code COUNT(*)}, which reads no value), and
   * the {@code alias} the single scalar row carries (the projection alias the aggregation step emits).
   */
  public record AggregateMetadata(
      @Nonnull CacheableShape kind, @Nullable String propertyName, @Nonnull String alias) {

  }

  /**
   * Returns the {@link AggregateMetadata} for a statement the aggregate cache miss path can populate, or
   * {@code null} when the statement is not a cacheable single-aggregate shape. The kind matches {@link
   * #classify} exactly: this method returns non-null precisely for the statements {@code classify}
   * returns an {@code AGGREGATE_*} shape for, so a caller that already gated on the shape can derive the
   * metadata without re-classifying. {@code COUNT(DISTINCT prop)} returns {@code null} (it classifies
   * {@code K0_NONE} and never reaches the aggregate path), as do bare {@code COUNT(*)} and every
   * non-aggregate shape.
   */
  @Nullable public static AggregateMetadata aggregateMetadata(@Nonnull SQLSelectStatement select) {
    var shape = classify(select);
    if (!isAggregateKind(shape)) {
      return null;
    }
    var item = select.getProjection().getItems().getFirst();
    var call = rootAggregateCall(item);
    if (call == null) {
      // classify already proved this is a clean single-aggregate item, so the call is always present;
      // the guard keeps the derivation total in the face of a future classify/derive divergence.
      return null;
    }
    var alias = item.getProjectionAlias().getStringValue();
    var propertyName = call.isStar() ? null : baseIdentifierArg(call);
    return new AggregateMetadata(shape, propertyName, alias);
  }

  private static boolean isAggregateKind(@Nonnull CacheableShape shape) {
    return shape == CacheableShape.AGGREGATE_COUNT
        || shape == CacheableShape.AGGREGATE_SUM
        || shape == CacheableShape.AGGREGATE_AVG
        || shape == CacheableShape.AGGREGATE_MIN
        || shape == CacheableShape.AGGREGATE_MAX
        || shape == CacheableShape.AGGREGATE_COUNT_DISTINCT;
  }

  /**
   * The property name a single-argument value aggregate reads, when its argument is a bare property
   * reference ({@code SUM(price)} &rarr; {@code price}); {@code null} for anything else (no argument, a
   * computed-expression argument, multiple arguments). A computed or multi-argument aggregate classifies
   * {@code K0_NONE} upstream, so a {@code null} here on a shape that reached the aggregate path means the
   * caller falls back to uncached execution.
   */
  @Nullable private static String baseIdentifierArg(@Nonnull SQLFunctionCall call) {
    var params = call.getParams();
    if (params == null || params.size() != 1) {
      return null;
    }
    return basePropertyName(params.getFirst());
  }

  /**
   * The bare property name an aggregate argument expression refers to, or {@code null} when the argument
   * is anything other than a single base identifier. {@code SQLExpression.getDefaultAlias} yields the
   * identifier text for a base-property expression (e.g. {@code price}); a computed expression has no
   * such base alias and yields {@code null}.
   */
  @Nullable private static String basePropertyName(@Nonnull SQLExpression arg) {
    var defaultAlias = arg.getDefaultAlias();
    if (defaultAlias == null) {
      return null;
    }
    return defaultAlias.getStringValue();
  }

  public static CacheableShape classify(@Nonnull SQLStatement statement) {
    if (statement instanceof SQLSelectStatement select) {
      return classifySelect(select);
    }
    if (statement instanceof SQLMatchStatement match) {
      return classifyMatch(match);
    }
    // INSERT/UPDATE/DELETE and other statements are not idempotent SELECT/MATCH reads and are gated
    // out before classify is reached; treat any unexpected statement conservatively.
    return CacheableShape.K0_NONE;
  }

  /**
   * A class or edge label is statically resolvable from the AST alone when it renders as a plain
   * identifier ({@code class:OUser}) or a quoted string literal ({@code out('member')}). A
   * parameterized label ({@code class: :type}, {@code out(:edgeType)}) renders with a {@code ?} or
   * {@code :} marker, and a computed label renders with operators or punctuation; both fail this
   * pattern and route to {@code K0_NONE} rather than seeding a wrong or empty class closure at entry
   * construction. The quoted alternative forbids an interior quote so it matches a single complete
   * string literal, not two concatenated tokens.
   */
  private static final Pattern STATIC_LABEL =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|'[^']*'|\"[^\"]*\"");

  /**
   * Classifies a MATCH statement into {@link CacheableShape#RECORD} (the single-alias Etap-A fold),
   * {@link CacheableShape#MATCH_TUPLE_MULTI}, or {@link CacheableShape#K0_NONE}. The {@code
   * MATCH_TUPLE_MULTI} delta floor reconciles vertex DELETE and
   * pass&rarr;fail UPDATE incrementally and tombstones the rest; it carries no mutation-version
   * backstop, so any MATCH shape the floor cannot reconcile must route to the version-gated {@code
   * K0_NONE} path here. A missed gate would silently serve a stale tuple set, so the gate is
   * deliberately broad: every check below over-approximates toward {@code K0_NONE} (which always
   * re-executes on any mutation and is therefore correctness-safe) rather than risk admitting an
   * unreconcilable shape.
   *
   * <p>This method is schema-free: it reads the parsed AST only, with no session or schema lookup, so
   * it can run on the {@code query()} hot path. Checks that need schema resolution or populate-time
   * record counts (the {@code n + m <= maxRecordsPerEntry} cap) are deferred to entry construction,
   * where the session and schema are available.
   *
   * <p>The gates, in order:
   *
   * <ol>
   *   <li><b>SKIP or LIMIT</b> (first, mirroring {@link #classifySelect}): a paginated prefix cannot
   *       be repaired by a per-tuple delta, so a dropped in-window tuple would emit the wrong
   *       cardinality.
   *   <li><b>GROUP BY, UNWIND, RETURN DISTINCT, or NOT MATCH</b>: a per-tuple skip/inject delta
   *       cannot reconcile any of these.
   *   <li><b>RETURN is not the alias-keyed form</b>: {@code $elements}, {@code $pathElements}, {@code
   *       $patterns}/{@code $matches}, {@code $paths} flatten the row away from the alias-keyed tuple
   *       the delta path and Etap-A projector assume.
   *   <li><b>A subquery target in any pattern WHERE</b>: the inner result is opaque to the per-tuple
   *       delta. (MATCH has no LET clause in the grammar, so there is no LET gate.)
   *   <li><b>Any vertex node missing {@code class:}</b>, or a non-statically-resolvable class or edge
   *       label: an unconstrained or parameter-resolved label cannot seed a class closure from the
   *       AST alone.
   *   <li><b>A cross-alias-state WHERE</b> (a {@code $matched.otherAlias} reference, detected via
   *       {@link SQLWhereClause#getMatchPatternInvolvedAliases}).
   *   <li><b>A link-path-dereference WHERE</b> ({@code where:(assignee.name = ?)}): a dotted path
   *       whose head is a property/link rather than the bound alias dereferences into a class outside
   *       the pattern's read set, whose mutation the delta build's class filter would otherwise drop.
   *   <li><b>A variable-depth or optional node</b> ({@code while:}, {@code maxDepth:}, {@code
   *       optional:}): a {@code while:}/{@code maxDepth:} traversal builds a transitive-closure tuple
   *       set whose membership depends on multi-hop reachability, which the direct-membership {@code
   *       reverseIndex} floor cannot reconcile when an intermediate vertex on a path is deleted; an
   *       {@code optional:} node binds a null alias value the alias-keyed tuple path has no RID to
   *       index. Gating on node presence also closes the {@code while:} predicate as a WHERE escape
   *       hatch: the link-deref/cross-alias/subquery gates run only against the {@code where:} clause
   *       ({@link SQLMatchFilter#getFilter}), never the separate {@code while:} predicate ({@link
   *       SQLMatchFilter#getWhileCondition}), so a node carrying a {@code while:} is routed to {@code
   *       K0_NONE} before any unreconcilable predicate inside it could be missed.
   * </ol>
   */
  private static CacheableShape classifyMatch(@Nonnull SQLMatchStatement match) {
    // SKIP/LIMIT first, for the same paginated-prefix reason as classifySelect: a cached top-N tuple
    // set cannot promote tuple N+1 after an in-tx drop.
    if (match.getSkip() != null || match.getLimit() != null) {
      return CacheableShape.K0_NONE;
    }

    // Shapes the per-tuple delta cannot reconcile: grouping/unwind fan-out, DISTINCT collapse, and a
    // NOT MATCH anti-join whose membership a tuple skip/inject cannot maintain.
    if (match.getGroupBy() != null
        || match.getUnwind() != null
        || match.isReturnDistinct()
        || !match.getNotMatchExpressions().isEmpty()) {
      return CacheableShape.K0_NONE;
    }

    // RETURN must be the alias-keyed form. The $elements/$pathElements/$patterns/$matches/$paths
    // special forms flatten the row to one element with no alias keys, breaking the alias-keyed tuple
    // assumption (getProperty(alias), reverseIndex, the Etap-A returnProjector).
    if (match.returnsElements()
        || match.returnsPathElements()
        || match.returnsPatterns()
        || match.returnsPaths()) {
      return CacheableShape.K0_NONE;
    }

    for (var expr : match.getMatchExpressions()) {
      if (matchExpressionForcesK0None(expr)) {
        return CacheableShape.K0_NONE;
      }
    }

    // Single-alias split (Etap A): a MATCH that binds exactly one alias with no traversal edge folds
    // onto the RECORD delta path. The entry stores the raw bound records and replays them through a
    // returnProjector that reproduces the RETURN tuple at the view emit boundary, so the RECORD
    // skip-set / sorted-merge stay RID-addressable. A multi-alias or edge-bearing MATCH cannot fold
    // this way and stays MATCH_TUPLE_MULTI for the per-tuple delta floor.
    if (isSingleAliasRecordFold(match)) {
      return CacheableShape.RECORD;
    }

    return CacheableShape.MATCH_TUPLE_MULTI;
  }

  /**
   * True when the MATCH is the single-alias shape the Etap-A RECORD fold handles: exactly one match
   * expression whose origin vertex binds a class and carries no traversal step (no {@code .out/.in/...}
   * edge), and whose RETURN / ORDER BY are reducible to the single bound record. The K0_NONE gates in
   * {@link #classifyMatch} have already run, so the origin's class is statically resolvable and its
   * WHERE carries no cross-alias / link-deref / subquery escape; this method only adds the
   * single-binding shape test plus the projectable-ORDER-BY test.
   *
   * <p><b>ORDER BY projectability.</b> The RECORD fold sorts by projecting both merge heads to the
   * RETURN tuple and comparing them, so every ORDER BY item must resolve against a column the projected
   * tuple carries: a path whose head is the bound alias ({@code ORDER BY u.name}, present in the tuple
   * as the {@code u.name} column). An ORDER BY over a foreign head, a computed projection alias, a RID
   * sort, or a bare record attribute ({@code @rid}/{@code @class}/{@code @version}/…) cannot be read
   * from the projected tuple (the tuple is non-identifiable and keyed only by the RETURN columns), so it
   * keeps the statement on the {@code MATCH_TUPLE_MULTI} path (currently served uncached) rather than
   * producing a mis-sorted RECORD merge.
   */
  private static boolean isSingleAliasRecordFold(@Nonnull SQLMatchStatement match) {
    var expressions = match.getMatchExpressions();
    if (expressions.size() != 1) {
      return false;
    }
    var expr = expressions.getFirst();
    var origin = expr.getOrigin();
    if (origin == null || origin.getClassName(null) == null) {
      return false;
    }
    // A traversal step binds a second alias / an edge: not the single-binding shape.
    if (!expr.getItems().isEmpty()) {
      return false;
    }
    return orderByIsAliasLocal(match.getOrderBy(), origin.getAlias());
  }

  /**
   * True when every ORDER BY item resolves against the single bound record by reading a column the
   * projected RETURN tuple carries: an alias-headed path whose head is {@code boundAlias} (so the
   * projected tuple keeps that column, e.g. {@code u.name}). A {@code null} ORDER BY (no sort) is
   * trivially local. Any other item disqualifies the RECORD fold and keeps the statement on the
   * {@code MATCH_TUPLE_MULTI} path.
   *
   * <p>A RID sort and a record-attribute sort ({@code @rid}, {@code @class}, {@code @version}, …) are
   * both rejected for the same reason: the view sorts by projecting both merge heads to the RETURN
   * tuple and comparing them, but that tuple is a non-identifiable {@code ResultInternal} keyed only by
   * the RETURN columns ({@code u}, {@code u.name}), so {@code getProperty("@rid")} / a RID read resolves
   * to {@code null} on every head. The comparator would treat every row as equal and emit in cache /
   * inject order, mis-sorting vs a fresh MATCH that orders on the raw record's attribute before the
   * final projection. Admitting such an ORDER BY would therefore produce a silently wrong order, so it
   * stays on the per-tuple {@code MATCH_TUPLE_MULTI} floor rather than folding to RECORD.
   */
  private static boolean orderByIsAliasLocal(@Nullable SQLOrderBy orderBy,
      @Nullable String boundAlias) {
    if (orderBy == null || orderBy.getItems() == null) {
      return true;
    }
    for (var item : orderBy.getItems()) {
      if (item.getRid() != null) {
        // A RID sort is not a record property read on the projected tuple.
        return false;
      }
      var alias = item.getAlias();
      if (alias != null) {
        // Alias-headed path (e.g. u.name): record-local only when the head is the single bound alias.
        if (!alias.equals(boundAlias)) {
          return false;
        }
        continue;
      }
      // Anything that is not an alias-headed path — a bare record attribute (@rid/@class/@version/…) or
      // a bare identifier — cannot be resolved on the projected RETURN tuple, so it disqualifies the
      // fold. (A bare record attribute would read null on the projected tuple and mis-sort.)
      return false;
    }
    return true;
  }

  /**
   * Runs every per-pattern-expression K0_NONE gate over one {@link SQLMatchExpression}: the origin
   * vertex node, each traversal step's edge label, and each step's target vertex node. Returns {@code
   * true} as soon as any gate fires, so the caller routes the whole statement to {@code K0_NONE}.
   */
  private static boolean matchExpressionForcesK0None(@Nonnull SQLMatchExpression expr) {
    if (vertexNodeForcesK0None(expr.getOrigin())) {
      return true;
    }
    for (var item : expr.getItems()) {
      if (edgeLabelForcesK0None(item) || vertexNodeForcesK0None(item.getFilter())) {
        return true;
      }
    }
    return false;
  }

  /**
   * A vertex node forces {@code K0_NONE} when it declares no statically-resolvable {@code class:}, when
   * it carries a variable-depth ({@code while:}/{@code maxDepth:}) or {@code optional:} modifier, or
   * when its pattern WHERE references another alias's state ({@code $matched.x}) or dereferences a
   * link into an out-of-pattern class. {@code getClassName(null)} is the context-free read of the
   * declared class; it returns {@code null} when no {@code class:} is present and the rendered label
   * otherwise, which {@link #STATIC_LABEL} then tests for static resolvability.
   */
  private static boolean vertexNodeForcesK0None(SQLMatchFilter node) {
    if (node == null) {
      // A path item with no target filter binds no vertex node; nothing to gate on it here.
      return false;
    }
    // Variable-depth (while:/maxDepth:) and optional: nodes are unreconcilable by the per-tuple floor:
    // a transitive-closure traversal's membership depends on multi-hop reachability the direct-
    // membership reverseIndex cannot repair when an intermediate vertex is deleted, and an optional
    // node binds a null alias value with no RID to index. Gating on node presence also routes any
    // while:-bearing node to K0_NONE before its separate while: predicate (never seen by the where:-
    // side link-deref/cross-alias/subquery gates below) could smuggle an unreconcilable predicate past
    // the gate. Over-approximating these to K0_NONE is correctness-safe.
    if (node.getWhileCondition() != null || node.getMaxDepth() != null || node.isOptional()) {
      return true;
    }
    var className = node.getClassName(null);
    if (className == null || !STATIC_LABEL.matcher(className).matches()) {
      return true;
    }
    var where = node.getFilter();
    if (where == null) {
      return false;
    }
    // A subquery embedded in the pattern WHERE produces a result opaque to the per-tuple delta; route
    // it to the version gate. (MATCH has no LET clause in the grammar, so there is no separate LET
    // gate to apply alongside this one.)
    if (subtreeHasSubquery(where)) {
      return true;
    }
    // Cross-alias-state WHERE: a $matched.<otherAlias> reference the per-tuple delta cannot re-evaluate
    // against a single mutated record.
    if (whereReferencesOtherAlias(where)) {
      return true;
    }
    // Link-path-dereference WHERE: a dotted path whose head is not the bound alias reaches into an
    // out-of-pattern record whose mutation the class filter would otherwise drop.
    return whereHasLinkPathDeref(where, node.getAlias());
  }

  /**
   * True when a path item's traversal edge label is not statically resolvable from the AST. The edge
   * label is the first parameter of the step's {@link SQLMethodCall}; the parser folds a bare {@code
   * out()} to the literal {@code "E"}, so a resolvable step always carries at least one literal-label
   * parameter. A parameterized label ({@code out(:edgeType)}) cannot seed an edge-class closure from
   * the AST alone and routes to {@code K0_NONE}; the closure itself is extracted at entry
   * construction. Multi-label steps ({@code out('E1','E2')}) pass only when every label is static.
   */
  private static boolean edgeLabelForcesK0None(@Nonnull SQLMatchPathItem item) {
    var method = item.getMethod();
    if (method == null) {
      return false;
    }
    var params = method.getParams();
    if (params == null || params.isEmpty()) {
      // A traversal step with no label is not statically resolvable to a specific edge class; the
      // parser normally folds a bare out() to "E", so an empty param list is the conservative reject.
      return true;
    }
    for (var param : params) {
      if (!isStaticLabel(param)) {
        return true;
      }
    }
    return false;
  }

  /** True when the expression renders as a bare static identifier (a literal class or edge label). */
  private static boolean isStaticLabel(@Nonnull SQLExpression label) {
    var rendered = label.toString();
    return STATIC_LABEL.matcher(rendered).matches();
  }

  /** Pre-order walk for any embedded {@link SQLStatement} (a subquery) under {@code node}. */
  private static boolean subtreeHasSubquery(Node node) {
    if (node instanceof SQLStatement) {
      return true;
    }
    var childCount = node.jjtGetNumChildren();
    for (var i = 0; i < childCount; i++) {
      var child = node.jjtGetChild(i);
      if (child instanceof SimpleNode && subtreeHasSubquery(child)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when the WHERE references another pattern alias's state via {@code $matched.<alias>}. {@link
   * SQLBooleanExpression#getMatchPatternInvolvedAliases} already recurses through the whole boolean
   * tree under the WHERE's root expression and returns the full set of {@code $matched}-referenced
   * aliases, so a single call on the root answers the question without an outer per-node walk; a
   * non-empty set is a cross-alias-state predicate. (A manual pre-order walk that called the method at
   * every descended boolean node re-did the descent the method already performs, an O(n^2) re-walk.)
   */
  private static boolean whereReferencesOtherAlias(@Nonnull SQLWhereClause where) {
    var root = where.getBaseExpression();
    if (root == null) {
      return false;
    }
    var aliases = root.getMatchPatternInvolvedAliases();
    return aliases != null && !aliases.isEmpty();
  }

  /**
   * True when the WHERE contains a dotted path whose head identifier is neither the bound alias nor a
   * context variable, indicating a dereference into a linked (out-of-pattern) record. A bare property
   * ({@code title}) carries no dereference modifier; a qualified own-property ({@code i.title} on
   * alias {@code i}) has the bound alias as its head; only a foreign head with a {@code .suffix}
   * dereference ({@code assignee.name}) reaches outside the pattern's read set. This walk is the
   * dedicated mechanism the cross-alias check ({@link #whereReferencesOtherAlias}) does not cover.
   */
  private static boolean whereHasLinkPathDeref(@Nonnull SQLWhereClause where, String boundAlias) {
    return subtreeHasLinkPathDeref(where, boundAlias);
  }

  /** Pre-order walk for any {@link SQLBaseExpression} that dereferences a non-bound-alias head. */
  private static boolean subtreeHasLinkPathDeref(Node node, String boundAlias) {
    if (node instanceof SQLBaseExpression base
        && baseExpressionDerefsForeignHead(base, boundAlias)) {
      return true;
    }
    var childCount = node.jjtGetNumChildren();
    for (var i = 0; i < childCount; i++) {
      var child = node.jjtGetChild(i);
      if (child instanceof SimpleNode && subtreeHasLinkPathDeref(child, boundAlias)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when a base expression dereferences (carries a {@code .suffix} / {@code [..]} / {@code
   * .method()} modifier chain) and its head identifier is a foreign property rather than the bound
   * alias or a context variable. The head is read from {@code toString()} up to the first dereference
   * separator; a {@code $}-prefixed head is a context/system variable, not a link into another class,
   * and is left to the cross-alias check.
   */
  private static boolean baseExpressionDerefsForeignHead(@Nonnull SQLBaseExpression base,
      String boundAlias) {
    var rendered = base.toString();
    if (rendered.isEmpty() || isLiteralHead(rendered.charAt(0))) {
      // A string or numeric literal ('a.b', 12.5, ?) is not a property path; a dot or bracket inside
      // it is part of the literal, not a dereference separator, so it cannot reach out of pattern.
      return false;
    }
    var sepIndex = firstDerefSeparator(rendered);
    if (sepIndex < 0) {
      // No dereference: a bare own-property reference (e.g. "title"); nothing reaches out of pattern.
      return false;
    }
    var head = rendered.substring(0, sepIndex);
    if (head.isEmpty() || head.charAt(0) == '$') {
      // A $-variable head ($matched, $parent, $current, ...) is handled by the cross-alias gate, not
      // here; it is not a plain link dereference into another class.
      return false;
    }
    // A dotted path whose head is the bound alias (e.g. "i.title" on alias i) is a qualified reference
    // to the bound record's own property, not a link into another class; only a foreign head derefs.
    return !head.equals(boundAlias);
  }

  /**
   * True when a rendered base expression begins with a literal marker (a string-literal quote, a
   * digit, a sign, or a {@code ?} positional parameter) rather than an identifier start. Such a leaf
   * carries no property head, so it cannot be a link dereference.
   */
  private static boolean isLiteralHead(char first) {
    return first == '\'' || first == '"' || first == '?' || first == '-' || first == '+'
        || (first >= '0' && first <= '9');
  }

  /**
   * Index of the first dereference separator ({@code .} or {@code [}) in a rendered base expression,
   * or {@code -1} when the expression is a bare identifier with no dereference.
   */
  private static int firstDerefSeparator(@Nonnull String rendered) {
    var dot = rendered.indexOf('.');
    var bracket = rendered.indexOf('[');
    if (dot < 0) {
      return bracket;
    }
    if (bracket < 0) {
      return dot;
    }
    return Math.min(dot, bracket);
  }

  private static CacheableShape classifySelect(@Nonnull SQLSelectStatement select) {
    // SKIP/LIMIT first: a paginated result's cached prefix cannot be repaired by a per-record delta
    // (see the class-level note), so it must fall to the version-gated K0_NONE path regardless of any
    // other feature, including an otherwise-RECORD shape.
    if (select.getSkip() != null || select.getLimit() != null) {
      return CacheableShape.K0_NONE;
    }

    // Shapes the record delta builder cannot reconcile incrementally, but whose result is still
    // deterministically reproducible from storage + AST: grouping, LET bindings, UNWIND fan-out, and
    // subquery targets all route to the version-gated K0_NONE path.
    if (select.getGroupBy() != null
        || select.getLetClause() != null
        || select.getUnwind() != null
        || hasSubqueryTarget(select)) {
      return CacheableShape.K0_NONE;
    }

    var aggregate = singleAggregateShape(select.getProjection(), select.getWhereClause() != null);
    if (aggregate != null) {
      return aggregate;
    }

    // A projection that mixes an aggregate with other items, or carries an aggregate over an
    // expression, is not a clean single-aggregate shape; it cannot be a plain RECORD either. Such a
    // projection is delta-irreconcilable, so it falls to K0_NONE.
    if (projectionContainsAggregate(select.getProjection())) {
      return CacheableShape.K0_NONE;
    }

    return CacheableShape.RECORD;
  }

  /** A target whose FROM item is itself a parsed statement is a subquery; routes to K0_NONE. */
  private static boolean hasSubqueryTarget(@Nonnull SQLSelectStatement select) {
    var target = select.getTarget();
    if (target == null) {
      return false;
    }
    var item = target.getItem();
    return item != null && item.getStatement() != null;
  }

  /**
   * Returns the {@code AGGREGATE_*} shape when the projection is exactly one recognised scalar
   * aggregate over a plain property (or {@code COUNT(*)}), otherwise {@code null}. A projection with
   * more than one item, or an aggregate wrapping an expression, is not a clean single-aggregate shape
   * and returns {@code null} so the caller can route it to RECORD or K0_NONE as appropriate.
   *
   * <p>{@code hasWhereClause} distinguishes the bare {@code COUNT(*) FROM C} shape (hardwired and
   * untappable, routed to K0_NONE) from {@code COUNT(*) FROM C WHERE non-indexed-predicate} (which
   * builds the aggregation step and is tappable, so it stays {@code AGGREGATE_COUNT}).
   */
  private static CacheableShape singleAggregateShape(SQLProjection projection,
      boolean hasWhereClause) {
    if (projection == null) {
      return null;
    }
    var items = projection.getItems();
    if (items == null || items.size() != 1) {
      return null;
    }
    var call = rootAggregateCall(items.getFirst());
    if (call == null) {
      return null;
    }
    var shape = aggregateShapeForCall(call);
    if (shape == CacheableShape.AGGREGATE_COUNT && isDistinct(call)) {
      // COUNT(DISTINCT prop) is routed to K0_NONE rather than a distinct-count aggregate shape: the
      // engine has no native distinct-count and computes count(distinct(prop)) as a plain row count, so
      // the K0 version gate (re-execute on any mutation) already reproduces the engine's result exactly,
      // and modelling a true distinct-count in the aggregate replay would diverge from that native
      // semantics. The AggregateState COUNT_DISTINCT machinery is retained for a future engine with a
      // true distinct-count, but no production path reaches it in v1.
      return CacheableShape.K0_NONE;
    }
    if (shape == CacheableShape.AGGREGATE_COUNT && call.isStar() && !hasWhereClause) {
      // Bare COUNT(*) FROM C (no WHERE) is hardwired in the planner to a CountFromClassStep before any
      // AggregateProjectionCalculationStep is built, so the aggregate side-tap can never reach it.
      // It is already O(1) and transaction-aware, so caching adds nothing. Route it to K0_NONE so the
      // untappable shape never enters the aggregate replay path. The single-field-indexed
      // COUNT(*) ... WHERE indexedField = ? shape is also hardwired, but its detection needs index
      // metadata the AST-only classifier cannot see, so that shape rides the aggregate splice fallback
      // (the splice finds a CountFromClassStep, not an aggregation step, and falls back uncached).
      return CacheableShape.K0_NONE;
    }
    return shape;
  }

  /**
   * Returns the aggregate {@link SQLFunctionCall} when the projection item's expression is exactly one
   * function call with no arithmetic applied, otherwise {@code null}. For a clean single-aggregate item
   * such as {@code count(*)} or {@code count(distinct(name))}, this is the aggregate call itself; the
   * caller then maps its name to a shape and inspects its arguments for the DISTINCT form.
   *
   * <p>An aggregate buried under arithmetic — {@code count(*) + 1}, {@code sum(price) * 2} — returns
   * {@code null}: the arithmetic result is not the bare aggregate scalar the side-tap and {@code
   * AggregateState} replay produce, so caching it as an {@code AGGREGATE_*} shape would replay the wrong
   * value. Routing it to {@code null} here lets the caller fall through to the {@code
   * projectionContainsAggregate} branch, which classifies it K0_NONE (deterministically reproducible,
   * served only under the mutation-version gate). The descent rejects the item the moment it crosses a
   * math node that applies an operator, so the arithmetic is detected without reading the (package-
   * private) function-call leaf.
   */
  private static SQLFunctionCall rootAggregateCall(SQLProjectionItem item) {
    if (item == null) {
      return null;
    }
    var expression = item.getExpression();
    if (expression == null) {
      return null;
    }
    var math = expression.getMathExpression();
    if (math == null) {
      return null;
    }
    return rootCallWithoutArithmetic(math);
  }

  /**
   * Descends a {@link SQLMathExpression}, returning the root {@link SQLFunctionCall} only when no
   * arithmetic operator is applied anywhere on the path down to it. Any math node carrying a non-empty
   * operator list (an actual {@code +}/{@code -}/{@code *}/... arithmetic combination) means the item is
   * an expression over the aggregate, not the aggregate itself, so the method returns {@code null}.
   * Single-operand wrapper math nodes (grammar artefacts with no operator) are transparently descended.
   * Once the descent reaches a base expression (a {@link SQLMathExpression} that holds no further child
   * math expressions), the JJTree subtree is scanned for the function call.
   */
  private static SQLFunctionCall rootCallWithoutArithmetic(SQLMathExpression math) {
    var operators = math.getOperators();
    if (operators != null && !operators.isEmpty()) {
      // Arithmetic is applied at this level (e.g. count(*) + 1); this is not a bare aggregate.
      return null;
    }
    var children = math.getChildExpressions();
    if (children != null && children.size() == 1) {
      // A single-child math node with no operator is a transparent wrapper; descend into it.
      return rootCallWithoutArithmetic(children.getFirst());
    }
    if (children != null && children.size() > 1) {
      // More than one operand without an operator should not occur, but if it does it is not a clean
      // single aggregate; treat it conservatively as non-aggregate.
      return null;
    }
    // Leaf base expression: its subtree holds the function call (if any) directly, with no arithmetic
    // above it. firstFunctionCall finds count/sum/... and, for COUNT(DISTINCT), the outer count call.
    return firstFunctionCall(math);
  }

  /** Pre-order search for the first {@link SQLFunctionCall} in {@code node}'s subtree. */
  private static SQLFunctionCall firstFunctionCall(Node node) {
    if (node instanceof SQLFunctionCall call) {
      return call;
    }
    var childCount = node.jjtGetNumChildren();
    for (var i = 0; i < childCount; i++) {
      var child = node.jjtGetChild(i);
      if (child instanceof SimpleNode) {
        var nested = firstFunctionCall(child);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  /**
   * Maps a recognised aggregate function name to its shape. Every {@code count} (DISTINCT or not) maps
   * to {@code AGGREGATE_COUNT} here; the DISTINCT form is split off to {@code K0_NONE} by {@link
   * #singleAggregateShape}, while keeping the name recognised so {@link #projectionContainsAggregate}
   * still treats it as an aggregate. {@code SUM/AVG/MIN/MAX} map directly. Any other function name (a
   * non-aggregate scalar like {@code lower(name)}, or an aggregate this foundation does not model such
   * as {@code median}) returns {@code null}.
   */
  private static CacheableShape aggregateShapeForCall(@Nonnull SQLFunctionCall call) {
    var name = call.getName();
    if (name == null || name.getStringValue() == null) {
      return null;
    }
    var lower = name.getStringValue().toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "count" -> CacheableShape.AGGREGATE_COUNT;
      case "sum" -> CacheableShape.AGGREGATE_SUM;
      case "avg" -> CacheableShape.AGGREGATE_AVG;
      case "min" -> CacheableShape.AGGREGATE_MIN;
      case "max" -> CacheableShape.AGGREGATE_MAX;
      default -> null;
    };
  }

  /** {@code COUNT(DISTINCT prop)} parses the DISTINCT argument as a nested {@code distinct(...)} call. */
  private static boolean isDistinct(@Nonnull SQLFunctionCall call) {
    for (var param : call.getParams()) {
      var nested = firstFunctionCall(param);
      if (nested != null
          && nested.getName() != null
          && "distinct".equalsIgnoreCase(nested.getName().getStringValue())) {
        return true;
      }
    }
    return false;
  }

  /** True if any function call recognised as an aggregate appears anywhere in the projection. */
  private static boolean projectionContainsAggregate(SQLProjection projection) {
    if (projection == null || projection.getItems() == null) {
      return false;
    }
    for (var item : projection.getItems()) {
      if (subtreeContainsAggregate(item)) {
        return true;
      }
    }
    return false;
  }

  private static boolean subtreeContainsAggregate(Node node) {
    if (node instanceof SQLFunctionCall call && aggregateShapeForCall(call) != null) {
      return true;
    }
    var childCount = node.jjtGetNumChildren();
    for (var i = 0; i < childCount; i++) {
      var child = node.jjtGetChild(i);
      if (child instanceof SimpleNode && subtreeContainsAggregate(child)) {
        return true;
      }
    }
    return false;
  }
}
