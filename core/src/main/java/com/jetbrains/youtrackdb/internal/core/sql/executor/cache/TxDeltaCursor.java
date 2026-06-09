/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A per-view snapshot of the in-transaction mutations relevant to one {@link CachedEntry}, produced
 * by {@link DeltaBuilder#buildForRecord}. It carries two immutable halves and one mutable cursor:
 *
 * <ul>
 *   <li>{@link #skipSet} — RIDs the merged view must suppress from BOTH the cached-result cursor and
 *       the lazy stream-pull, because a post-populate mutation either dropped them (DELETED, or
 *       UPDATED that no longer matches WHERE) or re-positioned them (so the post-mutation copy comes
 *       from {@link #injectList} instead). Consulted on every cache-cursor advance and on every
 *       stream-pull-append.
 *   <li>{@link #injectList} — the post-mutation {@code Result} rows the view must merge into its
 *       output, already sorted by the entry's ORDER BY (or in mutation-iteration order when there is
 *       no ORDER BY). The sole source of truth for the current state of every mutated record the
 *       query still selects.
 *   <li>{@link #injectPosition} — the per-view iteration cursor into {@link #injectList}. This is the
 *       only mutable field; it lets two views built at the same mutation version share the same
 *       immutable {@code (skipSet, injectList)} pair while each tracking its own progress.
 * </ul>
 *
 * <p>The {@code skipSet} and {@code injectList} are immutable and may be shared by several cursors
 * (cross-view delta sharing keyed on the entry's mutation version, see {@link
 * CachedEntry#getCachedDeltaVersion()}). Only {@link #injectPosition} is per-view state, so a cursor
 * is single-view, single-thread, and not safe to share. The shared halves never change after
 * construction, so a view started at an older mutation version keeps emitting a coherent snapshot
 * even after a fresher view rebuilt the entry's cached pair.
 *
 * <p>Single-transaction state observed only by the owning thread; no field is synchronised.
 */
public final class TxDeltaCursor {

  /** RIDs suppressed from both the cache cursor and the stream pull. Immutable; may be shared. */
  private final Set<RID> skipSet;

  /** Post-mutation rows to merge into the view, ORDER BY-sorted. Immutable; may be shared. */
  private final List<Result> injectList;

  /** Per-view position into {@link #injectList}; the only mutable field. */
  private int injectPosition;

  public TxDeltaCursor(@Nonnull Set<RID> skipSet, @Nonnull List<Result> injectList) {
    this.skipSet = skipSet;
    this.injectList = injectList;
    this.injectPosition = 0;
  }

  /**
   * Whether the given RID must be suppressed from the merged output. A view drops any cache-cursor
   * head and any stream-pulled row whose RID this returns {@code true} for; the post-mutation copy of
   * a re-positioned record reaches the output through {@link #injectList} instead.
   */
  public boolean shouldSkip(@Nonnull RID rid) {
    return skipSet.contains(rid);
  }

  /** Whether the inject cursor has more rows to emit. */
  public boolean hasNextInject() {
    return injectPosition < injectList.size();
  }

  /**
   * The inject row at the current position without advancing, or {@code null} when the cursor is
   * exhausted. The view peeks to compare ORDER BY keys against the cache-cursor head before deciding
   * which side emits next.
   */
  @Nullable public Result peekInject() {
    if (injectPosition >= injectList.size()) {
      return null;
    }
    return injectList.get(injectPosition);
  }

  /**
   * Returns the inject row at the current position and advances the cursor. Throws {@link
   * IndexOutOfBoundsException} if called when {@link #hasNextInject()} is false; callers peek first.
   */
  @Nonnull
  public Result advanceInject() {
    return injectList.get(injectPosition++);
  }

  /** The number of inject rows (independent of the per-view position). */
  public int injectSize() {
    return injectList.size();
  }

  /** The skip-set, for assertions and the view's stream-pull-append filter. */
  @Nonnull
  public Set<RID> getSkipSet() {
    return skipSet;
  }

  /** The immutable inject list, for assertions and view replay. */
  @Nonnull
  public List<Result> getInjectList() {
    return injectList;
  }
}
