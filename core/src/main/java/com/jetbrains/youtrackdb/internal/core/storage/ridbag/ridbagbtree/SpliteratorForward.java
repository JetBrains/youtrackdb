package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;
import javax.annotation.Nullable;

public final class SpliteratorForward implements Spliterator<RawPair<EdgeKey, LinkBagValue>> {

  private final SharedLinkBagBTree bTree;

  private final EdgeKey fromKey;
  private final EdgeKey toKey;
  private final boolean fromKeyInclusive;
  private final boolean toKeyInclusive;

  private final List<RawPair<EdgeKey, LinkBagValue>> batch = new ArrayList<>();
  private EdgeKey lastKey;
  private int nextEntry;
  private boolean exhausted;

  private final AtomicOperation atomicOperation;

  SpliteratorForward(
      SharedLinkBagBTree bTree,
      final EdgeKey fromKey,
      final EdgeKey toKey,
      final boolean fromKeyInclusive,
      final boolean toKeyInclusive, AtomicOperation atomicOperation) {
    this.bTree = bTree;
    this.fromKey = fromKey;
    this.toKey = toKey;

    this.toKeyInclusive = toKeyInclusive;
    this.fromKeyInclusive = fromKeyInclusive;
    this.atomicOperation = atomicOperation;
  }

  @Override
  public boolean tryAdvance(Consumer<? super RawPair<EdgeKey, LinkBagValue>> action) {
    if (exhausted) {
      return false;
    }
    if (nextEntry == batch.size()) {
      // The previous batch is fully consumed. Keep its continuation while reusing its storage.
      batch.clear();
      nextEntry = 0;
      try {
        bTree.readForwardCursorBatch(fromKey, fromKeyInclusive, toKey, toKeyInclusive,
            lastKey, atomicOperation, batch);
      } catch (RuntimeException | Error failure) {
        batch.clear();
        throw failure;
      }

      // Publish the continuation and exhaustion only after a successful read.
      lastKey = batch.isEmpty() ? null : batch.getLast().first();
      exhausted = batch.isEmpty();
      if (exhausted) {
        return false;
      }
    }
    action.accept(batch.get(nextEntry++));
    return true;
  }

  @Nullable @Override
  public Spliterator<RawPair<EdgeKey, LinkBagValue>> trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public int characteristics() {
    return SORTED | NONNULL | ORDERED;
  }

  @Override
  public Comparator<? super RawPair<EdgeKey, LinkBagValue>> getComparator() {
    return Comparator.comparing(RawPair::first);
  }
}
