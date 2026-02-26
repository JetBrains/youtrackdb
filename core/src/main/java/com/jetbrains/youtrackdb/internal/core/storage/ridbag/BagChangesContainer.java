package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Collection;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface BagChangesContainer extends Iterable<RawPair<RID, Change>> {
  @Nullable
  Change getChange(RID rid);

  void putChange(RID rid, Change change);

  void fillAllSorted(Collection<? extends RawPair<RID, Change>> changes);

  int size();

  @Nonnull
  @Override
  Spliterator<RawPair<RID, Change>> spliterator();

  @Nonnull
  Spliterator<RawPair<RID, Change>> spliterator(RID after);

  @Nonnull
  default Stream<RawPair<RID, Change>> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  @Nonnull
  @Override
  default Iterator<RawPair<RID, Change>> iterator() {
    return stream().iterator();
  }

  void clear();

  boolean isEmpty();
}
