package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCollection;
import java.util.List;

/** Literal type names with an immutable execution value, shared safely across plan copies. */
final class TypeNamesCollection extends SQLCollection {

  private final List<String> names;

  TypeNamesCollection(List<String> names) {
    super(-1);
    this.names = List.copyOf(names);
    for (var name : this.names) {
      add(MatchLiteralBuilder.toLiteral(name));
    }
  }

  @Override
  public Object execute(Identifiable record, CommandContext ctx) {
    return names;
  }

  @Override
  public Object execute(Result record, CommandContext ctx) {
    return names;
  }

  @Override
  public TypeNamesCollection copy() {
    return new TypeNamesCollection(names);
  }
}
