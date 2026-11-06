package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLModifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSuffixIdentifier;

/** Passes the effective $matched row to the alias dereference without retaining runtime values. */
public final class MatchedVariableModifier extends SQLModifier {

  private final MatchAliasModifier aliasAccess;

  public MatchedVariableModifier(SQLSuffixIdentifier suffix, MatchAliasModifier aliasAccess) {
    super(-1);
    this.suffix = suffix;
    this.next = aliasAccess;
    this.aliasAccess = aliasAccess;
  }

  @Override
  public Object execute(Result row, Object value, CommandContext ctx) {
    if (value instanceof Result matched) {
      // CURRENT belongs to the outer evaluation, but checked evidence belongs to $matched.
      if (ctx.getSystemVariable(CommandContext.VAR_CURRENT) == null) {
        ctx.setSystemVariable(CommandContext.VAR_CURRENT, row);
      }
      var result = suffix.execute(matched, ctx);
      return aliasAccess.executeWithEvidence(row, result, ctx, matched);
    }
    return super.execute(row, value, ctx);
  }

  @Override
  public MatchedVariableModifier copy() {
    return new MatchedVariableModifier(suffix.copy(), aliasAccess.copy());
  }
}
