package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** One consumer-owned stream execution, bound to the session that opened it. */
public final class StreamQueryHandle implements RegisteredQuery {

  // The reserved prefix keeps process-wide stream IDs distinct from result-set IDs.
  private static final AtomicLong NEXT_ID = new AtomicLong();

  private final String id = "stream-query-" + NEXT_ID.incrementAndGet();
  private final DatabaseSessionEmbedded session;
  private final Runnable closeExecution;
  private final Supplier<String> description;
  private boolean retired;

  public StreamQueryHandle(DatabaseSessionEmbedded session, Runnable closeExecution,
      Supplier<String> description) {
    this.session = session;
    this.closeExecution = closeExecution;
    this.description = description;
  }

  public void register() {
    session.queryStarted(id, this);
  }

  /** Retire without closing the owner, so a live plan can be rewound for a fresh arming. */
  public void retire() {
    if (retired) {
      return;
    }
    retired = true;
    session.queryClosed(id);
  }

  @Override
  public void close() {
    if (!retired) {
      closeExecution.run();
    }
  }

  @Override
  public String getDescription() {
    return description.get();
  }
}
