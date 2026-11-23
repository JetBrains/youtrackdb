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
    return executeWithEvidence(row, value, ctx, row);
  }

  Object executeWithEvidence(Result row, Object value, CommandContext ctx, Result evidenceRow) {
    if (evidenceRow instanceof ResultInternal internal
        && internal.getBoundedToSession() != null
        && internal.getBoundedToSession() == ctx.getDatabaseSession()) {
      var record = internal.getReusableRecord(alias);
      // An alias may also resolve through a context variable. Never use evidence for another value.
      if (record != null && record.getIdentity().equals(value)) {
        if (ctx.getSystemVariable(CommandContext.VAR_CURRENT) == null) {
          ctx.setSystemVariable(CommandContext.VAR_CURRENT, row);
        }
        // Keep Identifiable semantics, including context fallback and invalid property names.
        // Without valid evidence, the ordinary path must still check dangling identities.
        var result = identityOnly ? record.getIdentity()
            : suffix.execute((Identifiable) record, ctx);
        return next == null ? result : next.execute(row, result, ctx);
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
