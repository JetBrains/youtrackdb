package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.QueryDatabaseState;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public class RemoteResultSet implements ResultSet {

  @Nullable
  private DatabaseSessionRemote session;
  private final String queryId;
  private List<Result> currentPage;
  private ExecutionPlan executionPlan;
  private Map<String, Long> queryStats;
  private boolean hasNextPage;

  public RemoteResultSet(
      @Nullable DatabaseSessionRemote session,
      String queryId,
      List<Result> currentPage,
      ExecutionPlan executionPlan,
      Map<String, Long> queryStats,
      boolean hasNextPage) {
    this.session = session;
    this.queryId = queryId;
    this.currentPage = currentPage;
    this.executionPlan = executionPlan;
    this.queryStats = queryStats;
    this.hasNextPage = hasNextPage;
    if (session != null) {
      session.queryStarted(queryId, new QueryDatabaseState(this));
      for (var result : currentPage) {
        if (result instanceof ResultInternal resultInternal) {
          resultInternal.refreshNonPersistentRid();
        }
      }
    }
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
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
  public Result next() {
    assert session == null || session.assertIfNotActive();
    if (currentPage.isEmpty()) {
      if (!hasNextPage()) {
        throw new IllegalStateException();
      }
      fetchNextPage();
    }
    if (currentPage.isEmpty()) {
      throw new IllegalStateException();
    }
    var internal = currentPage.removeFirst();

    if (internal.isRecord() && session != null && session.isTxActive()) {
      var tx = session.getTransactionInternal();
      if (!tx.isDeletedInTx(internal.getIdentity())) {
        DBRecord record = tx.getRecord(internal.getIdentity());
        if (record != null) {
          internal = new ResultInternal(session, record);
        }
      }
    }
    return internal;
  }

  @Override
  public void close() {
    assert session == null || session.assertIfNotActive();
    if (hasNextPage && session != null) {
      // CLOSES THE QUERY SERVER SIDE ONLY IF THERE IS ANOTHER PAGE. THE SERVER ALREADY
      // AUTOMATICALLY CLOSES THE QUERY AFTER SENDING THE LAST PAGE
      session.closeQuery(queryId);
    }
    if (session != null) {
      var tx = session.getTransactionInternal();
      //read only transactions are initiated only for queries and only if there is no active transaction
      if (tx.isActive() && tx.isReadOnly()) {
        tx.rollbackInternal();
      }
    }
    this.session = null;
  }

  @Override
  public ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();
    return executionPlan;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }

  public void add(ResultInternal item) {
    assert session == null || session.assertIfNotActive();
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
      List<Result> result,
      boolean hasNextPage,
      ExecutionPlan executionPlan,
      Map<String, Long> queryStats) {
    assert session == null || session.assertIfNotActive();
    this.currentPage = result;
    this.hasNextPage = hasNextPage;

    if (queryStats != null) {
      this.queryStats = queryStats;
    }
    if (executionPlan != null) {
      this.executionPlan = executionPlan;
    }
  }

  @Override
  public boolean tryAdvance(Consumer<? super Result> action) {
    if (hasNext()) {
      action.accept(next());
      return true;
    }
    return false;
  }

  @Override
  public ResultSet trySplit() {
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
  public void forEachRemaining(Consumer<? super Result> action) {
    while (hasNext()) {
      action.accept(next());
    }
  }
}
