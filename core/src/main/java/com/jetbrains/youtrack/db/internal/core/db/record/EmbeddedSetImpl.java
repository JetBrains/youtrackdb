package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedSet;

public class EmbeddedSetImpl<T> extends TrackedSet<T> implements EmbeddedSet<T> {

  public EmbeddedSetImpl(RecordElement iSourceRecord) {
    super(iSourceRecord);
  }

  public EmbeddedSetImpl(RecordElement iSourceRecord, int size) {
    super(iSourceRecord, size);
  }

  public EmbeddedSetImpl() {
  }

  public EmbeddedSetImpl(int size) {
    super(size);
  }

  @Override
  public boolean isEmbeddedContainer() {
    return true;
  }
}
