package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/** Estimate-only view. The executable filter always remains in aliasFilters. */
final class PresenceDensityFilter extends SQLWhereClause {

  final PresenceDensity density;

  PresenceDensityFilter(PresenceDensity density) {
    super(-1);
    this.density = density;
    // The narrow comparison accepts only a pure promoted-presence target. Other target
    // predicates retain G5, rather than inventing independence from property presence.
    setBaseExpression(SQLBooleanExpression.TRUE);
  }

  @Override
  public long estimate(SchemaClassInternal clazz, long threshold, CommandContext context) {
    long base = density.candidates() + 1;
    // A statistical zero never proves an empty result.
    return Math.max(1, (long) Math.ceil(base * density.density()));
  }

  @Override
  public SQLWhereClause copy() {
    return new PresenceDensityFilter(density);
  }
}
