package com.jetbrains.youtrack.db.internal.core.db.record;

public interface StorageBackedMultiValue {

  void setOwnerFieldName(String fieldName);

  void setOwner(RecordElement newOwner);
}
