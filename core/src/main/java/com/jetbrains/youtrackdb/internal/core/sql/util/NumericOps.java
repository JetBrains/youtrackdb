package com.jetbrains.youtrackdb.internal.core.sql.util;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression.Operator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import javax.annotation.Nullable;

/// The shared numeric-promotion engine for binary arithmetic, lifted out of
/// `SQLMathExpression.Operator` so the SQL AST evaluator and the analyzed-expression IR
/// evaluator decide result type and value the exact same way. With one home for promotion the
/// two evaluators cannot drift on edge cases — integer-vs-double divide, null propagation,
/// `Date + Long`, `String` concatenation.
///
/// The class is all-static and not instantiable. Each method takes the [Operator] constant
/// explicitly (the enum used to carry this logic as instance methods, so what was `this`
/// becomes the first parameter). `SQLMathExpression.Operator`'s `apply` family delegates here;
/// it keeps its own public typed and `apply(Object, Object)` signatures so existing AST call
/// sites stay source-compatible.
///
/// Dispatch shape is preserved exactly so the live AST arithmetic hot path stays
/// perf-neutral. The per-operator first hop (the enum's per-constant `apply(Object, Object)`)
/// calls [#applyObject] for the operators that used to call `super.apply(...)`; [#applyObject]
/// re-dispatches by the widening entry [#apply(Number, Operator, Number)], which selects a
/// typed-pair method by the right operand's runtime type. Every hop here is a static
/// monomorphic call that inlines — no new virtual indirection enters the path.
public final class NumericOps {

  private NumericOps() {
  }

