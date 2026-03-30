package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
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

  @Override
  public int size() {
    return linkBag.size();
  }

  @Override
  public boolean isSizeable() {
    return linkBag.isSizeable();
  }
}
