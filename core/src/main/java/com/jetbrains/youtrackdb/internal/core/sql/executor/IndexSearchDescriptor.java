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
 *
 */
public class IndexSearchDescriptor {

  private final Index index;
  private final SQLBooleanExpression keyCondition;
  private final SQLBinaryCondition additionalRangeCondition;
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

  public int cost(CommandContext ctx) {
    var stats = QueryStats.get(ctx.getDatabaseSession());

    var indexName = index.getName();
    var size = getSubBlocks().size();
    var range = false;
    var lastOp = getSubBlocks().get(getSubBlocks().size() - 1);
    if (lastOp instanceof SQLBinaryCondition) {
      var op = ((SQLBinaryCondition) lastOp).getOperator();
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

  private List<SQLBooleanExpression> getSubBlocks() {
    if (keyCondition instanceof SQLAndBlock) {
      return ((SQLAndBlock) keyCondition).getSubBlocks();
    } else {
      return Collections.singletonList(keyCondition);
    }
  }

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
   * checks whether the condition has CONTAINSANY or similar expressions, that require multiple
   * index evaluations
   */
  public boolean requiresMultipleIndexLookups() {
    for (var oBooleanExpression : getSubBlocks()) {
      if (!(oBooleanExpression instanceof SQLBinaryCondition)) {
        return true;
      }
    }
    return false;
  }

  public boolean requiresDistinctStep() {
    return requiresMultipleIndexLookups() || duplicateResultsForRecord();
  }

  public boolean duplicateResultsForRecord() {
    if (index.getDefinition() instanceof CompositeIndexDefinition) {
      return ((CompositeIndexDefinition) index.getDefinition()).hasMultiValueProperties();
    }
    return false;
  }

  public boolean fullySorted(List<String> orderItems) {
    var conditions = getSubBlocks();
    var idx = index;

    if (!idx.supportsOrderedIterations()) {
      return false;
    }
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

    final var definition = idx.getDefinition();
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
   * returns true if the first argument is a prefix for the second argument, eg. if the first
   * argument is [a] and the second argument is [a, b]
   *
   * @param item
   * @param desc
   * @return
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
