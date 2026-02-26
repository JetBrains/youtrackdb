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

public interface BagChangesContainer extends Iterable<RawPair<RID, AbsoluteChange>> {
  @Nullable
  AbsoluteChange getChange(RID rid);

  void putChange(RID rid, AbsoluteChange change);

  void fillAllSorted(Collection<? extends RawPair<RID, AbsoluteChange>> changes);

  int size();

  @Nonnull
  @Override
  Spliterator<RawPair<RID, AbsoluteChange>> spliterator();

  @Nonnull
  Spliterator<RawPair<RID, AbsoluteChange>> spliterator(RID after);

  @Nonnull
  default Stream<RawPair<RID, AbsoluteChange>> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  @Nonnull
  @Override
  default Iterator<RawPair<RID, AbsoluteChange>> iterator() {
    return stream().iterator();
  }

  void clear();

  boolean isEmpty();
}
