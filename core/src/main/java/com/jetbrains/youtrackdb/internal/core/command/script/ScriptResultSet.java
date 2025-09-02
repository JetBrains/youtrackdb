package com.jetbrains.youtrackdb.internal.core.command.script;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.IteratorResultSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * Wrapper of IteratorResultSet Used in script results with conversion to Result for single
 * iteration
 */
public class ScriptResultSet extends IteratorResultSet {

  protected ScriptTransformer transformer;

  public ScriptResultSet(@Nullable DatabaseSessionInternal session, Iterator<?> iter,
      ScriptTransformer transformer) {
    super(session, iter);
    this.transformer = transformer;
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();

    if (!iterator.hasNext()) {
      throw new NoSuchElementException();
    }

    var next = iterator.next();
    return transformer.toResult(session, next);
  }
}
