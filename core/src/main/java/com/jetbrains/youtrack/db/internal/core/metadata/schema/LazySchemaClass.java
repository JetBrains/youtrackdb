package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

// todo potentially implement SchemaClass interface to make it interchangeable
// this will require a lot of boilerplate code to be generated to delegate all the cals to delegate
// making sure lazy class is loaded before hand.
// I don't have the will power to do it now, but I see points where it will be beneficial.
public class LazySchemaClass {

  private final RecordId recordId;
  private boolean classLoaded = false;
  private boolean inheritanceLoaded = false;
  private SchemaClassImpl delegate;

  private LazySchemaClass(RecordId recordId) {
    this.recordId = recordId;
  }

  private LazySchemaClass(RecordId recordId, SchemaClassImpl delegate) {
    this.recordId = recordId;
    this.delegate = delegate;
  }

  public static LazySchemaClass fromId(RecordId value) {
    return new LazySchemaClass(value);
  }

  public static LazySchemaClass fromTemplate(RecordId identity, SchemaClassImpl cls) {
    return new LazySchemaClass(identity, cls);
  }

  public void loadIfNeededWithTemplate(DatabaseSessionInternal session, SchemaClassImpl delegate) {
    if (!this.isFullyLoaded()) {
      this.load(session, delegate);
    }
  }

  public void loadIfNeeded(DatabaseSessionInternal session) {
    if (!this.isFullyLoaded()) {
      if (delegate == null) {
        throw new IllegalStateException(
            "LazySchemaClass does not have a delegate, please investigate");
      }
      this.load(session, delegate);
    }
  }

  private boolean isFullyLoaded() {
    return isLoadedWithoutInheritance() && inheritanceLoaded;
  }

  private void load(DatabaseSessionInternal session, SchemaClassImpl delegateTemplate) {
    if (delegate == null) {
      delegate = delegateTemplate;
    }
    // todo fix later.
    // when I do it this way it builds schemaSnapshot while loading schema which leads to use of non initialized classes
    session.executeInTx(() -> {
//      // todo figure out why loaded = true first works fine and loaded = true last gives stack overflow
//      // my first assumption is class relyes on itself, so when we do from streamStream we already need some places
//      // where we want to use this class, but it seems like class could be partially initialized which could lead to
//      // more issues.
////      loaded = true;
      EntityImpl classEntity = session.load(recordId);
      delegate.fromStream(session, classEntity);
      classLoaded = true;
      inheritanceLoaded = true;
    });
  }

  public void loadWithoutInheritanceIfNeeded(DatabaseSessionInternal session) {
    if (!this.isLoadedWithoutInheritance()) {
      if (delegate == null) {
        throw new IllegalStateException(
            "LazySchemaClass does not have a delegate, please investigate");
      }
      this.loadWithoutInheritance(session, delegate);
    }
  }

  public boolean isLoadedWithoutInheritance() {
    return delegate != null && classLoaded;
  }

  private void loadWithoutInheritance(DatabaseSessionInternal session,
      SchemaClassImpl delegateTemplate) {
    if (delegate == null) {
      delegate = delegateTemplate;
    }
    session.executeInTx(() -> {
      EntityImpl classEntity = session.load(recordId);
      delegate.fromStream(session, classEntity, false);
      classLoaded = true;
    });
  }

  public RecordId getId() {
    return recordId;
  }

  public void unload() {
    this.classLoaded = false;
    this.inheritanceLoaded = false;
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

  public String getName(DatabaseSessionInternal session) {
    loadWithoutInheritanceIfNeeded(session);
    return delegate.getName();
  }
}
