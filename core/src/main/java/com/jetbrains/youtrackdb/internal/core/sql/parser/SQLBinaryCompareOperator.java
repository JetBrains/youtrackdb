package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface SQLBinaryCompareOperator {

  boolean execute(@Nonnull DatabaseSessionEmbedded session, Object left, Object right);

  boolean supportsBasicCalculation();

  /// Merges right operands of current and passed in operator by producing a new operator and new
  /// right operand.result of execution of which on the same left operand is equivalent of the
  /// result of the execution of
  /// ```
  /// currentOperator.execute(left, currentRight) && otherOperator.execute(left, otherRight)
  ///```
  ///
  /// @param session       current database session
  /// @param otherOperator another operator to merge with
  /// @param currentRight  right operand for the current instance of operator``
  /// @param otherRight    right operand for the instance of another operator``
  @SuppressWarnings("unused")
  @Nullable
  default MergeResult mergeWithOperator(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SQLBinaryCompareOperator otherOperator,
      Object currentRight, Object otherRight) {

    var result = doCompare(currentRight, otherRight);
    if (result == null) {
      return null;
    }

    //combinations of LE, LT, GE, GT, EQ, NE
    return switch (this) {
      //LE
      case SQLLeOperator lessOrEquals -> {
        yield mergeLEOperator(otherOperator, currentRight, otherRight, result);
      }
      //LT
      case SQLLtOperator lessThan -> {
        yield mergeLTOperator(otherOperator, currentRight, otherRight, result);
      }
      //GE
      case SQLGeOperator greaterOrEquals -> {
        yield mergeGEOperator(otherOperator, currentRight, otherRight, result);
      }
      //GT
      case SQLGtOperator greaterThan -> {
        yield mergeGTOperator(otherOperator, currentRight, otherRight, result);
      }
      //EQ
      case SQLEqualsOperator equals -> {
        yield mergeEQOperator(otherOperator, currentRight, otherRight, result);
      }
      //NE
      case SQLNeOperator notEquals -> {
        yield mergeNEOperator(otherOperator, currentRight, otherRight, result);
      }
      default -> {
        yield null;
      }
    };
  }

  @SuppressWarnings({"DuplicateBranchesInSwitch", "unused"})
  @Nullable
  private MergeResult mergeNEOperator(SQLBinaryCompareOperator otherOperator, Object currentRight,
      Object otherRight, Integer result) {
    return switch (otherOperator) {
      case SQLLeOperator lessOrEquals -> {
        yield mergeLEOperator(this, otherRight, currentRight, -result);
      }
      case SQLLtOperator lessThan -> {
        yield mergeLTOperator(this, otherRight, currentRight, -result);
      }
      case SQLGeOperator greaterOrEquals -> {
        yield mergeGEOperator(this, otherRight, currentRight, -result);
      }
      case SQLGtOperator greaterThan -> {
        yield mergeGTOperator(this, otherRight, currentRight, -result);
      }
      case SQLNeOperator notEquals -> {
        if (result == 0) {
          yield new MergeResult(this, otherRight);
        }
        yield null;
      }
      case SQLEqualsOperator equals -> {
        yield null;
      }
      default -> {
        yield null;
      }
    };
  }

  @SuppressWarnings({"DuplicateBranchesInSwitch", "unused"})
  @Nullable
  private MergeResult mergeEQOperator(SQLBinaryCompareOperator otherOperator, Object currentRight,
      Object otherRight, Integer result) {
    return switch (otherOperator) {
      case SQLLeOperator lessOrEquals -> {
        yield mergeLEOperator(this, otherRight, currentRight, -result);
      }
      case SQLLtOperator lessThan -> {
        yield mergeLTOperator(this, otherRight, currentRight, -result);
      }
      case SQLGeOperator greaterOrEquals -> {
        yield mergeGEOperator(this, otherRight, currentRight, result);
      }
      case SQLGtOperator greaterThan -> {
        yield mergeGTOperator(this, otherRight, currentRight, result);
      }
      case SQLNeOperator notEquals -> {
        yield null;
      }
      case SQLEqualsOperator equals -> {
        if (result == 0) {
          yield new MergeResult(this, otherRight);
        }
        yield null;
      }
      default -> {
        yield null;
      }
    };
  }

  @SuppressWarnings({"DuplicatedCode", "unused"})
  @Nullable
  private MergeResult mergeGTOperator(SQLBinaryCompareOperator otherOperator, Object currentRight,
      Object otherRight, Integer result) {
    return switch (otherOperator) {
      case SQLLeOperator lessOrEquals -> {
        yield null;//not applicable at the moment
      }
      case SQLLtOperator lessThan -> {
        yield null;//not applicable at the moment
      }
      case SQLGeOperator greaterOrEquals -> {
        if (result >= 0) {
          yield new MergeResult(this, currentRight);
        }

        yield new MergeResult(otherOperator, otherRight);
      }
      case SQLGtOperator greaterThan -> {
        if (result >= 0) {
          yield new MergeResult(this, currentRight);
        }

        yield new MergeResult(otherOperator, otherRight);
      }
      case SQLNeOperator notEquals -> {
        if (result >= 0) {
          yield new MergeResult(this, currentRight);
        }

        yield new MergeResult(this, otherRight);
      }
      case SQLEqualsOperator equals -> {
        if (result < 0) {
          yield new MergeResult(SQLEqualsOperator.INSTANCE, otherRight);
        }

        yield null;
      }
      default -> {
        yield null;
      }
    };
  }


  @SuppressWarnings("unused")
  @Nullable
  private MergeResult mergeGEOperator(SQLBinaryCompareOperator otherOperator, Object currentRight,
      Object otherRight, Integer result) {
    return switch (otherOperator) {
      case SQLLeOperator lessOrEquals -> {
        yield null;//not applicable at the moment
      }
      case SQLLtOperator lessThan -> {
        yield null;//not applicable at the moment
      }
      case SQLGeOperator greaterOrEquals -> {
        if (result >= 0) {
          yield new MergeResult(this, currentRight);
        }

        yield new MergeResult(otherOperator, otherRight);
      }
      case SQLGtOperator greaterThan -> {
        if (result > 0) {
          yield new MergeResult(this, currentRight);
        }

        yield new MergeResult(otherOperator, otherRight);
      }
      case SQLNeOperator notEquals -> {
        if (result > 0) {
          yield new MergeResult(this, currentRight);
        }

        yield new MergeResult(SQLGtOperator.INSTANCE, otherRight);
      }
      case SQLEqualsOperator equals -> {
        if (result <= 0) {
          yield new MergeResult(SQLEqualsOperator.INSTANCE, otherRight);
        }
        yield null;
      }
      default -> {
        yield null;
      }
    };
  }

  @SuppressWarnings("unused")
  @Nullable
  private MergeResult mergeLEOperator(SQLBinaryCompareOperator otherOperator, Object currentRight,
      Object otherRight, Integer result) {
    return switch (otherOperator) {
      case SQLLeOperator lessOrEquals -> {
        if (result <= 0) {
          yield new MergeResult(this, currentRight);
        }
        yield new MergeResult(otherOperator, otherOperator);
      }

      case SQLLtOperator lessThan -> {
        if (result < 0) {
          yield new MergeResult(this, currentRight);
        }
        yield new MergeResult(otherOperator, otherRight);
      }

      case SQLGeOperator greaterOrEquals -> {
        yield null;// not applicable at the moment
      }

      case SQLGtOperator greaterThan -> {
        yield null;// not applicable at the moment
      }

      case SQLNeOperator notEquals -> {
        if (result >= 0) {
          yield new MergeResult(SQLLtOperator.INSTANCE, otherRight);
        }

        yield new MergeResult(this, currentRight);
      }

      case SQLEqualsOperator equals -> {
        if (result >= 0) {
          yield new MergeResult(SQLEqualsOperator.INSTANCE, otherRight);
        }

        yield null;
      }

      default -> {
        yield null;
      }
    };
  }

  @SuppressWarnings("unused")
  @Nullable
  private MergeResult mergeLTOperator(SQLBinaryCompareOperator otherOperator, Object currentRight,
      Object otherRight, Integer result) {
    return switch (otherOperator) {
      case SQLLeOperator lessOrEquals -> {
        if (result <= 0) {
          yield new MergeResult(this, currentRight);
        }

        yield new MergeResult(SQLLeOperator.INSTANCE, otherRight);
      }
      case SQLLtOperator lessThan -> {
        if (result <= 0) {
          yield new MergeResult(this, currentRight);
        }
        yield new MergeResult(otherOperator, otherRight);
      }
      case SQLGeOperator greaterOrEquals -> {
        yield null;// not applicable at the moment
      }
      case SQLGtOperator greaterThan -> {
        yield null;// not applicable at the moment
      }
      case SQLNeOperator notEquals -> {
        if (result >= 0) {
          yield new MergeResult(this, otherRight);
        }
        yield new MergeResult(this, currentRight);
      }
      case SQLEqualsOperator equals -> {
        if (result > 0) {
          yield new MergeResult(SQLEqualsOperator.INSTANCE, otherRight);
        }
        yield null;
      }
      default -> {
        yield null;
      }
    };
  }



  void toGenericStatement(StringBuilder builder);

  SQLBinaryCompareOperator copy();

  default boolean isRangeOperator() {
    return false;
  }

  IndexFinder.Operation getOperation();

  @Nullable
  static Integer doCompare(Object left, Object right) {
    if (left == null || right == null) {
      return null;
    }

    if (left.getClass() != right.getClass()
        && left instanceof Number
        && right instanceof Number) {
      var couple = PropertyTypeInternal.castComparableNumber((Number) left, (Number) right);
      left = couple[0];
      right = couple[1];
    } else {
      try {
        right = PropertyTypeInternal.convert(null, right, left.getClass());
      } catch (RuntimeException e) {
        right = null;
        // Can't convert to the target value do nothing will return false
        LogManager.instance()
            .warn(SQLBinaryCompareOperator.class,
                "Issue converting value to target type, ignoring value", e);
      }
    }
    if (right == null) {
      return null;
    }
    if (left instanceof Identifiable && !(right instanceof Identifiable)) {
      return null;
    }

    if (!(left instanceof Comparable)) {
      return null;
    }

    //noinspection unchecked
    return ((Comparable<Object>) left).compareTo(right);
  }

  record MergeResult(SQLBinaryCompareOperator operator, Object mergedRightOperand) {

  }
}
