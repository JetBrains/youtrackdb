package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResultSet;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.graalvm.nativeimage.c.struct.SizeOf;

public class PaginatedResultSet implements RemoteResultSet {

  @Nullable
  private DatabaseSessionRemote session;
  private final String queryId;
  private List<RemoteResult> currentPage;
  private boolean hasNextPage;

  private final boolean closed = false;

  public PaginatedResultSet(
      @Nullable DatabaseSessionRemote session,
      String queryId,
      List<RemoteResult> currentPage,
      boolean hasNextPage) {
    this.session = session;
    this.queryId = queryId;
    this.currentPage = currentPage;
    this.hasNextPage = hasNextPage;

    if (session != null) {
      session.queryStarted(queryId, this);
    }
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      return false;
    }
    ;

    if (!currentPage.isEmpty()) {
      return true;
    }
    if (!hasNextPage()) {
      return false;
    }

    fetchNextPage();

    return !currentPage.isEmpty();
  }

  private void fetchNextPage() {
    assert session == null || session.assertIfNotActive();

    if (session != null) {
      session.fetchNextPage(this);
    }
  }

  @Override
  public RemoteResult next() {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      throw new NoSuchElementException();
    }

    if (currentPage.isEmpty()) {
      if (!hasNextPage()) {
        throw new NoSuchElementException();
      }

      fetchNextPage();
    }

    if (currentPage.isEmpty()) {
      throw new NoSuchElementException();
    }

    return currentPage.removeFirst();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }

    assert session == null || session.assertIfNotActive();
    if (hasNextPage && session != null) {
      // CLOSES THE QUERY SERVER SIDE ONLY IF THERE IS ANOTHER PAGE. THE SERVER ALREADY
      // AUTOMATICALLY CLOSES THE QUERY AFTER SENDING THE LAST PAGE
      session.closeQuery(queryId);
    }

    this.session = null;
  }

  @Nullable
  @Override
  public RemoteDatabaseSession getBoundToSession() {
    return session;
  }

  public void add(RemoteResult item) {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      throw new IllegalStateException("ResultSet is closed and can not be used.");
    }

    currentPage.add(item);
  }

  public boolean hasNextPage() {
    assert session == null || session.assertIfNotActive();

    return hasNextPage;
  }

  public String getQueryId() {
    assert session == null || session.assertIfNotActive();
    return queryId;
  }

  public void fetched(
      List<RemoteResult> result,
      boolean hasNextPage) {
    assert session == null || session.assertIfNotActive();
    this.currentPage = result;
    this.hasNextPage = hasNextPage;
  }

  @Override
  public boolean tryAdvance(Consumer<? super RemoteResult> action) {
    if (hasNext()) {
      action.accept(next());
      return true;
    }
    return false;
  }

  @Override
  public PaginatedResultSet trySplit() {
    //noinspection ReturnOfNull
    return null;
  }

  @Override
  public long estimateSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public int characteristics() {
    return ORDERED;
  }

  @Override
  public void forEachRemaining(@Nonnull Consumer<? super RemoteResult> action) {
    while (hasNext()) {
      action.accept(next());
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

}
