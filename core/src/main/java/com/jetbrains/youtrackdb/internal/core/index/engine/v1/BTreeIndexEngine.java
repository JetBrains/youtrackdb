package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import javax.annotation.Nullable;

public interface BTreeIndexEngine extends V1IndexEngine {

  int VERSION = 4;

  /** Returns the histogram manager, or null if not yet initialized. */
  @Nullable IndexHistogramManager getHistogramManager();

  /** Sets (or clears) the histogram manager for this engine. */
  void setHistogramManager(@Nullable IndexHistogramManager histogramManager);

  @Nullable @Override
  default IndexStatistics getStatistics() {
    var mgr = getHistogramManager();
    return mgr != null ? mgr.getStatistics() : null;
  }

  @Nullable @Override
  default EquiDepthHistogram getHistogram() {
    var mgr = getHistogramManager();
    return mgr != null ? mgr.getHistogram() : null;
  }

  /**
   * Builds the initial histogram from the current B-tree contents.
   * Called after index creation + population (rebuild), or during migration.
   *
   * <p>Each engine implementation knows how to obtain the sorted key stream,
   * total count, and null count from its internal B-tree structures.
   *
   * @param atomicOperation current atomic operation for page I/O
   */
  void buildInitialHistogram(AtomicOperation atomicOperation) throws IOException;

  /**
   * Returns the approximate number of null entries in the index. O(1) read
   * from a maintained counter, recalibrated by {@link #buildInitialHistogram}
   * and {@code load()}.
   *
   * @param atomicOperation current atomic operation (unused, kept for API
   *     compatibility)
   */
  long getNullCount(AtomicOperation atomicOperation);

  /**
   * Returns the approximate total number of entries (including nulls). O(1)
   * read from a maintained counter, recalibrated by
   * {@link #buildInitialHistogram} and {@code load()}.
   *
   * @param atomicOperation current atomic operation (unused, kept for API
   *     compatibility)
   */
  long getTotalCount(AtomicOperation atomicOperation);

  /**
   * Adjusts the approximate total entry count by the given delta. Called by
   * {@code AbstractStorage.applyIndexCountDeltas()} after a successful commit.
   */
  void addToApproximateEntryCount(long delta);

  /**
   * Adjusts the approximate null entry count by the given delta. Called by
   * {@code AbstractStorage.applyIndexCountDeltas()} after a successful commit.
   */
  void addToApproximateNullCount(long delta);
}
