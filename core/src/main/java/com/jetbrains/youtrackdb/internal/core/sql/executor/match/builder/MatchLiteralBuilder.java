package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Converts a Java literal value into an [SQLExpression] suitable for the unified
/// MATCH IR consumed by `MatchExecutionPlanner`. Each input type maps to a
/// dedicated SQL AST field that survives `SQLExpression.copy()` (called by
/// `SelectExecutionPlanner` during plan creation):
///
/// - String → [SQLBaseExpression] via `setMathExpression` (encoded/decoded, not an identifier)
/// - [RecordIdInternal] (LINK) → [SQLRid] via `setRid`
/// - Number (Long, Double, Integer, BigDecimal, …) → [SQLBaseExpression] over [SQLInteger] via `setMathExpression`
/// - Boolean → `setBooleanValue`
/// - Date, List, Set, Map, byte[] → `setLiteralValue` (opaque value preserved through copy)
///
/// `null` is not supported; passing `null` throws [NullPointerException]. Callers
/// are responsible for filtering nulls upstream. This is intentional behavior
/// inherited from the original `GqlMatchStatement.toLiteral` extraction; treating
/// `null` as a literal would silently drop predicates that the parser already
/// rejects upstream.
public final class MatchLiteralBuilder {

  private MatchLiteralBuilder() {
  }

  /// Converts a Java literal `value` into an [SQLExpression] matching the type
  /// table in the class Javadoc. Throws [IllegalArgumentException] for any
  /// type not listed there, and [NullPointerException] when `value` is `null`.
  public static SQLExpression toLiteral(Object value) {
    Objects.requireNonNull(value, "value must not be null");
    var expr = new SQLExpression(-1);
    if (value instanceof String s) {
      expr.setMathExpression(new SQLBaseExpression(s));
      return expr;
    }
    if (value instanceof RecordIdInternal rid) {
      var sqlRid = new SQLRid(-1);
      var collection = new SQLInteger(-1);
      collection.setValue(rid.getCollectionId());
      var position = new SQLInteger(-1);
      position.setValue(rid.getCollectionPosition());
      sqlRid.setCollection(collection);
      sqlRid.setPosition(position);
      sqlRid.setLegacy(true);
      expr.setRid(sqlRid);
      return expr;
    }
    if (value instanceof Number n) {
      var integer = new SQLInteger(-1);
      integer.setValue(n);
      expr.setMathExpression(new SQLBaseExpression(integer));
      return expr;
    }
    if (value instanceof Boolean b) {
      expr.setBooleanValue(b);
      return expr;
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
