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

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import javax.annotation.Nonnull;

/**
 * Builds the {@link TxDeltaCursor} that reconciles a {@link CachedEntry}'s frozen RECORD output
 * against the in-transaction mutations that landed after the entry was populated. Stateless: every
 * method takes the entry, the transaction, and the original query's command context, so a single
 * shared instance (or static use) serves all entries.
 *
 * <p><b>Populate-version filter.</b> Only mutations whose {@link RecordOperation#version}
 * exceeds {@link CachedEntry#getPopulateMutationVersion()} enter the build. Every earlier mutation is
 * already reflected in {@link CachedEntry#getResults()} by the transaction-aware executor that
 * populated the entry, so replaying it here would double-apply it. Because {@code
 * addRecordOperation} re-stamps {@code version} to the latest mutation on its collapse path, a single
 * collapsed operation carries the version of its most recent change; the filter therefore admits a
 * collapsed pre-populate record exactly when a post-populate mutation touched it.
 *
 * <p><b>Dispatch table.</b> For each surviving operation the build computes two runtime facts —
 * {@code cached_at_build} (was the RID already pulled into {@link CachedEntry#getCachedRids()}?) and
 * {@code match_after} (does the post-mutation record still satisfy the WHERE clause, re-evaluated
 * with the original query's context so {@code :param} bindings resolve identically?) — then dispatches
 * on {@code (op.type, cached_at_build, match_after)}:
 *
 * <pre>
 *   op.type   cached  match  action
 *   CREATED   true    true   skip + inject  (collapsed pre-populate row whose ORDER BY key may have moved)
 *   CREATED   true    false  skip           (collapsed update drove WHERE false; drop the cached row)
 *   CREATED   false   true   inject         (true post-populate create; never in cache)
 *   CREATED   false   false  no-op          (true post-populate create that does not match)
 *   UPDATED   true    true   skip + inject  (re-position in case the ORDER BY key changed)
 *   UPDATED   true    false  skip           (no longer matches WHERE; remove from result)
 *   UPDATED   false   true   skip + inject  (not yet stream-pulled; inject post-mutation, suppress stale pull)
 *   UPDATED   false   false  skip           (suppress later stream-pull of the pre-mutation state)
 *   DELETED   *       *      skip           (suppress both the cache cursor and the stream pull)
 * </pre>
 *
 * <p>The collapse semantics make {@code cached_at_build} load-bearing even under the version filter:
 * {@code addRecordOperation} keeps a CREATE→UPDATE collapsed operation typed {@code CREATED} while
 * bumping its version, so a {@code CREATED} operation can be either a true post-populate create
 * (never in cache) or a pre-populate create whose post-populate update re-stamped it (in cache from
 * populate). {@code cached_at_build} is the runtime distinguisher: same type, different cache state,
 * different action. CREATE→DELETE and UPDATE→DELETE both collapse to {@code DELETED}, which always
 * skips regardless of the other two facts.
 *
 * <p><b>Cross-view sharing.</b> The {@code (skipSet, injectList)} pair is a pure function of the
 * entry's frozen metadata and the transaction's current mutation state, so two views built on one
 * entry at the same {@link FrontendTransactionImpl#getMutationVersion()} compute the identical pair.
 * The build caches the latest pair on the entry tagged by that version and reuses it when a later view
 * observes the same version, avoiding a re-walk of the mutation log. A view at a fresher version
 * rebuilds and overwrites the cached pair; older views keep their own reference to the prior pair via
 * their cursor, so the prior pair stays correct until those views finish.
 *
 * <p>Single-transaction state observed only by the owning thread; no synchronisation.
 */
public final class DeltaBuilder {

  private DeltaBuilder() {
  }

