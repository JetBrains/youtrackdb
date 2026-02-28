package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import java.util.Objects;

/**
 * Value object pairing an index key condition with an optional second range bound,
 * used as a map key in {@link SelectExecutionPlanner#commonFactor} to aggregate
 * multiple OR-branches that share the same index lookup pattern.
 *
 * <pre>
 *  Example: WHERE (city = 'NYC' AND age &gt;= 20 AND age &lt; 30 AND name = 'Alice')
 *               OR (city = 'NYC' AND age &gt;= 20 AND age &lt; 30 AND name = 'Bob')
 *
 *  Both branches share the same index lookup pattern:
 *    mainCondition    = AND[city = 'NYC', age &gt;= 20]
 *    additionalRange  = age &lt; 30
 *
 *  The commonFactor() method groups them by this IndexCondPair key and
 *  combines their remaining conditions: (name = 'Alice' OR name = 'Bob').
 * </pre>
 *
 * <p>Implements {@link #equals} and {@link #hashCode} so it can be used as a
 * {@link java.util.HashMap} key.
 */
class IndexCondPair {

  /** The primary index key condition (equality chain, possibly ending with a range). */
  protected SQLBooleanExpression mainCondition;

  /** Optional complementary range bound on the last key field (null if single-sided). */
  protected SQLBinaryCondition additionalRange;

  public IndexCondPair(
      SQLBooleanExpression keyCondition, SQLBinaryCondition additionalRangeCondition) {
    this.mainCondition = keyCondition;
    this.additionalRange = additionalRangeCondition;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof IndexCondPair that)) {
      return false;
    }

    if (!Objects.equals(mainCondition, that.mainCondition)) {
      return false;
    }
    return Objects.equals(additionalRange, that.additionalRange);
  }

  @Override
  public int hashCode() {
    var result = mainCondition != null ? mainCondition.hashCode() : 0;
    result = 31 * result + (additionalRange != null ? additionalRange.hashCode() : 0);
    return result;
  }
}
