package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Page provenance for one crash replay. Only the replay thread accesses this object. The write
 * cache never passes it to a flush or validation task.
 */
public final class RecoveryPageContext {

  private Map<Integer, TreeMap<Long, LogSequenceNumber>> declaredPages = Map.of();
  private LogSequenceNumber currentRecord;

  public void setDeclaredPages(Map<Integer, TreeMap<Long, LogSequenceNumber>> pages) {
    declaredPages = pages == null ? Map.of() : pages;
  }

  public void setCurrentRecord(@Nullable LogSequenceNumber position) {
    currentRecord = position;
  }

  /** Whether this page has an allocation position in the current replay unit. */
  public boolean isDeclaredPage(int fileId, long pageIndex) {
    return declaredPosition(fileId, pageIndex) != null;
  }

  @Nullable public LogSequenceNumber declaredPosition(int fileId, long pageIndex) {
    final var filePages = declaredPages.get(fileId);
    return filePages == null ? null : filePages.get(pageIndex);
  }

  /** Use an allocation's position for a declared page, otherwise the current redo position. */
  @Nullable public LogSequenceNumber positionForReplayLoad(int fileId, long pageIndex) {
    final var declared = declaredPosition(fileId, pageIndex);
    return declared != null ? declared : currentRecord;
  }
}
