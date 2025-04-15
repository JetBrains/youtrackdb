package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 *
 */
public class SQLMatchPathItemFirst extends SQLMatchPathItem {

  protected SQLFunctionCall function;

  protected SQLMethodCall methodWrapper;

  public SQLMatchPathItemFirst(int id) {
    super(id);
  }

  public SQLMatchPathItemFirst(YouTrackDBSql p, int id) {
    super(p, id);
  }

  public boolean isBidirectional() {
    return false;
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    function.toString(params, builder);
    if (filter != null) {
      filter.toString(params, builder);
    }
  }

  public void toGenericStatement(StringBuilder builder) {
    function.toGenericStatement(builder);
    if (filter != null) {
      filter.toGenericStatement(builder);
    }
  }

  protected Iterable<Identifiable> traversePatternEdge(
      SQLMatchStatement.MatchContext matchContext,
      Identifiable startingPoint,
      CommandContext iCommandContext) {
    var qR = this.function.execute(startingPoint, iCommandContext);
    return (qR instanceof Iterable) ? (Iterable) qR : Collections.singleton((Identifiable) qR);
  }

  @Override
  public SQLMatchPathItem copy() {
    var result = (SQLMatchPathItemFirst) super.copy();
    result.function = function == null ? null : function.copy();
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (!super.equals(o)) {
      return false;
    }
    var that = (SQLMatchPathItemFirst) o;

    return Objects.equals(function, that.function);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (function != null ? function.hashCode() : 0);
    return result;
  }

  public SQLFunctionCall getFunction() {
    return function;
  }

  public void setFunction(SQLFunctionCall function) {
    this.function = function;
  }

  @Override
  public SQLMethodCall getMethod() {
    if (methodWrapper == null) {
      synchronized (this) {
        if (methodWrapper == null) {
          methodWrapper = new SQLMethodCall(-1);
          methodWrapper.params = function.params;
          methodWrapper.methodName = function.name;
        }
      }
    }
    return methodWrapper;
  }
}
