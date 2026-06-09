/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Node;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFunctionCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode;
import java.util.Locale;
import javax.annotation.Nonnull;

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
 *       item is one of the recognised scalar aggregates over a plain property. The classifier returns
 *       the final {@code AGGREGATE_*} value, but the aggregate delta path lands in a later track, so
 *       the session bypasses these (uncached) for now.
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

    var aggregate = singleAggregateShape(select.getProjection());
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
   */
  private static CacheableShape singleAggregateShape(SQLProjection projection) {
    if (projection == null) {
      return null;
    }
    var items = projection.getItems();
    if (items == null || items.size() != 1) {
      return null;
    }
    var call = topLevelFunctionCall(items.getFirst());
    if (call == null) {
      return null;
    }
    return aggregateShapeForCall(call);
  }

  /**
   * Returns the outermost (closest-to-root in pre-order) {@link SQLFunctionCall} in the projection
   * item's subtree, or {@code null} if the item carries no function call. For a clean single-aggregate
   * item such as {@code count(*)} or {@code count(distinct(name))}, this is the aggregate call itself;
   * the caller then maps its name to a shape and inspects its arguments for the DISTINCT form. A
   * function call buried under arithmetic (e.g. {@code count(*) + 1}) is also returned, but such a
   * shape is bypassed (uncached) in this foundation regardless, so the looser match is harmless here.
   */
  private static SQLFunctionCall topLevelFunctionCall(SQLProjectionItem item) {
    if (item == null) {
      return null;
    }
    return firstFunctionCall(item);
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
   * Maps a recognised aggregate function name to its shape. {@code COUNT(DISTINCT prop)} is the
   * count-distinct shape; a non-DISTINCT count is {@code AGGREGATE_COUNT}. {@code SUM/AVG/MIN/MAX}
   * map directly. Any other function name (a non-aggregate scalar like {@code lower(name)}, or an
   * aggregate this foundation does not model such as {@code median}) returns {@code null}.
   */
  private static CacheableShape aggregateShapeForCall(@Nonnull SQLFunctionCall call) {
    var name = call.getName();
    if (name == null || name.getStringValue() == null) {
      return null;
    }
    var lower = name.getStringValue().toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "count" -> isDistinct(call) ? CacheableShape.AGGREGATE_COUNT_DISTINCT
          : CacheableShape.AGGREGATE_COUNT;
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
