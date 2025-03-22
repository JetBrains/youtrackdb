package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedList;
import java.util.Collection;
import javax.annotation.Nonnull;

public class EmbeddedListImpl<T> extends TrackedList<T> implements EmbeddedList<T> {

  public EmbeddedListImpl(@Nonnull RecordElement iRecord,
      Collection<? extends T> iOrigin, Class<?> iGenericClass) {
    super(iRecord, iOrigin, iGenericClass);
  }

  public EmbeddedListImpl(@Nonnull RecordElement iSourceRecord) {
    super(iSourceRecord);
  }

  public EmbeddedListImpl(@Nonnull RecordElement iSourceRecord, int size) {
    super(iSourceRecord, size);
  }

  public EmbeddedListImpl() {
  }

  public EmbeddedListImpl(int size) {
    super(size);
  }

  public EmbeddedListImpl(boolean linkCollectionsProhibited, boolean resultAllowed) {
    super(linkCollectionsProhibited, resultAllowed);
  }

  @Override
  public boolean isEmbeddedContainer() {
    return true;
  }
}
