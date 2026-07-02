package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.StartStepRecogniser.createLegacySqlRid;

import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Converts a Java literal value into an {@link SQLExpression} suitable for the unified
 * MATCH IR consumed by {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner}. Each
 * input type maps to a dedicated SQL AST field that survives {@link SQLExpression#copy()}
 * (called by {@link com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlanner}
 * during plan creation):
 *
 * <ul>
 *   <li>String → {@link SQLBaseExpression} via {@code setMathExpression} (encoded/decoded, not
 *       an identifier)
 *   <li>{@link RecordIdInternal} (LINK) → {@link SQLRid} via {@code setRid}
 *   <li>Number (Long, Double, Integer, BigDecimal, …) → {@link SQLBaseExpression} over
 *       {@link SQLInteger} via {@code setMathExpression}
 *   <li>Boolean → {@code setBooleanValue}
 *   <li>Date, List, Set, Map, byte[] → {@code setLiteralValue} (opaque value preserved through
 *       copy)
 * </ul>
 *
 * <p>{@code null} is not supported; callers must filter nulls upstream. This is intentional
 * behavior inherited from the original {@code GqlMatchStatement.toLiteral} extraction; treating
 * {@code null} as a literal would silently drop predicates that the parser already rejects
 * upstream.
 */
public final class MatchLiteralBuilder {

  private MatchLiteralBuilder() {
  }

  /**
   * Converts a Java literal {@code value} into an {@link SQLExpression} matching the type table
   * in the class Javadoc. Throws {@link IllegalArgumentException} for any type not listed there.
   */
  public static SQLExpression toLiteral(@Nonnull Object value) {
    var expr = new SQLExpression(-1);
    switch (value) {
      case String s -> {
        expr.setMathExpression(new SQLBaseExpression(s));
        return expr;
      }
      case RecordIdInternal rid -> {
        var sqlRid = createLegacySqlRid(rid);
        expr.setRid(sqlRid);
        return expr;
      }
      case Number n -> {
        var integer = new SQLInteger(-1);
        integer.setValue(n);
        expr.setMathExpression(new SQLBaseExpression(integer));
        return expr;
      }
      case Boolean b -> {
        expr.setBooleanValue(b);
        return expr;
      }
      default -> {
      }
    }
    if (value instanceof Date
        || value instanceof List<?>
        || value instanceof Set<?>
        || value instanceof Map<?, ?>
        || value instanceof byte[]) {
      expr.setLiteralValue(value);
      return expr;
    }
    throw new IllegalArgumentException("Unsupported property value type: " + value.getClass());
  }
}
