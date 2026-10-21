package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLModifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSuffixIdentifier;

/** Plain alias dereference that preserves execution-local checked evidence across row layers. */
public final class MatchAliasModifier extends SQLModifier {

  private final String alias;
  private final boolean identityOnly;

  public MatchAliasModifier(String alias, SQLSuffixIdentifier suffix, boolean identityOnly) {
    super(-1);
    this.alias = alias;
    this.suffix = suffix;
    this.identityOnly = identityOnly;
  }

  @Override
  public Object execute(Result row, Object value, CommandContext ctx) {
    // An identity is already in the row. Reading it needs neither record contents nor a check.
    if (identityOnly && value instanceof Identifiable identifiable) {
      if (ctx.getSystemVariable(CommandContext.VAR_CURRENT) == null) {
        ctx.setSystemVariable(CommandContext.VAR_CURRENT, row);
      }
      var identity = identifiable.getIdentity();
      return next == null ? identity : next.execute(row, identity, ctx);
    }
    if (row instanceof ResultInternal internal
        && internal.getBoundedToSession() != null
        && internal.getBoundedToSession() == ctx.getDatabaseSession()) {
      var record = internal.getReusableRecord(alias);
      // An alias may also resolve through a context variable. Never use evidence for another value.
      if (record != null && record.getIdentity().equals(value)) {
        value = record;
      }
    }
    return super.execute(row, value, ctx);
  }

  @Override
  public MatchAliasModifier copy() {
    var result = new MatchAliasModifier(alias, suffix.copy(), identityOnly);
    result.next = next == null ? null : next.copy();
    return result;
  }
}
