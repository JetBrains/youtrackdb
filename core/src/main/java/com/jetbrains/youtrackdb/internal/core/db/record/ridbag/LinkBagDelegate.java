package com.jetbrains.youtrackdb.internal.core.db.record.ridbag;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.Change;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Collection;
import java.util.Spliterator;
import java.util.stream.Stream;

public interface LinkBagDelegate
    extends Iterable<RidPair>,
    Sizeable,
    TrackedMultiValue<RID, RID>,
    RecordElement {

  void addAll(Collection<RID> values);

  boolean add(RID rid);

  boolean add(RID primaryRid, RID secondaryRid);

  boolean remove(RID rid);

  boolean isEmpty();

  @Override
  default boolean isEmbeddedContainer() {
    return false;
  }

  void requestDelete(FrontendTransaction transaction);

  /**
   * THIS IS VERY EXPENSIVE METHOD AND CAN NOT BE CALLED IN REMOTE STORAGE.
   *
   * @param identifiable Object to check.
   * @return true if ridbag contains at leas one instance with the same rid as passed in
   * identifiable.
   */
  boolean contains(RID identifiable);

  @Override
  void setOwner(RecordElement owner);

  @Override
  RecordElement getOwner();

  @Override
  String toString();

  Stream<RawPair<RID, Change>> getChanges();

  SimpleMultiValueTracker<RID, RID> getTracker();

  void setTracker(SimpleMultiValueTracker<RID, RID> tracker);

  void setTransactionModified(boolean transactionModified);

  Stream<RidPair> stream();

  @Override
  Spliterator<RidPair> spliterator();
}
