package com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand;

import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Discriminated union for SQL command execution outcomes.
/// Either the command produced no results ([Unit]) or it returned an iterator of mapped
/// Gremlin values ([Results]).
public sealed interface SqlCommandExecutionResult {

  /// The command executed successfully but produced no result rows (e.g. BEGIN, COMMIT, DDL).
  record Unit() implements SqlCommandExecutionResult {
    private static final Unit INSTANCE = new Unit();
  }

  /// The command produced a stream of Gremlin-typed values.
  record Results(CloseableIterator<Object> iterator) implements SqlCommandExecutionResult {
    public Results {
      if (iterator == null) {
        throw new IllegalArgumentException("Iterator cannot be null");
      }
    }
  }

  static SqlCommandExecutionResult unit() {
    return Unit.INSTANCE;
  }

  static SqlCommandExecutionResult results(CloseableIterator<Object> iterator) {
    return new Results(iterator);
  }
}
