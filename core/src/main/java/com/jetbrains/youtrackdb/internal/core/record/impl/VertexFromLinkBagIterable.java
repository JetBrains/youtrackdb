package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Iterable that wraps a LinkBag and yields vertices directly from the secondary RIDs
 * stored in each RidPair, without loading intermediate edge records. This provides an
 * optimized path for {@code getVertices()} when the underlying storage is a LinkBag
 * with double-sided entries.
 *
 * <p>Use {@link #withClassFilter(IntSet)} to create a filtered copy that skips
 * vertices whose collection ID is not in the accepted set — this avoids loading
 * records from storage entirely (only the in-memory RID is inspected).
 */
public class VertexFromLinkBagIterable
    implements PreFilterableLinkBagIterable, Iterable<Vertex> {

  @Nonnull
  private final LinkBag linkBag;
  @Nonnull
  private final DatabaseSessionEmbedded session;

  /**
   * When non-null, only vertices whose collection ID is in this set are loaded.
   * Passed through to {@link VertexFromLinkBagIterator}.
   */
  @Nullable private final IntSet acceptedCollectionIds;

  @Nullable private final Set<RID> acceptedRids;

  public VertexFromLinkBagIterable(
      @Nonnull LinkBag linkBag,
      @Nonnull DatabaseSessionEmbedded session) {
    this(linkBag, session, null, null);
  }

  private VertexFromLinkBagIterable(
      @Nonnull LinkBag linkBag,
      @Nonnull DatabaseSessionEmbedded session,
      @Nullable IntSet acceptedCollectionIds,
      @Nullable Set<RID> acceptedRids) {
    this.linkBag = linkBag;
    this.session = session;
    this.acceptedCollectionIds = acceptedCollectionIds;
    this.acceptedRids = acceptedRids;
  }

  /**
   * Returns a new iterable that only yields vertices whose collection (cluster)
   * ID is in the given set. Vertices with non-matching collection IDs are
   * skipped without any disk I/O.
   *
   * @param collectionIds accepted collection IDs (typically from
   *     {@link VertexFromLinkBagIterator#collectionIdsForClass})
   */
  @Nonnull
  @Override
  public VertexFromLinkBagIterable withClassFilter(@Nonnull IntSet collectionIds) {
    return new VertexFromLinkBagIterable(linkBag, session, collectionIds, acceptedRids);
  }

  /**
   * Returns a new iterable that only yields vertices whose RID is in the given set.
   * Vertices not in the set are skipped without any disk I/O.
   *
   * @param ridSet accepted RIDs (typically built from an index query)
   */
  @Nonnull
  @Override
  public VertexFromLinkBagIterable withRidFilter(@Nonnull Set<RID> ridSet) {
    return new VertexFromLinkBagIterable(linkBag, session, acceptedCollectionIds, ridSet);
  }

  @Nonnull
  @Override
  public Iterator<Vertex> iterator() {
    return new VertexFromLinkBagIterator(
        linkBag.iterator(), session, linkBag.size(), acceptedCollectionIds, acceptedRids);
  }

  /**
   * Returns an iterator that yields {@link ResultInternal} objects wrapping
   * bare RIDs from the LinkBag's secondary RIDs, without calling
   * {@code loadEntity()}. Class filter and RID filter are applied on the
   * RID (no disk I/O) — only matching RIDs produce Results.
   *
   * <p>Used by the MATCH engine: the caller plugs this directly into
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ResultIteratorExecutionStream}
   * to skip the per-row {@code ctx.getDatabaseSession()} + {@code toResult(...)}
   * dispatch that a generic {@code IteratorExecutionStream} would perform.
   * Entity loading is deferred to first property access via
   * {@code ResultInternal}'s built-in lazy loading.
   */
  @Nonnull
  public Iterator<ResultInternal> ridIterator() {
    return new RidOnlyIterator(
        linkBag.iterator(), session, acceptedCollectionIds, acceptedRids);
  }

  @Override
  public int size() {
    return linkBag.size();
  }

  @Override
  public boolean isSizeable() {
    return linkBag.isSizeable();
  }

  /**
   * Iterator that yields {@link ResultInternal} objects wrapping RIDs from
   * LinkBag secondary RIDs, applying class and RID filters without loading
   * entities from storage. The session is captured once at construction so
   * each yielded Result can be consumed directly by a
   * {@code ResultIteratorExecutionStream} — eliminating the per-row
   * {@code ctx.getDatabaseSession()} + {@code toResult(...)} dispatch that a
   * generic {@code IteratorExecutionStream} would incur.
   */
  private static final class RidOnlyIterator implements Iterator<ResultInternal> {

    private final Iterator<RidPair> ridPairIterator;
    @Nonnull
    private final DatabaseSessionEmbedded session;
    @Nullable private final IntSet acceptedCollectionIds;
    @Nullable private final Set<RID> acceptedRids;
    @Nullable private RID nextRid;

    RidOnlyIterator(
        Iterator<RidPair> ridPairIterator,
        @Nonnull DatabaseSessionEmbedded session,
        @Nullable IntSet acceptedCollectionIds,
        @Nullable Set<RID> acceptedRids) {
      this.ridPairIterator = ridPairIterator;
      this.session = session;
      this.acceptedCollectionIds = acceptedCollectionIds;
      this.acceptedRids = acceptedRids;
    }

    @Override
    public boolean hasNext() {
      while (nextRid == null && ridPairIterator.hasNext()) {
        nextRid = filterRid(ridPairIterator.next());
      }
      return nextRid != null;
    }

    @Override
    public ResultInternal next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      var current = nextRid;
      nextRid = null;
      return new ResultInternal(session, current);
    }

    @Nullable private RID filterRid(RidPair ridPair) {
      ridPair.validateEdgePair();
      var rid = ridPair.secondaryRid();

      if (acceptedCollectionIds != null
          && !acceptedCollectionIds.contains(rid.getCollectionId())) {
        return null;
      }
      if (acceptedRids != null && !acceptedRids.contains(rid)) {
        return null;
      }
      return rid;
    }
  }
}
