package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

public class LazySchemaClass {

  private final RecordId recordId;
  private SchemaClassImpl delegate;

  public LazySchemaClass(RecordId recordId) {
    this.recordId = recordId;
  }

  public LazySchemaClass(RecordId recordId, SchemaClassImpl delegate) {
    this.recordId = recordId;
    this.delegate = delegate;
  }

  public boolean isLoaded() {
    return delegate != null;
  }

  public void load(DatabaseSessionInternal db, SchemaClassImpl delegateTemplate) {
    if (delegate == null) {
      delegate = delegateTemplate;
    }
    // todo do we need sync here?
    db.begin();
    EntityImpl classEntity = db.load(recordId);
    delegate.fromStream(db, classEntity);
    db.commit();
  }

  public RecordId getId() {
    return recordId;
  }

  public SchemaClassInternal getDelegate() {
    return delegate;
  }

  @Override
  public String toString() {
    return "LazySchemaClass{" +
        "recordId=" + recordId +
        ", delegate=" + delegate +
        '}';
  }
}