  /**
   * Builds (or reuses) the RECORD-shape delta cursor for {@code entry} against the transaction's
   * current mutation state. Honours the cross-view cache on the entry: when the entry already holds a
   * pair tagged at the transaction's current mutation version, the returned cursor wraps that shared
   * immutable pair; otherwise the build walks the version-filtered mutation snapshot, dispatches per the
   * table above, sorts the inject list by the entry's ORDER BY, promotes the new pair onto the entry,
   * and returns a cursor over it.
   *
   * @param entry the cached RECORD entry to reconcile
   * @param tx    the active transaction whose {@code recordOperations} carry the post-populate deltas
   * @param ctx   the original query's command context, reused so WHERE {@code :param} bindings resolve
   *              identically to the populating execution
   */
  @Nonnull
  public static TxDeltaCursor buildForRecord(
      @Nonnull CachedEntry entry,
      @Nonnull FrontendTransactionImpl tx,
      @Nonnull CommandContext ctx) {
    final var version = tx.getMutationVersion();

    // Fast path: another view on this entry already built the delta at this exact tx state. The
    // cached pair is immutable, so the new cursor shares it and only tracks its own inject position.
    if (entry.getCachedDeltaVersion() == version && entry.getCachedSkipSet() != null) {
      return new TxDeltaCursor(entry.getCachedSkipSet(), entry.getCachedInjectList());
    }

    // Snapshot the version-filtered operations before iterating: WHERE re-evaluation may invoke a UDF
    // that calls save(), which would structurally modify recordOperations mid-iteration. A snapshot
    // (a) keeps the iteration ConcurrentModificationException-free and (b) excludes any record a
    // UDF-triggered mutation adds during the build — that record becomes visible to the NEXT view,
    // when the mutation version has advanced and a fresh delta is built.
    final var snapshot = new ArrayList<RecordOperation>();
    for (final var op : tx.getRecordOperationsInternal()) {
      if (op.version > entry.getPopulateMutationVersion()) {
        snapshot.add(op);
      }
    }

    final var skipSet = new HashSet<RID>();
    final var injectList = new ArrayList<Result>();
    final var session = ctx.getDatabaseSession();

    for (final var op : snapshot) {
      final var record = op.record;
      // Class filter (subclass closure, O(1) hash-set probe). Non-Entity records (e.g. Blobs) and
      // entities whose schema class is null or outside the query's class closure are not part of
      // this RECORD query's result and contribute no delta.
      if (!(record instanceof Entity entity)) {
        continue;
      }
      final var className = entity.getSchemaClassName();
      if (className == null || !entry.getEffectiveFromClasses().contains(className)) {
        continue;
      }

      final var rid = record.getIdentity();
      final var cachedAtBuild = entry.getCachedRids().contains(rid);
      // Re-evaluate WHERE against the post-mutation record using the original query's context so
      // parameterized predicates bind identically. A null WHERE clause matches every record.
      final var whereClause = entry.getWhereClause();
      final var matchAfter = whereClause == null || whereClause.matchesFilters(record, ctx);

      switch (op.type) {
        case RecordOperation.DELETED ->
            // Suppress from both the cache cursor and the stream pull; no inject.
            skipSet.add(rid);
        case RecordOperation.CREATED -> {
          if (cachedAtBuild) {
            // Collapsed pre-populate create that absorbed a post-populate update in place: the row
            // is already in the cache, so always skip the cached copy. Re-inject the post-mutation
            // state only when it still matches WHERE (its ORDER BY position may have shifted).
            skipSet.add(rid);
            if (matchAfter) {
              injectList.add(new ResultInternal(session, record));
            }
          } else if (matchAfter) {
            // True post-populate create that matches: inject only (never emitted from cache, and its
            // temp RID was never reachable by the stream, so no skip is needed).
            injectList.add(new ResultInternal(session, record));
          }
          // cached=false, match=false: a true post-populate create that does not match — no-op.
        }
        case RecordOperation.UPDATED -> {
          // Always suppress the pre-mutation copy from the cache cursor and the stream pull; inject
          // the post-mutation state when it still matches WHERE.
          skipSet.add(rid);
          if (matchAfter) {
            injectList.add(new ResultInternal(session, record));
          }
        }
        default ->
            // RecordOperation only ever carries CREATED / UPDATED / DELETED; any other byte is a
            // contract violation upstream.
            throw new IllegalStateException(
                "Unexpected RecordOperation type " + op.type + " for RID " + rid);
      }
    }

    // Sort the inject list by the query's ORDER BY so the view's sorted-merge stays correct. With no
    // ORDER BY the rows keep their mutation-iteration order, matching the unsorted fresh-execution
    // contract for that query shape.
    final var orderBy = entry.getOrderBy();
    if (orderBy != null && injectList.size() > 1) {
      injectList.sort((a, b) -> orderBy.compare(a, b, ctx));
    }

    // Promote the new pair onto the entry (overwriting any older version's pair) using unmodifiable
    // wrappers so every cursor — first-build and reuse alike — shares the identical immutable surface.
    final var sharedSkipSet = Collections.unmodifiableSet(skipSet);
    final var sharedInjectList = Collections.unmodifiableList(injectList);
    entry.setCachedSkipSet(sharedSkipSet);
    entry.setCachedInjectList(sharedInjectList);
    entry.setCachedDeltaVersion(version);

    return new TxDeltaCursor(sharedSkipSet, sharedInjectList);
  }
}
