package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Proxy over a {@link SchemaClassImpl}. Reads route through {@link #resolve()} and writes through
 * {@link #resolveForWrite()} (see {@link SchemaProxedResource}), so during a schema transaction the
 * proxy operates on the transaction's tx-local class object rather than the committed shared one,
 * and impl-typed arguments (superclasses, linked classes) are re-resolved by name into the tx-local
 * copy before they are linked.
 */
public final class SchemaClassProxy extends SchemaProxedResource<SchemaClassImpl> implements
    SchemaClassInternal {

  private int hashCode = 0;

  public SchemaClassProxy(SchemaClassImpl delegate,
      @Nonnull DatabaseSessionEmbedded session) {
    super(delegate, session);
  }

  @Override
  protected SchemaClassImpl rebindToTxLocal(@Nonnull SchemaShared txLocalSchema) {
    if (delegate.getOwner() == txLocalSchema) {
      // Already a tx-local object (e.g. a class created earlier in the same transaction).
      return delegate;
    }
    var resolved = txLocalSchema.getClass(delegate.getName());
    if (resolved == null) {
      throw new IllegalStateException(
          "Class '" + delegate.getName() + "' is not present in the transaction-local schema view;"
              + " it may have been dropped earlier in this transaction");
    }
    return resolved;
  }

  @Override
  protected void recordWriteTarget(@Nonnull TxSchemaState txState,
      @Nonnull SchemaClassImpl resolved) {
    // A class-level write mutates this class's serialized per-class record, so the commit must
    // rewrite it: record the class under its tx-local name. markClassChanged is idempotent, so the
    // mutators that already record (create / drop / rename / abstract->concrete alter) are harmless
    // duplicates here.
    txState.markClassChanged(resolved.getName());
  }

  @Override
  public CollectionSelectionStrategy getCollectionSelection() {
    assert this.session.assertIfNotActive();
    return resolve().getCollectionSelection();
  }

  @Override
  public int getCollectionForNewInstance(EntityImpl entity) {
    assert this.session.assertIfNotActive();
    var cls = resolve();
    return cls.getCollectionSelection().getCollection(this.session, this, entity);
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionEmbedded session, String... fields) {
    assert this.session.assertIfNotActive();
    return resolve().getInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return resolve().getInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyTypeInternal iType, PropertyTypeInternal iLinkedType, boolean unsafe) {
    assert this.session.assertIfNotActive();
    return new SchemaPropertyProxy(
        resolveForWrite().createProperty(session, iPropertyName, iType, iLinkedType, unsafe),
        session);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyTypeInternal iType, SchemaClass iLinkedClass, boolean unsafe) {
    assert this.session.assertIfNotActive();
    var cls = resolveForWrite();
    var linkedImpl = iLinkedClass != null
        ? reresolveClassImpl(cls.getOwner(),
            ((SchemaClassInternal) iLinkedClass).getImplementation())
        : null;
    return new SchemaPropertyProxy(
        cls.createProperty(session, iPropertyName, iType, linkedImpl, unsafe), session);
  }

  @Override
  public Set<Index> getIndexesInternal() {
    assert this.session.assertIfNotActive();
    return resolve().getIndexesInternal(session);
  }

  @Override
  public void getIndexesInternal(DatabaseSessionEmbedded session, Collection<Index> indices) {
    assert this.session.assertIfNotActive();
    resolve().getIndexesInternal(this.session, indices);
  }

  @Override
  public long count(DatabaseSessionEmbedded session) {
    assert this.session.assertIfNotActive();
    return resolve().count(this.session);
  }

  @Override
  public void truncate() {
    assert this.session.assertIfNotActive();
    // truncate empties a class's records and changes no schema, so it routes through the read
    // resolver: it must not mark the class changed or seed a tx-local schema state, which would
    // route a truncate-only transaction onto the heavier schema-carry commit path. The record
    // deletions it performs already make the transaction a write transaction on their own.
    resolve().truncate(session);
  }

  @Override
  public long count(DatabaseSessionEmbedded session, boolean isPolymorphic) {
    assert this.session.assertIfNotActive();
    return resolve().count(this.session, isPolymorphic);
  }

  @Override
  public long approximateCount(DatabaseSessionEmbedded session) {
    assert this.session.assertIfNotActive();
    return resolve().approximateCount(this.session);
  }

  @Override
  public long approximateCount(DatabaseSessionEmbedded session, boolean isPolymorphic) {
    assert this.session.assertIfNotActive();
    return resolve().approximateCount(this.session, isPolymorphic);
  }

  @Nullable @Override
  public SchemaPropertyInternal getPropertyInternal(String propertyName) {
    assert this.session.assertIfNotActive();
    var result = resolve().getPropertyInternal(propertyName);
    return result != null ? new SchemaPropertyProxy(result, session) : null;
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      String... fields) {
    assert this.session.assertIfNotActive();
    return resolve().getClassInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return resolve().getClassInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public Set<Index> getClassIndexesInternal() {
    assert this.session.assertIfNotActive();
    return resolve().getClassIndexesInternal(session);
  }

  @Override
  public Index getClassIndex(DatabaseSessionEmbedded session, String name) {
    assert this.session.assertIfNotActive();
    return resolve().getClassIndex(this.session, name);
  }

  @Override
  public SchemaClass set(ATTRIBUTES attribute,
      Object value) {
    assert session.assertIfNotActive();
    resolveForWrite().set(session, attribute, value);
    return this;
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSessionEmbedded session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return resolve().getInvolvedIndexes(this.session, fields);
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSessionEmbedded session, String... fields) {
    assert this.session.assertIfNotActive();
    return resolve().getInvolvedIndexes(this.session, fields);
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSessionEmbedded session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return resolve().getClassInvolvedIndexes(this.session, fields);
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSessionEmbedded session, String... fields) {
    assert this.session.assertIfNotActive();
    return resolve().getClassInvolvedIndexes(this.session, fields);
  }

  @Override
  public boolean areIndexed(DatabaseSessionEmbedded session, Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return resolve().areIndexed(this.session, fields);
  }

  @Override
  public boolean areIndexed(DatabaseSessionEmbedded session, String... fields) {
    assert this.session.assertIfNotActive();
    return resolve().areIndexed(this.session, fields);
  }

  @Override
  public Set<String> getClassIndexes() {
    assert session.assertIfNotActive();
    return resolve().getClassIndexes(session);
  }

  @Override
  public Set<String> getIndexes() {
    assert session.assertIfNotActive();
    return resolve().getIndexes(session);
  }

  @Override
  public SchemaClassImpl getImplementation() {
    // Unwrap primitive: returns the captured delegate. Write methods that link an impl re-resolve
    // it by name into the tx-local copy (reresolveClassImpl), so handing back the captured object
    // here cannot leak shared state into the private graph.
    return delegate;
  }

  @Override
  public boolean isAbstract() {
    assert session.assertIfNotActive();
    return resolve().isAbstract();
  }

  @Override
  public SchemaClass setAbstract(boolean iAbstract) {
    assert session.assertIfNotActive();
    resolveForWrite().setAbstract(session, iAbstract);
    return this;
  }

  @Override
  public boolean isStrictMode() {
    assert session.assertIfNotActive();
    return resolve().isStrictMode();
  }

  @Override
  public void setStrictMode(boolean iMode) {
    assert session.assertIfNotActive();
    resolveForWrite().setStrictMode(session, iMode);
  }

  @Override
  public boolean hasSuperClasses() {
    assert session.assertIfNotActive();
    return resolve().hasSuperClasses();
  }

  @Override
  public List<String> getSuperClassesNames() {
    assert session.assertIfNotActive();
    return resolve().getSuperClassesNames();
  }

  @Override
  public List<SchemaClass> getSuperClasses() {
    assert session.assertIfNotActive();
    var result = resolve().getSuperClasses();
    var resultProxy = new ArrayList<SchemaClass>(result.size());

    for (var schemaClass : result) {
      resultProxy.add(
          new SchemaClassProxy(schemaClass, session));
    }

    return resultProxy;
  }

  @Override
  public SchemaClass setSuperClasses(List<? extends SchemaClass> classes) {
    assert session.assertIfNotActive();

    var cls = resolveForWrite();
    var owner = cls.getOwner();
    var classesImpl = new ArrayList<SchemaClassImpl>(classes.size());
    for (var schemaClass : classes) {
      classesImpl.add(
          reresolveClassImpl(owner, ((SchemaClassInternal) schemaClass).getImplementation()));
    }
    cls.setSuperClasses(session, classesImpl);

    return this;
  }

  @Override
  public SchemaClass addSuperClass(SchemaClass superClass) {
    assert session.assertIfNotActive();
    var cls = resolveForWrite();
    cls.addSuperClass(session,
        reresolveClassImpl(cls.getOwner(), ((SchemaClassInternal) superClass).getImplementation()));
    return this;
  }

  @Override
  public void removeSuperClass(SchemaClass superClass) {
    assert session.assertIfNotActive();
    var cls = resolveForWrite();
    cls.removeSuperClass(this.session,
        reresolveClassImpl(cls.getOwner(), ((SchemaClassInternal) superClass).getImplementation()));
  }

  @Override
  public String getName() {
    assert this.session.assertIfNotActive();
    return resolve().getName();
  }

  @Override
  public SchemaClass setName(String iName) {
    assert this.session.assertIfNotActive();
    resolveForWrite().setName(this.session, iName);
    hashCode = 0;
    return this;
  }

  @Override
  public String getDescription() {
    assert this.session.assertIfNotActive();
    return resolve().getDescription();
  }

  @Override
  public SchemaClass setDescription(String iDescription) {
    assert this.session.assertIfNotActive();
    resolveForWrite().setDescription(this.session, iDescription);
    return this;
  }

  @Override
  public String getStreamableName() {
    assert this.session.assertIfNotActive();
    return resolve().getStreamableName();
  }

  @Override
  public Collection<SchemaProperty> getDeclaredProperties() {
    assert this.session.assertIfNotActive();
    var result = resolve().declaredProperties();

    var resultProxy = new ArrayList<SchemaProperty>(result.size());
    for (var schemaProperty : result) {
      resultProxy.add(new SchemaPropertyProxy(schemaProperty, this.session));
    }

    return resultProxy;
  }

  @Override
  public Collection<SchemaProperty> getProperties() {
    assert this.session.assertIfNotActive();
    var result = resolve().properties();

    var resultProxy = new ArrayList<SchemaProperty>(result.size());
    for (var schemaProperty : result) {
      resultProxy.add(new SchemaPropertyProxy(schemaProperty, this.session));
    }

    return resultProxy;
  }

  @Override
  public Map<String, SchemaProperty> getPropertiesMap() {
    assert session.assertIfNotActive();
    var result = resolve().propertiesMap(session);

    var resultProxy = new HashMap<String, SchemaProperty>(result.size());
    for (var entry : result.entrySet()) {
      resultProxy.put(entry.getKey(), new SchemaPropertyProxy(entry.getValue(), session));
    }

    return resultProxy;
  }

  @Nullable @Override
  public SchemaProperty getProperty(String iPropertyName) {
    assert session.assertIfNotActive();
    var result = resolve().getProperty(iPropertyName);
    return result != null ? new SchemaPropertyProxy(result, session) : null;
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType) {
    assert session.assertIfNotActive();
    var result = resolveForWrite().createProperty(session, iPropertyName, iType);
    return new SchemaPropertyProxy(result, session);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType, SchemaClass iLinkedClass) {
    assert session.assertIfNotActive();

    var cls = resolveForWrite();
    var linkedImpl = iLinkedClass != null
        ? reresolveClassImpl(cls.getOwner(),
            ((SchemaClassInternal) iLinkedClass).getImplementation())
        : null;
    var result = cls.createProperty(session, iPropertyName, iType, linkedImpl);
    return new SchemaPropertyProxy(result, session);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType, PropertyType iLinkedType) {
    assert session.assertIfNotActive();
    var result = resolveForWrite().createProperty(session, iPropertyName, iType, iLinkedType);
    return new SchemaPropertyProxy(result, session);
  }

  @Override
  public void dropProperty(String iPropertyName) {
    assert session.assertIfNotActive();
    resolveForWrite().dropProperty(session, iPropertyName);
  }

  @Override
  public boolean existsProperty(String iPropertyName) {
    assert session.assertIfNotActive();
    return resolve().existsProperty(iPropertyName);
  }

  @Override
  public int[] getCollectionIds() {
    assert session.assertIfNotActive();
    return resolve().getCollectionIds();
  }

  @Override
  public int[] getPolymorphicCollectionIds() {
    assert session.assertIfNotActive();
    return resolve().getPolymorphicCollectionIds();
  }

  @Override
  public Collection<SchemaClass> getSubclasses() {
    assert session.assertIfNotActive();
    var result = resolve().getSubclasses();
    var resultProxy = new ArrayList<SchemaClass>(result.size());

    for (var schemaClass : result) {
      resultProxy.add(new SchemaClassProxy(schemaClass, session));
    }

    return resultProxy;
  }

  @Override
  public Collection<SchemaClass> getAllSubclasses() {
    assert session.assertIfNotActive();
    var result = resolve().getAllSubclasses();
    var resultProxy = new ArrayList<SchemaClass>(result.size());

    for (var schemaClass : result) {
      resultProxy.add(new SchemaClassProxy(schemaClass, session));
    }

    return resultProxy;

  }

  @Override
  public Collection<SchemaClass> getAllSuperClasses() {
    assert session.assertIfNotActive();
    var result = resolve().getAllSuperClasses();
    var resultProxy = new ArrayList<SchemaClass>(result.size());

    for (var schemaClass : result) {
      resultProxy.add(new SchemaClassProxy(schemaClass, session));
    }

    return resultProxy;
  }

  @Override
  public boolean isSubClassOf(String iClassName) {
    assert session.assertIfNotActive();
    return resolve().isSubClassOf(iClassName);
  }

  @Override
  public boolean isSubClassOf(SchemaClass iClass) {
    assert session.assertIfNotActive();
    var cls = resolve();
    // Read predicate: tolerate an argument class absent from the resolved (tx-local or committed)
    // schema by re-resolving it through the read-tolerant helper, which yields null for an absent
    // class; isSubClassOf(null) answers false, preserving the historical total-read contract.
    return cls.isSubClassOf(
        reresolveClassImplForRead(
            cls.getOwner(), ((SchemaClassInternal) iClass).getImplementation()));
  }

  @Override
  public boolean isSuperClassOf(SchemaClass iClass) {
    assert session.assertIfNotActive();
    var cls = resolve();
    // Read predicate: an absent argument class re-resolves to null and isSuperClassOf(null) answers
    // false, so an unrelated or dropped argument never raises rather than returning false.
    return cls.isSuperClassOf(
        reresolveClassImplForRead(
            cls.getOwner(), ((SchemaClassInternal) iClass).getImplementation()));
  }

  @Override
  public void createIndex(String iName, INDEX_TYPE iType,
      String... fields) {
    assert session.assertIfNotActive();
    resolveForWrite().createIndex(session, iName, iType, fields);
  }

  @Override
  public void createIndex(String iName, String iType, String... fields) {
    assert session.assertIfNotActive();
    resolveForWrite().createIndex(session, iName, iType, fields);
  }

  @Override
  public void createIndex(String iName, INDEX_TYPE iType,
      ProgressListener iProgressListener, String... fields) {
    assert session.assertIfNotActive();
    resolveForWrite().createIndex(session, iName, iType, iProgressListener, fields);
  }

  @Override
  public void createIndex(String iName, String iType,
      ProgressListener iProgressListener, Map<String, Object> metadata, String algorithm,
      String... fields) {
    assert session.assertIfNotActive();
    resolveForWrite().createIndex(session, iName, iType, iProgressListener, metadata, algorithm,
        fields);
  }

  @Override
  public void createIndex(String iName, String iType,
      ProgressListener iProgressListener, Map<String, Object> metadata, String... fields) {
    assert session.assertIfNotActive();
    resolveForWrite().createIndex(session, iName, iType, iProgressListener, metadata, fields);
  }

  @Override
  public boolean isEdgeType() {
    assert session.assertIfNotActive();
    return resolve().isEdgeType();
  }

  @Override
  public boolean isVertexType() {
    assert session.assertIfNotActive();
    return resolve().isVertexType();
  }

  @Override
  public String getCustom(String iName) {
    assert session.assertIfNotActive();
    return resolve().getCustom(iName);
  }

  @Override
  public SchemaClass setCustom(String iName, String iValue) {
    assert session.assertIfNotActive();
    resolveForWrite().setCustom(session, iName, iValue);
    return this;
  }

  @Override
  public void removeCustom(String iName) {
    assert session.assertIfNotActive();
    resolveForWrite().removeCustom(session, iName);
  }

  @Override
  public void clearCustom() {
    assert session.assertIfNotActive();
    resolveForWrite().clearCustom(session);
  }

  @Override
  public Set<String> getCustomKeys() {
    assert session.assertIfNotActive();
    return resolve().getCustomKeys();
  }

  @Override
  public boolean hasCollectionId(int collectionId) {
    assert session.assertIfNotActive();
    return resolve().hasCollectionId(collectionId);
  }

  @Override
  public boolean hasPolymorphicCollectionId(int collectionId) {
    assert session.assertIfNotActive();
    return resolve().hasPolymorphicCollectionId(collectionId);
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      hashCode = delegate.getName().hashCode();
    }

    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (obj instanceof SchemaClassInternal schemaClass) {
      return session == schemaClass.getBoundToSession()
          && delegate.getName().equals(schemaClass.getName());
    }

    return false;
  }

  @Override
  public DatabaseSessionEmbedded getBoundToSession() {
    return session;
  }

  @Override
  public String toString() {
    if (session.isActiveOnCurrentThread()) {
      return delegate.getName();
    }

    return super.toString();
  }
}
