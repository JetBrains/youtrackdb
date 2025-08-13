package com.jetbrains.youtrackdb.internal.lucene.functions;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.core.sql.functions.IndexableSQLFunction;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.lucene.collections.LuceneResultSet;
import com.jetbrains.youtrackdb.internal.lucene.index.LuceneFullTextIndex;
import java.util.Map;
import javax.annotation.Nullable;

public abstract class LuceneSearchFunctionTemplate extends SQLFunctionAbstract
    implements IndexableSQLFunction {

  public LuceneSearchFunctionTemplate(String iName, int iMinParams, int iMaxParams) {
    super(iName, iMinParams, iMaxParams);
  }

  @Override
  public boolean canExecuteInline(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {
    return allowsIndexedExecution(target, operator, rightValue, ctx, args);
  }

  @Override
  public boolean allowsIndexedExecution(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {
    var index = searchForIndex(target, ctx, args);
    return index != null;
  }

  @Override
  public boolean shouldExecuteAfterSearch(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {
    return false;
  }

  @Override
  public long estimate(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {

    var a = searchFromTarget(target, operator, rightValue, ctx, args);
    if (a instanceof LuceneResultSet) {
      return ((LuceneResultSet) a).size();
    }
    long count = 0;
    for (Object o : a) {
      count++;
    }

    return count;
  }

  protected static Map<String, ?> getMetadata(SQLExpression metadata, CommandContext ctx) {
    final var md = metadata.execute((Identifiable) null, ctx);
    if (md instanceof EntityImpl document) {
      return document.toMap();
    } else if (md instanceof Map map) {
      return map;
    } else if (md instanceof String) {
      return JSONSerializerJackson.INSTANCE.mapFromJson((String) md);
    } else {
      return JSONSerializerJackson.INSTANCE.mapFromJson(metadata.toString());
    }
  }

  @Nullable
  protected abstract LuceneFullTextIndex searchForIndex(
      SQLFromClause target, CommandContext ctx, SQLExpression... args);
}
