package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Node;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFunctionCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode;
import java.util.Locale;
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
 *   <li><b>MATCH &rarr; {@link CacheableShape#MATCH_TUPLE_MULTI}.</b> Returned for any MATCH
 *       statement here; the MATCH delta path and the single-alias RECORD-fold (Etap A) land in a
 *       later track, so the session bypasses these for now.
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
    if (statement instanceof SQLMatchStatement) {
      // MATCH delta path (multi-alias reconcile + single-alias Etap A RECORD fold) lands in a later
      // track. Returning the final shape value lets the session's bypass decision stay a pure shape
      // check; until the MATCH path is wired, the session routes these to uncached execution.
      return CacheableShape.MATCH_TUPLE_MULTI;
    }
    // INSERT/UPDATE/DELETE and other statements are not idempotent SELECT/MATCH reads and are gated
    // out before classify is reached; treat any unexpected statement conservatively.
    return CacheableShape.K0_NONE;
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
