package com.jetbrains.youtrack.db.internal.core.gremlin.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import java.nio.channels.ClosedChannelException;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalInterruptedException;

public final class YTDBGremlinScriptResultSet implements ResultSet {

  private final Traversal<?, ?> traversal;
  private final ScriptTransformer transformer;

  private final DatabaseSessionEmbedded session;
  private boolean closed;

  public YTDBGremlinScriptResultSet(DatabaseSessionEmbedded session,
      Traversal<?, ?> traversal, ScriptTransformer transformer) {
    this.traversal = traversal;
    this.transformer = transformer;
    this.session = session;
  }

  @Override
  public boolean hasNext() {
    if (closed) {
      return false;
    }
    try {
      return traversal.hasNext();
    } catch (StorageException se) {
      if (se.getCause() instanceof TraversalInterruptedException
          || se.getCause() instanceof ClosedChannelException) {
        throw new TraversalInterruptedException();
      }
      throw se;
    }
  }

  @Override
  public Result next() {
    if (closed) {
      throw new NoSuchElementException();
    }

    try {
      var next = traversal.next();
      return transformer.toResult(session, next);
    } catch (StorageException se) {
      if (se.getCause() instanceof TraversalInterruptedException
          || se.getCause() instanceof ClosedChannelException) {
        throw new TraversalInterruptedException();
      }
      throw se;
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }

    closed = true;
    try {
      traversal.close();
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommandExecutionException(session, "Error closing the gremlin Result Set"), e,
          session);
    }
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }

  @Override
  public boolean tryAdvance(Consumer<? super Result> action) {
    if (hasNext()) {
      var result = next();
      action.accept(result);
      return true;
    }

    return false;
  }

  @Override
  public void forEachRemaining(@Nonnull Consumer<? super Result> action) {
    while (hasNext()) {
      var result = next();
      action.accept(result);
    }
  }

  @Override
  @Nullable
  public Spliterator<Result> trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return -1;
  }

  @Override
  public int characteristics() {
    return ORDERED;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Nullable
  @Override
  public ExecutionPlan getExecutionPlan() {
    return null;
  }
}