  /// The base object-level promotion: null operands return the other operand, two `Number`s
  /// widen through [#apply(Number, Operator, Number)], anything else is unsupported and
  /// returns `null`. This is the lifted form of the enum's former base `apply(Object, Object)`;
  /// the per-constant bodies that used to call `super.apply(...)` now call this.
  @Nullable public static Object applyObject(Operator op, @Nullable Object left, @Nullable Object right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      return apply(leftNumber, op, rightNumber);
    }
    return null;
  }

  /// `+` semantics at the object level: null propagation (`null + null = null`, `null + x = x`),
  /// numeric addition through the widening engine, `Date ± Long` producing a `Date`, and
  /// `String` concatenation when neither branch above matched (the non-`String` operand is
  /// `toString()`-ed). This is the lifted body of `Operator.PLUS.apply(Object, Object)`.
  @Nullable public static Object plusObject(@Nullable Object left, @Nullable Object right) {
    if (left == null && right == null) {
      return null;
    }
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    if (left instanceof Number && right instanceof Number) {
      return applyObject(Operator.PLUS, left, right);
    }
    if (left instanceof Date || right instanceof Date) {
      // `Date op (non-Number, non-Date)` is an out-of-scope error path: `toLong` yields null for
      // the non-Date operand, so the widening entry's null guard throws IllegalArgumentException.
      // The pre-lift form called the typed `apply(Long, Long)` overload, which threw
      // NullPointerException on the unboxing of a null operand. This NPE->IAE shift on an
      // already-failing path is a deliberate, cleaner error; normal `Date +/- Long` is unaffected.
      var result = apply(toLong(left), Operator.PLUS, toLong(right));
      return new Date(result.longValue());
    }
    return String.valueOf(left) + right;
  }

  /// `-` semantics at the object level: a `null` numeric operand acts as the additive identity
  /// (`x - null = x`, `null - x = 0 - x`), numeric subtraction goes through the widening engine,
  /// and `Date - Long` produces a `Date`. Anything else returns `null`. This is the lifted body
  /// of `Operator.MINUS.apply(Object, Object)`.
  @Nullable public static Object minusObject(@Nullable Object left, @Nullable Object right) {
    Object result = null;
    if (left == null && right == null) {
      result = null;
    } else if (left instanceof Number && right == null) {
      result = left;
    } else if (right instanceof Number rightNumber && left == null) {
      result = apply(0, Operator.MINUS, rightNumber);
    } else if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      result = apply(leftNumber, Operator.MINUS, rightNumber);
    } else if (left instanceof Date || right instanceof Date) {
      // See plusObject: `Date - (non-Number, non-Date)` throws IllegalArgumentException from the
      // widening entry's null guard, where the pre-lift typed-overload path threw
      // NullPointerException. Deliberate NPE->IAE shift on an out-of-scope error path; normal
      // `Date - Long` is unaffected.
      var r = apply(toLong(left), Operator.MINUS, toLong(right));
      result = new Date(r.longValue());
    }
    return result;
  }

  /// `^` (XOR) semantics at the object level: a `null` numeric operand is treated as `0`, two
  /// `Number`s go through the widening engine, anything else returns `null`. This is the lifted
  /// body of `Operator.XOR.apply(Object, Object)`.
  @Nullable public static Object xorObject(@Nullable Object left, @Nullable Object right) {
    if (left == null && right == null) {
      return null;
    }
    if (left instanceof Number leftNumber && right == null) {
      return apply(leftNumber, Operator.XOR, 0);
    }
    if (right instanceof Number rightNumber && left == null) {
      return apply(0, Operator.XOR, rightNumber);
    }
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      return apply(leftNumber, Operator.XOR, rightNumber);
    }
    return null;
  }

  /// `|` (bitwise OR) semantics at the object level: `null` operands are treated as `0`, then
  /// the result is computed through the base object promotion. `null | null` short-circuits to
  /// `null`. This is the lifted body of `Operator.BIT_OR.apply(Object, Object)`.
  @Nullable public static Object bitOrObject(@Nullable Object left, @Nullable Object right) {
    if (left == null && right == null) {
      return null;
    }
    return applyObject(Operator.BIT_OR, left == null ? 0 : left, right == null ? 0 : right);
  }

  /// `??` (null-coalescing) semantics at the object level: the left operand when non-null, else
  /// the right. This is the lifted body of `Operator.NULL_COALESCING.apply(Object, Object)`.
  @Nullable public static Object nullCoalescingObject(@Nullable Object left, @Nullable Object right) {
    return left != null ? left : right;
  }

  /// Promotes a pair of `Integer` operands for the given operator: products, sums (with
  /// overflow upgraded to `Long`), differences (with underflow upgraded to `Long`), remainders,
  /// shifts, and bitwise operators. Shift/bitwise/XOR results for non-integral types are `null`
  /// upstream, but the `Integer` form always has an integral result.
  public static Number apply(Operator op, Integer left, Integer right) {
    return switch (op) {
      case STAR -> left * right;
      case SLASH -> {
        if (left % right == 0) {
          yield left / right;
        }
        yield ((double) left) / right;
      }
      case REM -> left % right;
      case PLUS -> {
        final var sum = left + right;
        // SPECIAL CASE: positive + positive overflowing to negative upgrades to Long.
        if (sum < 0 && left > 0 && right > 0) {
          yield left.longValue() + right;
        }
        yield sum;
      }
      case MINUS -> {
        var result = left - right;
        // SPECIAL CASE: negative - positive underflowing to positive upgrades to Long.
        if (result > 0 && left.intValue() < 0 && right.intValue() > 0) {
          yield left.longValue() - right;
        }
        yield result;
      }
      case LSHIFT -> left << right;
      case RSHIFT -> left >> right;
      case RUNSIGNEDSHIFT -> left >>> right;
      case BIT_AND -> left & right;
      case XOR -> left ^ right;
      case BIT_OR -> left | right;
      case NULL_COALESCING -> left != null ? left : right;
    };
  }

  /// Promotes a pair of `Long` operands for the given operator. Mirrors the `Integer` form
  /// without the overflow upgrade (the wider type already absorbs it).
  public static Number apply(Operator op, Long left, Long right) {
    return switch (op) {
      case STAR -> left * right;
      case SLASH -> {
        if (left % right == 0) {
          yield left / right;
        }
        yield ((double) left) / right;
      }
      case REM -> left % right;
      case PLUS -> left + right;
      case MINUS -> left - right;
      case LSHIFT -> left << right;
      case RSHIFT -> left >> right;
      case RUNSIGNEDSHIFT -> left >>> right;
      case BIT_AND -> left & right;
      case XOR -> left ^ right;
      case BIT_OR -> left | right;
      case NULL_COALESCING -> left != null ? left : right;
    };
  }

  /// Promotes a pair of `Float` operands for the given operator. Shift and bitwise-`OR`/`XOR`
  /// operators are undefined on floating-point and return `null`; `BIT_AND` falls back to a
  /// `Long` bitwise op via the integral values.
  @Nullable public static Number apply(Operator op, Float left, Float right) {
    return switch (op) {
      case STAR -> left * right;
      case SLASH -> left / right;
      case REM -> left % right;
      case PLUS -> left + right;
      case MINUS -> left - right;
      case LSHIFT, RSHIFT, RUNSIGNEDSHIFT, XOR, BIT_OR -> null;
      case BIT_AND -> apply(op, left.longValue(), right.longValue());
      case NULL_COALESCING -> left != null ? left : right;
    };
  }

  /// Promotes a pair of `Double` operands for the given operator. Same shape as the `Float`
  /// form: floating-point shift/bitwise-`OR`/`XOR` are `null`; `BIT_AND` falls back via `Long`.
  @Nullable public static Number apply(Operator op, Double left, Double right) {
    return switch (op) {
      case STAR -> left * right;
      case SLASH -> left / right;
      case REM -> left % right;
      case PLUS -> left + right;
      case MINUS -> left - right;
      case LSHIFT, RSHIFT, RUNSIGNEDSHIFT, XOR, BIT_OR -> null;
      case BIT_AND -> apply(op, left.longValue(), right.longValue());
      case NULL_COALESCING -> left != null ? left : right;
    };
  }

  /// Promotes a pair of `BigDecimal` operands for the given operator. Division rounds
  /// `HALF_UP`. Shift and bitwise operators are undefined on arbitrary-precision decimals and
  /// return `null`.
  @Nullable public static Number apply(Operator op, BigDecimal left, BigDecimal right) {
    return switch (op) {
      case STAR -> left.multiply(right);
      case SLASH -> left.divide(right, RoundingMode.HALF_UP);
      case REM -> left.remainder(right);
      case PLUS -> left.add(right);
      case MINUS -> left.subtract(right);
      case LSHIFT, RSHIFT, RUNSIGNEDSHIFT, BIT_AND, XOR, BIT_OR -> null;
      case NULL_COALESCING -> left != null ? left : right;
    };
  }

  /// The widening entry: given two `Number`s and an operator, widen the pair to a common type
  /// by the right operand's runtime type and route to the matching typed-pair method. Throws
  /// `IllegalArgumentException` for `null` operands or an unsupported runtime-type combination.
  public static Number apply(final Number a, final Operator operation, final Number b) {
    if (a == null || b == null) {
      throw new IllegalArgumentException("Cannot increment a null value");
    }

    if (a instanceof Integer || a instanceof Short) {
      if (b instanceof Integer || b instanceof Short) {
        return apply(operation, a.intValue(), b.intValue());
      } else if (b instanceof Long) {
        return apply(operation, a.longValue(), b.longValue());
      } else if (b instanceof Float) {
        return apply(operation, a.floatValue(), b.floatValue());
      } else if (b instanceof Double) {
        return apply(operation, a.doubleValue(), b.doubleValue());
      } else if (b instanceof BigDecimal bigDecimal) {
        // `a.intValue()` widens both Integer and Short, which the enclosing
        // `a instanceof Integer || a instanceof Short` branch admits. The pre-lift form was
        // `new BigDecimal((Integer) a)`, which threw ClassCastException for a Short left operand
        // (e.g. a `shortField op decimalField` arithmetic expression). The .intValue() form is a
        // deliberate latent-bug fix: Short + BigDecimal now computes a value instead of throwing.
        return apply(operation, new BigDecimal(a.intValue()), bigDecimal);
      }
    } else if (a instanceof Long) {
      if (b instanceof Integer || b instanceof Long || b instanceof Short) {
        return apply(operation, a.longValue(), b.longValue());
      } else if (b instanceof Float) {
        return apply(operation, a.floatValue(), b.floatValue());
      } else if (b instanceof Double) {
        return apply(operation, a.doubleValue(), b.doubleValue());
      } else if (b instanceof BigDecimal bigDecimal) {
        return apply(operation, new BigDecimal(a.longValue()), bigDecimal);
      }
    } else if (a instanceof Float) {
      if (b instanceof Short || b instanceof Integer || b instanceof Long || b instanceof Float) {
        return apply(operation, a.floatValue(), b.floatValue());
      } else if (b instanceof Double) {
        return apply(operation, a.doubleValue(), b.doubleValue());
      } else if (b instanceof BigDecimal bigDecimal) {
        return apply(operation, BigDecimal.valueOf(a.floatValue()), bigDecimal);
      }
    } else if (a instanceof Double) {
      if (b instanceof Short
          || b instanceof Integer
          || b instanceof Long
          || b instanceof Float
          || b instanceof Double) {
        return apply(operation, a.doubleValue(), b.doubleValue());
      } else if (b instanceof BigDecimal bigDecimal) {
        return apply(operation, BigDecimal.valueOf(a.doubleValue()), bigDecimal);
      }
    } else if (a instanceof BigDecimal bigDecimalA) {
      if (b instanceof Integer) {
        return apply(operation, bigDecimalA, new BigDecimal(b.intValue()));
      } else if (b instanceof Long) {
        return apply(operation, bigDecimalA, new BigDecimal(b.longValue()));
      } else if (b instanceof Short) {
        return apply(operation, bigDecimalA, new BigDecimal(b.intValue()));
      } else if (b instanceof Float) {
        return apply(operation, bigDecimalA, BigDecimal.valueOf(b.floatValue()));
      } else if (b instanceof Double) {
        return apply(operation, bigDecimalA, BigDecimal.valueOf(b.doubleValue()));
      } else if (b instanceof BigDecimal bigDecimalB) {
        return apply(operation, bigDecimalA, bigDecimalB);
      }
    }

    throw new IllegalArgumentException(
        "Cannot increment value '"
            + a
            + "' ("
            + a.getClass()
            + ") with '"
            + b
            + "' ("
            + b.getClass()
            + ")");
  }

  /// Coerces a value to a `Long` epoch/number for `Date` arithmetic: a `Number`'s long value or
  /// a `Date`'s epoch milliseconds. Returns `null` for any other type.
  @Nullable static Long toLong(@Nullable Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof Date date) {
      return date.getTime();
    }
    return null;
  }
}
