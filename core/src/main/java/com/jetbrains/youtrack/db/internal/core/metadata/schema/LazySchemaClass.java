package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

public class LazySchemaClass {

  private final RecordId recordId;
  private SchemaClassInternal delegate;

  public LazySchemaClass(RecordId recordId) {
    this.recordId = recordId;
  }

  public LazySchemaClass(RecordId recordId, SchemaClassInternal delegate) {
    this.recordId = recordId;
    this.delegate = delegate;
  }

  public boolean isLoaded() {
    return delegate != null;
  }

  public void load(DatabaseSessionInternal db, SchemaClassImpl delegateTemplate) {
    // todo do we need sync here?
    EntityImpl classEntity = db.load(recordId);
    delegateTemplate.fromStream(db, classEntity);
    delegate = delegateTemplate;
  }

  public RecordId getId() {
    return recordId;
  }

  public SchemaClassInternal getDelegate() {
    return delegate;
  }
}
