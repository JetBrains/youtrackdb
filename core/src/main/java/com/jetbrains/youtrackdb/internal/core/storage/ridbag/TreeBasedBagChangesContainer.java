package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Collection;
import java.util.Map;
import java.util.Spliterator;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TreeBasedBagChangesContainer implements BagChangesContainer {

  private final TreeMap<RID, Change> changes = new TreeMap<>();

  @Nullable
  @Override
  public Change getChange(RID rid) {
    return changes.get(rid);
  }

  @Override
  public void putChange(RID rid, Change change) {
    changes.put(rid, change);
  }

  @Override
  public void fillAllSorted(Collection<? extends RawPair<RID, Change>> changes) {
    for (var change : changes) {
      this.changes.put(change.first(), change.second());
    }
  }

  @Override
  public int size() {
    return changes.size();
  }

  @Nonnull
  @Override
  public Spliterator<RawPair<RID, Change>> spliterator() {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public Spliterator<RawPair<RID, Change>> spliterator(RID after) {
    return new TransformingSpliterator(changes.entrySet().spliterator());
  }

  @Override
  public void clear() {
    changes.clear();
  }

  @Override
  public boolean isEmpty() {
    return changes.isEmpty();
  }

  private static final class TransformingSpliterator implements Spliterator<RawPair<RID, Change>> {

    private final Spliterator<Map.Entry<RID, Change>> spliterator;

    private TransformingSpliterator(Spliterator<Map.Entry<RID, Change>> spliterator) {
      this.spliterator = spliterator;
    }

    @Override
    public boolean tryAdvance(Consumer<? super RawPair<RID, Change>> action) {
      return spliterator.tryAdvance(
          entry -> action.accept(new RawPair<>(entry.getKey(), entry.getValue())));
    }

    @Override
    @Nullable
    public Spliterator<RawPair<RID, Change>> trySplit() {
      var split = spliterator.trySplit();
      return split != null ? new TransformingSpliterator(split) : null;
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return spliterator.characteristics();
    }
  }
}
