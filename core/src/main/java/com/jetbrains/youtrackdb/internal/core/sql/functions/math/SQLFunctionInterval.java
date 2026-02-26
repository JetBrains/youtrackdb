package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import javax.annotation.Nullable;

/**
 * Returns the index of the argument that is more than the first argument.
 *
 * <p>Returns -1 if 1st number is NULL or if 1st number is the highest number The returned index
 * starts from the 2nd argument. That is, interval(23, 5, 50) = 1
 *
 * <p>Few examples: interval(43, 35, 5, 15, 50) = 3 interval(54, 25, 35, 45) = -1 interval(null, 5,
 * 50) = -1 interval(6, 6) = -1 interval(58, 60, 30, 65) = 0 interval(103, 54, 106, 98, 119) = 1
 */
public class SQLFunctionInterval extends SQLFunctionMathAbstract {

  public static final String NAME = "interval";

  public SQLFunctionInterval() {
    super(NAME, 2, 0);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public Object execute(
      Object iThis,
      final Result iRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {

    if (iParams == null || iParams.length < 2) {
      return -1;
    }

    // return the index of the number the 1st number
    final Comparable first = (Comparable<?>) iParams[0];
    if (first == null) {
      return -1;
    }

    for (var i = 1; i < iParams.length; ++i) {
      final Comparable other = (Comparable<?>) iParams[i];
      if (other.compareTo(first) > 0) {
        return i - 1;
      }
    }

    return -1;
  }

  @Override
  public boolean aggregateResults() {
    return false;
  }

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return "interval(<field> [,<field>*])";
  }

  @Nullable
  @Override
  public Object getResult() {
    return null;
  }
}
