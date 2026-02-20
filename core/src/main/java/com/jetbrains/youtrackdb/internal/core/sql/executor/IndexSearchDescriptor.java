package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
   * Estimates the I/O cost of this index lookup by querying stored index statistics.
   * Returns {@link Integer#MAX_VALUE} if no statistics are available.
   */
  public int cost(CommandContext ctx) {
    var stats = QueryStats.get(ctx.getDatabaseSession());

    var indexName = index.getName();
    var size = getSubBlocks().size();
    var range = false;
    var lastOp = getSubBlocks().get(getSubBlocks().size() - 1);
    if (lastOp instanceof SQLBinaryCondition binCond) {
      var op = binCond.getOperator();
      range = op.isRangeOperator();
    }

    var val =
        stats.getIndexStats(
            indexName, size, range, additionalRangeCondition != null, ctx.getDatabaseSession());
    if (val == -1) {
      // TODO query the index!
    }
    if (val >= 0) {
      return val > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) val;
    }
    return Integer.MAX_VALUE;
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

  protected Index getIndex() {
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
