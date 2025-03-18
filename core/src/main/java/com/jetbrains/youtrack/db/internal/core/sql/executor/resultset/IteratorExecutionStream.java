package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.Iterator;
import java.util.Map;

public class IteratorExecutionStream implements ExecutionStream {

  private final Iterator<?> iterator;
  private final String alias;

  public IteratorExecutionStream(Iterator<?> iter, String alias) {
    this.iterator = iter;
    this.alias = alias;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    return iterator.hasNext();
  }

  @Override
  public Result next(CommandContext ctx) {
    var val = iterator.next();
    if (val instanceof Result) {
      return (Result) val;
    }

    ResultInternal result;
    var db = ctx.getDatabaseSession();
    if (val instanceof Identifiable) {

      if (alias != null) {
        throw new CommandExecutionException(db,
            "Cannot expand a record with a non-null alias: " + alias);
      }
      result = new ResultInternal(db, (Identifiable) val);
    } else if (val instanceof Map.Entry<?,?> entry) {

      if (alias != null) {
        throw new CommandExecutionException(db,
            "Cannot expand a map with a non-null alias: " + alias);
      }
      result = new ResultInternal(db);
      result.setProperty(entry.getKey().toString(), entry.getValue());
    } else {
      result = new ResultInternal(db);
      result.setProperty(alias == null ? "value" : alias, val);
    }
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
  }
}
