package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Page provenance for one crash replay. Only the replay thread accesses this object. The write
 * cache never passes it to a flush or validation task.
 */
public final class RecoveryPageContext {

  private final Map<Integer, Map<Long, LogSequenceNumber>> gapPages = new HashMap<>();
  private Map<Integer, TreeMap<Long, LogSequenceNumber>> declaredPages = Map.of();
  private LogSequenceNumber currentRecord;

  public void setDeclaredPages(Map<Integer, TreeMap<Long, LogSequenceNumber>> pages) {
    declaredPages = pages == null ? Map.of() : pages;
  }

  public void setCurrentRecord(@Nullable LogSequenceNumber position) {
    currentRecord = position;
  }

  /** Records pages created by replay before their asynchronous validation writes complete. */
  public void addCreatedPage(int fileId, long pageIndex) {
    if (currentRecord != null) {
      gapPages.computeIfAbsent(fileId, ignored -> new HashMap<>())
          .put(pageIndex, currentRecord);
    }
  }

  /** A gap page has one chance to be rebuilt. Declarations remain valid for their whole unit. */
  @Nullable public LogSequenceNumber consumeGapPage(int fileId, long pageIndex) {
    final var filePages = gapPages.get(fileId);
    if (filePages == null) {
      return null;
    }
    final var position = filePages.remove(pageIndex);
    if (filePages.isEmpty()) {
      gapPages.remove(fileId);
    }
    return position;
  }

  @Nullable public LogSequenceNumber declaredPosition(int fileId, long pageIndex) {
    final var filePages = declaredPages.get(fileId);
    return filePages == null ? null : filePages.get(pageIndex);
  }

  /** Retain the earliest known cause when a later unit first reads an unstamped gap page. */
  public LogSequenceNumber positionForReplayLoad(int fileId, long pageIndex,
      LogSequenceNumber recordPosition) {
    final var declared = declaredPosition(fileId, pageIndex);
    if (declared != null) {
      return declared;
    }
    final var gaps = gapPages.get(fileId);
    if (gaps != null && gaps.containsKey(pageIndex)) {
      return gaps.get(pageIndex);
    }
    return recordPosition;
  }

  public void removeFile(int fileId) {
    gapPages.remove(fileId);
  }
}
