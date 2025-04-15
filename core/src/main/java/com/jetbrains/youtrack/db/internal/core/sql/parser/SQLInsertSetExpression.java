package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;

/**
 *
 */
public class SQLInsertSetExpression {

  protected SQLIdentifier left;
  protected SQLExpression right;

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    left.toString(params, builder);
    builder.append(" = ");
    right.toString(params, builder);
  }

  public SQLInsertSetExpression copy() {
    var result = new SQLInsertSetExpression();
    result.left = left == null ? null : left.copy();
    result.right = right == null ? null : right.copy();
    return result;
  }

  public SQLIdentifier getLeft() {
    return left;
  }

  public SQLExpression getRight() {
    return right;
  }

  public boolean isCacheable(DatabaseSessionInternal session) {
    return right.isCacheable(session);
  }

  public void toGenericStatement(StringBuilder builder) {
    left.toGenericStatement(builder);
    builder.append(" = ");
    right.toGenericStatement(builder);
  }
}
