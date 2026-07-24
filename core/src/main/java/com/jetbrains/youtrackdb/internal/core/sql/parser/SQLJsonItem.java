package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 *
 */
public class SQLJsonItem {

  protected SQLIdentifier leftIdentifier;
  protected String leftString;
  protected SQLExpression right;

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    if (leftIdentifier != null) {
      builder.append("\"");
      leftIdentifier.toString(params, builder);
      builder.append("\"");
    }
    if (leftString != null) {
      builder.append("\"");
      builder.append(SQLExpression.encode(leftString));
      builder.append("\"");
    }
    builder.append(": ");
    right.toString(params, builder);
  }

  public void toGenericStatement(StringBuilder builder) {
    if (leftIdentifier != null) {
      builder.append("\"");
      leftIdentifier.toGenericStatement(builder);
      builder.append("\"");
    }
    if (leftString != null) {
      builder.append("\"");
      builder.append(SQLExpression.encode(leftString));
      builder.append("\"");
    }
    builder.append(": ");
    right.toGenericStatement(builder);
  }

  @Nullable
  public String getLeftValue() {
    if (leftString != null) {
      return leftString;
    }
    if (leftIdentifier != null) {
      return leftIdentifier.getStringValue();
    }
    return null;
  }

  public boolean needsAliases(Set<String> aliases) {
    if (aliases.contains(leftIdentifier.getStringValue())) {
      return true;
    }
    return right.needsAliases(aliases);
  }

  public boolean isAggregate(DatabaseSessionEmbedded session) {
    return right.isAggregate(session);
  }

  public SQLJsonItem splitForAggregation(
      AggregateProjectionSplit aggregateSplit, CommandContext ctx) {
    if (isAggregate(ctx.getDatabaseSession())) {
      var item = new SQLJsonItem();
      item.leftIdentifier = leftIdentifier;
      item.leftString = leftString;
      item.right = right.splitForAggregation(aggregateSplit, ctx);
      return item;
    } else {
      return this;
    }
  }

  public SQLJsonItem copy() {
    var result = new SQLJsonItem();
    result.leftIdentifier = leftIdentifier == null ? null : leftIdentifier.copy();
    result.leftString = leftString;
    result.right = right.copy();
    return result;
  }

  public void extractSubQueries(SubQueryCollector collector) {
    right.extractSubQueries(collector);
  }

  public boolean refersToParent() {
    return right != null && right.refersToParent();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var oJsonItem = (SQLJsonItem) o;

    if (!Objects.equals(leftIdentifier, oJsonItem.leftIdentifier)) {
      return false;
    }
    if (!Objects.equals(leftString, oJsonItem.leftString)) {
      return false;
    }
    return Objects.equals(right, oJsonItem.right);
  }

  @Override
  public int hashCode() {
    var result = leftIdentifier != null ? leftIdentifier.hashCode() : 0;
    result = 31 * result + (leftString != null ? leftString.hashCode() : 0);
    result = 31 * result + (right != null ? right.hashCode() : 0);
    return result;
  }
}
