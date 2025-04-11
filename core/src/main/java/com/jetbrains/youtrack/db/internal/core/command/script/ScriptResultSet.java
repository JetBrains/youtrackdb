package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.IteratorResultSet;
import java.util.Iterator;
import javax.annotation.Nullable;

/**
 * Wrapper of IteratorResultSet Used in script results with conversion to Result for single
 * iteration
 */
public class ScriptResultSet extends IteratorResultSet {

  protected ScriptTransformer transformer;

  public ScriptResultSet(@Nullable DatabaseSessionInternal session, Iterator iter,
      ScriptTransformer transformer) {
    super(session, iter);
    this.transformer = transformer;
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();
    var next = iterator.next();
    return transformer.toResult(session, next);
  }
}
