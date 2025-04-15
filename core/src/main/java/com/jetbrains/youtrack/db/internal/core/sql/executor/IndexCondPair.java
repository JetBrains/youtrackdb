package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBooleanExpression;
import java.util.Objects;

/**
 * For internal use. It is used to keep info about an index range search, where the main condition
 * has the lower bound and the additional condition has the upper bound on last field only
 */
class IndexCondPair {

  protected SQLBooleanExpression mainCondition;
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
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (IndexCondPair) o;

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
