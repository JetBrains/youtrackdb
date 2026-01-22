package com.jetbrains.youtrackdb.internal.core.util;

import java.util.Iterator;
import java.util.function.Consumer;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Wrapper over [Iterator] with custom closing logic.
public class CloseableIteratorWithCallback<T> implements CloseableIterator<T> {

  private final Iterator<T> underlying;
  private final Runnable onClose;
  private boolean closed = false;

  public CloseableIteratorWithCallback(Iterator<T> underlying, Runnable onClose) {
    this.underlying = underlying;
    this.onClose = onClose;
  }

  @Override
  public boolean hasNext() {
    final var hasNext = underlying.hasNext();
    if (!hasNext && !closed) {
      close();
    }
    return hasNext;
  }

  @Override
  public T next() {
    return underlying.next();
  }

  @Override
  public void remove() {
    underlying.remove();
  }

  @Override
  public void forEachRemaining(Consumer<? super T> action) {
    underlying.forEachRemaining(action);
    close();
  }

  @Override
  public void close() {
    if (!closed) {
      CloseableIterator.closeIterator(underlying);
      onClose.run();
      closed = true;
    }
  }
}
