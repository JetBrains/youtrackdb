package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Iterator;

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
    return ResultInternal.toResult(val, ctx.getDatabaseSession(), alias);
  }

  /**
   * Propagates {@code close()} to the wrapped iterator when it is
   * {@link AutoCloseable}. Iterators that are not closeable (the common case)
   * are simply ignored. This hook exists so iterators that accumulate
   * side-channel state during iteration (e.g. the LinkBag iterators flushing
   * the pre-filter effectiveness metric) can release that state even when the
   * stream is short-circuited by a LIMIT clause or an exception, instead of
   * relying solely on natural exhaustion via {@code hasNext()}.
   */
  @Override
  public void close(CommandContext ctx) {
    if (iterator instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception e) {
        // Pass the iterator class name inline (not as a vararg) so Java's
        // overload resolution unambiguously selects
        // {@code warn(Object, String, Throwable)} — without this, the
        // {@code warn(Object, String dbName, String message, Object...)}
        // overload wins instead and treats the message as a database name.
        LogManager.instance().warn(this,
            "Failed to close wrapped iterator " + iterator.getClass().getSimpleName(),
            e);
      }
    }
  }
}
