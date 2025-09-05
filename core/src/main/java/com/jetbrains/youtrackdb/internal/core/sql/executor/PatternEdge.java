package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;

public class PatternEdge {

  public PatternNode in;
  public PatternNode out;
  public SQLMatchPathItem item;

  public Iterable<Identifiable> executeTraversal(
      SQLMatchStatement.MatchContext matchContext,
      CommandContext iCommandContext,
      Identifiable startingPoint,
      int depth) {
    return item.executeTraversal(matchContext, iCommandContext, startingPoint, depth);
  }

  @Override
  public String toString() {
    return "{as: " + in.alias + "}" + item.toString();
  }
}
