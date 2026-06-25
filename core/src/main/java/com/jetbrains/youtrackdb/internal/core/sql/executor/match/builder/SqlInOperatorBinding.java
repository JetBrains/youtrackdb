package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import java.lang.reflect.Field;

/**
 * Single canonical access point for populating {@link SQLInCondition#operator} —
 * the parser-emitted field that has no public setter and lives under the
 * off-limits {@code internal/core/sql/parser/} package. Multiple call sites in
 * the MATCH IR construction layer (the {@link MatchWhereBuilder#in builder's
 * own {@code in} method}, the Gremlin-translator recognisers that hand-build
 * {@code @rid IN [...]} or {@code @class IN [...]} conditions) need to set the
 * field; consolidating reflection here keeps the cost paid once at class load
 * and the parser-package coupling documented in one place.
 *
 * <p>Without this field populated, the runtime's {@code SQLInCondition.toString}
 * and a handful of optimizer paths see a half-formed condition and behave
 * incorrectly.
 */
public final class SqlInOperatorBinding {

  /**
   * Cached reflective handle on {@link SQLInCondition#operator}. Resolved once at
   * class-load time; if the parser package layout ever changes such that the
   * field disappears, the {@link ExceptionInInitializerError} surfaces
   * immediately on first use rather than as a deferred {@link IllegalStateException}
   * on the first IN-condition build.
   */
  private static final Field OPERATOR_FIELD = resolveOperatorField();

  private SqlInOperatorBinding() {
    // Static utility — no instances.
  }

  /**
   * Sets the canonical {@link SQLInOperator} on the supplied condition. Each call
   * pays only the {@link Field#set} cost — field lookup and {@code setAccessible}
   * happen exactly once at class load.
   */
  public static void setOperator(SQLInCondition condition) {
    try {
      OPERATOR_FIELD.set(condition, new SQLInOperator(-1));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("SQLInCondition.operator field is no longer writable", e);
    }
  }

  private static Field resolveOperatorField() {
    try {
      Field field = SQLInCondition.class.getDeclaredField("operator");
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "SQLInCondition.operator is no longer present; the parser's package layout changed",
          e);
    }
  }
}
