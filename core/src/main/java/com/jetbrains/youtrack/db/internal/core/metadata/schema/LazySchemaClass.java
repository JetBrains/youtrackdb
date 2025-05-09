package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.transaction.Transaction;
import com.jetbrains.youtrack.db.api.transaction.TxConsumer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

// todo potentially implement SchemaClass interface to make it interchangeable
// this will require a lot of boilerplate code to be generated to delegate all the cals to delegate
// making sure lazy class is loaded before hand.
// I don't have the will power to do it now, but I see points where it will be beneficial.
public class LazySchemaClass {

  private final RID recordId;
  private boolean classLoaded = false;
  private boolean inheritanceLoaded = false;
  private SchemaClassImpl delegate;

  private LazySchemaClass(RID recordId, SchemaClassImpl delegate) {
    this.recordId = recordId;
    this.delegate = delegate;
  }

  public static LazySchemaClass fromTemplate(RID identity, SchemaClassImpl cls, boolean isNew) {
    var lazySchemaClass = new LazySchemaClass(identity, cls);
    if (isNew) {
      // lazy class just created from the template should be considered loaded
      // since there is no information about it in the storage, thus all the information we have
      // in the template is the most accurate
      lazySchemaClass.classLoaded = true;
      lazySchemaClass.inheritanceLoaded = true;
    }
    return lazySchemaClass;
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

  public boolean isFullyLoaded() {
    return isLoadedWithoutInheritance() && inheritanceLoaded;
  }

  private void load(DatabaseSessionInternal session, SchemaClassImpl delegateTemplate) {
    if (delegate == null) {
      delegate = delegateTemplate;
    }
    TxConsumer<Transaction, RuntimeException> loadClassTransaction = tx -> {
      EntityImpl classEntity = session.load(recordId);
      // we need to load class without inheritance first, and then we can load inheritance
      // this is needed because unloaded class will be overwritten by loading its superclass
      delegate.fromStream(session, classEntity, false);
      classLoaded = true;

      delegate.loadInheritanceTree(session, classEntity);
      inheritanceLoaded = true;
    };
    if (session.isTxActive()) {
      loadClassTransaction.accept(session.getTransactionInternal());
    } else {
      session.executeInTx(loadClassTransaction);
    }
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
    session.executeInTx(tx -> {
      Entity classEntity = session.load(recordId);
      delegate.fromStream(session, classEntity, false);
      classLoaded = true;
    });
  }

  public RID getId() {
    return recordId;
  }

  public void unload() {
    this.classLoaded = false;
    this.inheritanceLoaded = false;

    this.delegate.subclasses.clear();
    this.delegate.superClasses.clear();
  }

  public SchemaClassImpl getDelegate() {
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
    return delegate.getName(session);
  }
}
