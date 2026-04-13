package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.index.engine.SelectivityEstimator;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Describes how to use a specific index to evaluate a subset of WHERE conditions.
 *
 * <p>Produced by {@link SelectExecutionPlanner#buildIndexSearchDescriptor} during
 * index selection and consumed by {@link FetchFromIndexStep} during execution.
 *
 * <h2>Structure</h2>
 * <pre>
 *  Given:
 *    Index on [city, age]
 *    WHERE city = 'NYC' AND age &gt; 20 AND name = 'Alice'
 *
 *  IndexSearchDescriptor:
 *    index                    = idx_city_age
 *    keyCondition             = AND[city = 'NYC', age &gt; 20]
 *                               (conditions that form the index key)
 *    additionalRangeCondition = null  (or a second bound on the last key field)
 *    remainingCondition       = AND[name = 'Alice']
 *                               (conditions NOT covered by the index,
 *                                applied as a post-fetch filter)
 * </pre>
 *
 * <h2>Cost estimation</h2>
 * The {@link #cost(CommandContext)} method queries stored index statistics to estimate
 * the I/O cost. The planner uses this to choose the cheapest index among candidates.
 *
 * @see SelectExecutionPlanner#findBestIndexFor
 * @see FetchFromIndexStep
 */
public class IndexSearchDescriptor {

  /** The index to use for the lookup. */
  private final Index index;

  /**
   * The conditions that form the index key (an AND block of equalities / ranges).
   * Passed to the index API as the search key.
   */
  private final SQLBooleanExpression keyCondition;

  /**
   * Optional second range bound on the last key field, used when two range conditions
   * exist on the same field (e.g. {@code age >= 20 AND age < 30}). The first range
   * is in the keyCondition; this is the complementary bound.
   */
  private final SQLBinaryCondition additionalRangeCondition;

  /**
   * WHERE conditions NOT covered by the index key. These must be applied as a
   * post-fetch {@link FilterStep} after the index scan.
   */
  private final SQLBooleanExpression remainingCondition;

  public IndexSearchDescriptor(
      Index idx,
      SQLBooleanExpression keyCondition,
      SQLBinaryCondition additional,
      SQLBooleanExpression remainingCondition) {
    this.index = idx;
    this.keyCondition = keyCondition;
    this.additionalRangeCondition = additional;
    this.remainingCondition = remainingCondition;
  }

  public IndexSearchDescriptor(Index idx) {
    this.index = idx;
    this.keyCondition = null;
    this.additionalRangeCondition = null;
    this.remainingCondition = null;
  }

  public IndexSearchDescriptor(Index idx, SQLBooleanExpression keyCondition) {
    this.index = idx;
    this.keyCondition = keyCondition;
    this.additionalRangeCondition = null;
    this.remainingCondition = null;
  }

  /**
   * Estimates the I/O cost of this index lookup using persistent histogram-based
   * estimation. For unique indexes with exact-match on all fields, returns a
   * cost of 1 (single-row lookup). Returns {@link Integer#MAX_VALUE} if no
   * statistics are available.
   */
  public int cost(CommandContext ctx) {
    if (keyCondition == null) {
      return Integer.MAX_VALUE;
    }

    // Unique index with exact equality on all key fields → single-row lookup.
    // Every sub-block must be an equality condition (SQLEqualsOperator) to
    // guarantee at most one matching row. IN, range, LIKE, and other operators
    // can match multiple rows even on a unique index.
    if (index.isUnique() && additionalRangeCondition == null) {
      var subBlocks = getSubBlocks();
      if (index.getDefinition().getProperties().size() == subBlocks.size()
          && allEquality(subBlocks)) {
        return (int) CostModel.indexEqualityCost(1);
      }
    }

    return estimateFromHistogram(ctx);
  }

  /**
   * Estimates I/O cost using persistent index statistics (histogram + counters)
   * and the {@link CostModel}. Returns a cost value in abstract units (where
   * one sequential page read = 1.0), or {@link Integer#MAX_VALUE} if no
   * statistics are available.
   *
   * <p>Strategy:
   * <ol>
   *   <li>Leading field condition: dispatched to {@link SelectivityEstimator}
   *       using the histogram (if available) or uniform estimation.</li>
   *   <li>Non-leading field conditions (composite indexes): each multiplies
   *       by {@link SelectivityEstimator#defaultSelectivity()} since no
   *       per-field histogram exists.</li>
   *   <li>Additional range condition on the leading field (single-field index
   *       with two bounds): combined into a tighter range estimate.</li>
   *   <li>Estimated row count is converted to I/O cost via
   *       {@link CostModel#indexEqualityCost(long)} (equality) or
   *       {@link CostModel#indexRangeCost(long)} (range).</li>
   * </ol>
   */
  private int estimateFromHistogram(CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var indexStats = index.getStatistics(session);
    if (indexStats == null || indexStats.totalCount() == 0) {
      return Integer.MAX_VALUE;
    }

    double selectivity = computeRawSelectivity(
        indexStats, index.getHistogram(session), ctx);
    if (selectivity < 0) {
      return Integer.MAX_VALUE;
    }

    // Note: selectivity is expressed as a fraction of non-null entries (see
    // SelectivityEstimator Javadoc), but we multiply by totalCount (which
    // includes nulls).  This produces a mild overestimate bounded by the null
    // fraction — an intentional conservative bias that favors full scans in
    // borderline cases rather than picking a bad index.
    long estimatedRows = Math.max(1, (long) (indexStats.totalCount() * selectivity));
    boolean isRange = isRangeEstimate(getSubBlocks(), additionalRangeCondition);
    double cost = isRange
        ? CostModel.indexRangeCost(estimatedRows)
        : CostModel.indexEqualityCost(estimatedRows);
    // Ensure at least cost 1 so histogram-estimated indexes are always
    // distinguishable from "no estimate" (Integer.MAX_VALUE) and from each
    // other when costs are fractional.
    return cost > Integer.MAX_VALUE
        ? Integer.MAX_VALUE : Math.max(1, (int) cost);
  }

  /**
   * Estimates selectivity for a single condition block on the leading field.
   * Returns -1 if the condition cannot be estimated (field mismatch, non-constant
   * value, or unsupported operator).
   */
  private static double estimateBlockSelectivity(
      SQLBooleanExpression expr, String field,
      IndexStatistics stats, @Nullable EquiDepthHistogram histogram,
      CommandContext ctx) {
    if (!(expr instanceof SQLBinaryCondition bc)) {
      return -1;
    }
    if (!bc.getLeft().isBaseIdentifier()
        || !field.equals(bc.getLeft().getDefaultAlias().getStringValue())
        || !bc.getRight().isEarlyCalculated(ctx)) {
      return -1;
    }
    var value = bc.getRight().execute((Result) null, ctx);
    if (value == null) {
      return -1;
    }
    return SelectivityEstimator.estimateForOperator(
        bc.getOperator(), stats, histogram, value);
  }

  /**
   * Combines two range conditions on the same field (e.g., {@code f >= 20}
   * and {@code f < 30}) into a single range selectivity estimate.
   * Returns -1 if the bounds cannot be determined.
   */
  private static double estimateCombinedRange(
      SQLBooleanExpression firstExpr, SQLBinaryCondition additional,
      IndexStatistics stats, @Nullable EquiDepthHistogram histogram,
      CommandContext ctx) {
    if (!(firstExpr instanceof SQLBinaryCondition first)) {
      return -1;
    }
    if (!first.getRight().isEarlyCalculated(ctx)
        || !additional.getRight().isEarlyCalculated(ctx)) {
      return -1;
    }
    var val1 = first.getRight().execute((Result) null, ctx);
    var val2 = additional.getRight().execute((Result) null, ctx);
    if (val1 == null || val2 == null) {
      return -1;
    }

    // Determine from/to based on which operator is the lower vs upper bound.
    var op1 = first.getOperator();
    var op2 = additional.getOperator();

    Object fromKey;
    Object toKey;
    boolean fromInclusive;
    boolean toInclusive;

    if (isLowerBound(op1) && isUpperBound(op2)) {
      fromKey = val1;
      toKey = val2;
      fromInclusive = op1 instanceof SQLGeOperator;
      toInclusive = op2 instanceof SQLLeOperator;
    } else if (isUpperBound(op1) && isLowerBound(op2)) {
      fromKey = val2;
      toKey = val1;
      fromInclusive = op2 instanceof SQLGeOperator;
      toInclusive = op1 instanceof SQLLeOperator;
    } else {
      return -1;
    }

    return SelectivityEstimator.estimateRange(
        stats, histogram, fromKey, toKey, fromInclusive, toInclusive);
  }

  private static boolean isLowerBound(SQLBinaryCompareOperator op) {
    return op instanceof SQLGtOperator || op instanceof SQLGeOperator;
  }

  private static boolean isUpperBound(SQLBinaryCompareOperator op) {
    return op instanceof SQLLtOperator || op instanceof SQLLeOperator;
  }

  /**
   * Returns {@code true} if the estimate represents a range scan rather than
   * an equality seek. An estimate is range-based when the condition on the
   * leading field uses a range operator or when two bounds are combined via
   * {@code additionalRange}.
   */
  private static boolean isRangeEstimate(
      List<SQLBooleanExpression> subBlocks,
      @Nullable SQLBinaryCondition additionalRange) {
    if (additionalRange != null) {
      return true;
    }
    if (!subBlocks.isEmpty()
        && subBlocks.getFirst() instanceof SQLBinaryCondition bc) {
      return bc.getOperator().isRangeOperator();
    }
    return false;
  }

  /** Returns true if every sub-block is a strict equality condition (SQLEqualsOperator). */
  private static boolean allEquality(List<SQLBooleanExpression> subBlocks) {
    for (var block : subBlocks) {
      if (!(block instanceof SQLBinaryCondition bc)
          || !(bc.getOperator() instanceof SQLEqualsOperator)) {
        return false;
      }
    }
    return true;
  }

  /** Unwraps the key condition into its sub-blocks (AND block -> sub-blocks, else singleton). */
  private List<SQLBooleanExpression> getSubBlocks() {
    if (keyCondition instanceof SQLAndBlock andBlock) {
      return andBlock.getSubBlocks();
    } else {
      return Collections.singletonList(keyCondition);
    }
  }

  /** Returns the number of condition sub-blocks in the key condition. */
  public int blockCount() {
    return getSubBlocks().size();
  }

  /**
   * Estimates the number of matching rows using persistent index statistics
   * and the histogram. Returns {@code -1} if estimation is not possible
   * (no key condition, no statistics, non-constant values, or unsupported
   * operators).
   *
   * <p>Used by the plan-time histogram gate to reject index pre-filters
   * whose estimated result size exceeds the adaptive threshold.
   */
  public long estimateHits(CommandContext ctx) {
    if (keyCondition == null) {
      return -1;
    }
    var session = ctx.getDatabaseSession();
    var indexStats = index.getStatistics(session);
    if (indexStats == null || indexStats.totalCount() == 0) {
      return -1;
    }
    double selectivity = computeRawSelectivity(
        indexStats, index.getHistogram(session), ctx);
    if (selectivity < 0) {
      return -1;
    }
    return Math.max(1, (long) (indexStats.totalCount() * selectivity));
  }

  /**
   * Estimates the selectivity of this index lookup as a fraction of the
   * total index entries that match the key condition. Returns a value in
   * [0.0, 1.0] where 0.0 means no entries match and 1.0 means all entries
   * match. Returns {@code -1.0} if statistics are unavailable or the index
   * is empty.
   *
   * <p>Uses the same estimation logic as {@link #estimateHits} (histogram-
   * based leading field + default selectivity for non-leading fields) but
   * returns the raw selectivity fraction rather than an absolute count.
   *
   * <p>Used by {@link RidFilterDescriptor.IndexLookup#passesSelectivityCheck}
   * to decide whether the index pre-filter is worth applying.
   */
  public double estimateSelectivity(CommandContext ctx) {
    if (keyCondition == null) {
      return -1.0;
    }
    var session = ctx.getDatabaseSession();
    var indexStats = index.getStatistics(session);
    if (indexStats == null || indexStats.totalCount() == 0) {
      return -1.0;
    }
    double selectivity = computeRawSelectivity(
        indexStats, index.getHistogram(session), ctx);
    if (selectivity < 0) {
      return -1.0;
    }
    return Math.min(1.0, Math.max(0.0, selectivity));
  }

  /**
   * Core selectivity computation shared by {@link #estimateHits},
   * {@link #estimateSelectivity}, and {@link #estimateFromHistogram}.
   * Returns the raw selectivity fraction (a value typically in [0.0, 1.0]),
   * or {@code -1.0} if estimation is not possible (field mismatch,
   * non-constant value, or unsupported operator).
   *
   * <p>Callers are responsible for null-checking {@code indexStats}
   * before calling. The histogram may be {@code null} (in which case
   * default selectivity estimation is used).
   */
  private double computeRawSelectivity(
      IndexStatistics indexStats, @Nullable EquiDepthHistogram histogram,
      CommandContext ctx) {

    var subBlocks = getSubBlocks();
    var leadingField = index.getDefinition().getProperties().getFirst();

    double selectivity = estimateBlockSelectivity(
        subBlocks.getFirst(), leadingField, indexStats, histogram, ctx);
    if (selectivity < 0) {
      return -1.0;
    }

    if (additionalRangeCondition != null && subBlocks.size() == 1) {
      double rangeSel = estimateCombinedRange(
          subBlocks.getFirst(), additionalRangeCondition,
          indexStats, histogram, ctx);
      if (rangeSel >= 0) {
        selectivity = rangeSel;
      }
    }

    for (int i = 1; i < subBlocks.size(); i++) {
      selectivity *= SelectivityEstimator.defaultSelectivity();
    }

    return selectivity;
  }

  public Index getIndex() {
    return index;
  }

  protected SQLBooleanExpression getKeyCondition() {
    return keyCondition;
  }

  protected SQLBinaryCondition getAdditionalRangeCondition() {
    return additionalRangeCondition;
  }

  protected SQLBooleanExpression getRemainingCondition() {
    return remainingCondition;
  }

  /**
   * Returns {@code true} if any sub-block in the key condition is not a simple binary
   * comparison (e.g. it is an IN or CONTAINSANY expression), which requires multiple
   * separate index lookups rather than a single range scan.
   */
  public boolean requiresMultipleIndexLookups() {
    for (var oBooleanExpression : getSubBlocks()) {
      if (!(oBooleanExpression instanceof SQLBinaryCondition)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if a {@link DistinctExecutionStep} must follow this index lookup
   * to deduplicate results (because multiple lookups or multi-value properties can produce
   * duplicate RIDs).
   */
  public boolean requiresDistinctStep() {
    return requiresMultipleIndexLookups() || duplicateResultsForRecord();
  }

  /**
   * Returns {@code true} if the index is a composite index with multi-value properties
   * (e.g. EMBEDDEDLIST fields), which can produce the same RID multiple times.
   */
  public boolean duplicateResultsForRecord() {
    if (index.getDefinition() instanceof CompositeIndexDefinition compDef) {
      return compDef.hasMultiValueProperties();
    }
    return false;
  }

  /**
   * Returns {@code true} if this index lookup produces results that are already sorted
   * according to the given ORDER BY field list, so no in-memory sort is needed.
   *
   * <p>The check accounts for equality conditions (whose values are fixed) overlapping
   * with the ORDER BY fields. For example, if the index is on [city, age] and the
   * condition fixes city='NYC', then ORDER BY age is already satisfied.
   *
   * <pre>
   *  Example 1 -- fully sorted:
   *    Index fields:     [city, age, name]
   *    Key conditions:   [city = 'NYC']        (fixed by equality)
   *    ORDER BY:         [age, name]
   *    Merged order:     [city, age, name]      matches index prefix -> sorted
   *
   *  Example 2 -- NOT sorted:
   *    Index fields:     [city, age]
   *    Key conditions:   [city = 'NYC']
   *    ORDER BY:         [name]
   *    Merged order:     [city, name]           name != age -> not sorted
   *
   *  Example 3 -- overlap:
   *    Index fields:     [city, age]
   *    Key conditions:   [city = 'NYC']
   *    ORDER BY:         [city, age]
   *    city overlaps condition -> consumed; remaining [age] matches -> sorted
   * </pre>
   *
   * @param orderItems mutable list of ORDER BY field names.
   *     <b>WARNING: this list is destructively modified in-place</b> -- matching
   *     entries are removed during the overlap check. Callers must pass a
   *     defensive copy if they need the original list afterward.
   */
  public boolean fullySorted(List<String> orderItems) {
    var conditions = getSubBlocks();
    List<String> conditionItems = new ArrayList<>();

    for (var i = 0; i < conditions.size(); i++) {
      var item = conditions.get(i);
      if (item instanceof SQLBinaryCondition cond
          && cond.getOperator() instanceof SQLEqualsOperator) {
        conditionItems.add(cond.getLeft().toString());
      } else if (item instanceof SQLInCondition) {
        return false;
      } else if (i != conditions.size() - 1) {
        return false;
      }
    }

    List<String> orderedFields = new ArrayList<>();
    var overlapping = false;
    for (var s : conditionItems) {
      if (orderItems.isEmpty()) {
        return true; // nothing to sort, the conditions completely overlap the ORDER BY
      }
      if (s.equals(orderItems.get(0))) {
        orderItems.remove(0);
        overlapping = true; // start overlapping
      } else if (overlapping) {
        return false; // overlapping, but next order item does not match...
      }
      orderedFields.add(s);
    }
    orderedFields.addAll(orderItems);

    final var definition = index.getDefinition();
    final var fields = definition.getProperties();
    if (fields.size() < orderedFields.size()) {
      return false;
    }

    for (var i = 0; i < orderedFields.size(); i++) {
      final var orderFieldName = orderedFields.get(i);
      final var indexFieldName = fields.get(i);
      if (!orderFieldName.equals(indexFieldName)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Returns {@code true} if this descriptor's key condition blocks are a prefix of
   * {@code other}'s. For example, if this has blocks [a] and {@code other} has [a, b],
   * this is a prefix of other.
   *
   * @param other the descriptor to compare against
   * @return true if this descriptor's conditions are a prefix of the other's
   */
  public boolean isPrefixOf(IndexSearchDescriptor other) {
    var left = getSubBlocks();
    var right = other.getSubBlocks();
    if (left.size() > right.size()) {
      return false;
    }
    for (var i = 0; i < left.size(); i++) {
      if (!left.get(i).equals(right.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns {@code true} if this descriptor uses the same key conditions (same number
   * and equal sub-blocks) as another descriptor, regardless of which index is used.
   */
  public boolean isSameCondition(IndexSearchDescriptor desc) {
    if (blockCount() != desc.blockCount()) {
      return false;
    }
    var left = getSubBlocks();
    var right = desc.getSubBlocks();
    for (var i = 0; i < left.size(); i++) {
      if (!left.get(i).equals(right.get(i))) {
        return false;
      }
    }
    return true;
  }
}
