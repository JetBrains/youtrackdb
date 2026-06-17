package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Proxy over a {@link SchemaPropertyImpl}. Reads route through {@link #resolve()} and writes through
 * {@link #resolveForWrite()} (see {@link SchemaProxedResource}), so during a schema transaction the
 * proxy operates on the tx-local property object (its owner class re-resolved by name into the
 * tx-local copy) rather than the committed shared one, and an impl-typed argument such as a linked
 * class is re-resolved by name before it is linked.
 */
public final class SchemaPropertyProxy extends SchemaProxedResource<SchemaPropertyImpl> implements
    SchemaPropertyInternal {

  private int hashCode = 0;

  public SchemaPropertyProxy(SchemaPropertyImpl iDelegate,
      DatabaseSessionEmbedded session) {
    super(iDelegate, session);
  }

  @Override
  protected SchemaPropertyImpl rebindToTxLocal(@Nonnull SchemaShared txLocalSchema) {
    var owner = delegate.getOwnerClass();
    if (owner != null && owner.getOwner() == txLocalSchema) {
      // Already a tx-local object (e.g. a property created earlier in the same transaction).
      return delegate;
    }
    if (owner == null) {
      throw new IllegalStateException(
          "Property '" + delegate.getName() + "' has no owner class and cannot be resolved against"
              + " the transaction-local schema view");
    }
    var resolvedOwner = txLocalSchema.getClass(owner.getName());
    if (resolvedOwner == null) {
      throw new IllegalStateException(
          "Owner class '" + owner.getName() + "' of property '" + delegate.getName() + "' is not"
              + " present in the transaction-local schema view");
    }
    var resolved = resolvedOwner.getPropertyInternal(delegate.getName());
    if (resolved == null) {
      throw new IllegalStateException(
          "Property '" + delegate.getName() + "' is not present on class '" + owner.getName()
              + "' in the transaction-local schema view; it may have been dropped earlier in this"
              + " transaction");
    }
    return resolved;
  }

  @Override
  public Collection<String> getAllIndexes() {
    assert session.assertIfNotActive();
    return resolve().getAllIndexes(session);
  }

  @Override
  public Collection<Index> getAllIndexesInternal() {
    assert session.assertIfNotActive();
    return resolve().getAllIndexesInternal(session);
  }

  @Override
  public String getName() {
    assert session.assertIfNotActive();
    return resolve().getName();
  }

  @Override
  public String getFullName() {
    assert session.assertIfNotActive();
    return resolve().getFullName(session);
  }

  @Override
  public SchemaProperty setName(String iName) {
    assert session.assertIfNotActive();
    resolveForWrite().setName(session, iName);
    hashCode = 0;
    return this;
  }

  @Override
  public void set(ATTRIBUTES attribute, Object iValue) {
    assert session.assertIfNotActive();
    resolveForWrite().set(session, attribute, iValue);
  }

  @Override
  public PropertyType getType() {
    assert session.assertIfNotActive();
    return resolve().getType();
  }

  @Nullable @Override
  public SchemaClass getLinkedClass() {
    assert session.assertIfNotActive();
    var result = resolve().getLinkedClass(session);
    return result == null ? null : new SchemaClassProxy(result, session);
  }

  @Override
  public SchemaProperty setLinkedClass(SchemaClass oClass) {
    assert session.assertIfNotActive();
    var property = resolveForWrite();
    var linkedImpl = oClass != null
        ? reresolveClassImpl(property.getOwnerClass().getOwner(),
            ((SchemaClassInternal) oClass).getImplementation())
        : null;
    property.setLinkedClass(session, linkedImpl);
    return this;
  }

  @Nullable @Override
  public PropertyType getLinkedType() {
    assert session.assertIfNotActive();
    var linkedType = resolve().getLinkedType();
    if (linkedType == null) {
      return null;
    }

    return linkedType.getPublicPropertyType();
  }

  @Override
  public SchemaProperty setLinkedType(@Nonnull PropertyType type) {
    assert session.assertIfNotActive();
    resolveForWrite().setLinkedType(session, PropertyTypeInternal.convertFromPublicType(type));
    return this;
  }

  @Override
  public boolean isNotNull() {
    assert session.assertIfNotActive();
    return resolve().isNotNull();
  }

  @Override
  public SchemaProperty setNotNull(boolean iNotNull) {
    assert session.assertIfNotActive();
    resolveForWrite().setNotNull(session, iNotNull);
    return this;
  }

  @Override
  public Collate getCollate() {
    assert session.assertIfNotActive();
    return resolve().getCollate();
  }

  @Override
  public SchemaProperty setCollate(String iCollateName) {
    assert session.assertIfNotActive();
    resolveForWrite().setCollate(session, iCollateName);
    return this;
  }

  @Override
  public SchemaProperty setCollate(Collate collate) {
    assert session.assertIfNotActive();
    resolveForWrite().setCollate(session, collate);
    return this;
  }

  @Override
  public boolean isMandatory() {
    assert session.assertIfNotActive();
    return resolve().isMandatory();
  }

  @Override
  public SchemaProperty setMandatory(boolean mandatory) {
    assert session.assertIfNotActive();
    resolveForWrite().setMandatory(session, mandatory);
    return this;
  }

  @Override
  public boolean isReadonly() {
    assert session.assertIfNotActive();
    return resolve().isReadonly();
  }

  @Override
  public SchemaProperty setReadonly(boolean iReadonly) {
    assert session.assertIfNotActive();
    resolveForWrite().setReadonly(session, iReadonly);
    return this;
  }

  @Override
  public String getMin() {
    assert session.assertIfNotActive();
    return resolve().getMin();
  }

  @Override
  public SchemaProperty setMin(String min) {
    assert session.assertIfNotActive();
    resolveForWrite().setMin(session, min);
    return this;
  }

  @Override
  public String getMax() {
    assert session.assertIfNotActive();
    return resolve().getMax();
  }

  @Override
  public SchemaProperty setMax(String max) {
    assert session.assertIfNotActive();
    resolveForWrite().setMax(session, max);
    return this;
  }

  @Override
  public String getDefaultValue() {
    assert session.assertIfNotActive();
    return resolve().getDefaultValue();
  }

  @Override
  public SchemaProperty setDefaultValue(String defaultValue) {
    assert session.assertIfNotActive();
    resolveForWrite().setDefaultValue(session, defaultValue);
    return this;
  }

  @Override
  public String createIndex(INDEX_TYPE iType) {
    assert session.assertIfNotActive();
    return resolveForWrite().createIndex(session, iType);
  }

  @Override
  public String createIndex(String iType) {
    assert session.assertIfNotActive();
    return resolveForWrite().createIndex(session, iType);
  }

  @Override
  public String createIndex(String iType, Map<String, Object> metadata) {
    assert session.assertIfNotActive();
    return resolveForWrite().createIndex(session, iType, metadata);
  }

  @Override
  public String createIndex(INDEX_TYPE iType, Map<String, Object> metadata) {
    assert session.assertIfNotActive();
    return resolveForWrite().createIndex(session, iType, metadata);
  }

  @Override
  public String getRegexp() {
    assert session.assertIfNotActive();
    return resolve().getRegexp();
  }

  @Override
  public SchemaProperty setRegexp(String regexp) {
    assert session.assertIfNotActive();
    resolveForWrite().setRegexp(session, regexp);
    return this;
  }

  @Override
  public SchemaProperty setType(PropertyType iType) {
    assert session.assertIfNotActive();
    resolveForWrite().setType(session, PropertyTypeInternal.convertFromPublicType(iType));
    return this;
  }

  @Override
  public String getCustom(String iName) {
    assert session.assertIfNotActive();
    return resolve().getCustom(iName);
  }

  @Override
  public SchemaProperty setCustom(String iName,
      String iValue) {
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

  @Nullable @Override
  public SchemaClass getOwnerClass() {
    assert session.assertIfNotActive();
    var result = resolve().getOwnerClass();
    return result == null ? null : new SchemaClassProxy(result, session);
  }

  @Override
  public Object get(ATTRIBUTES iAttribute) {
    assert session.assertIfNotActive();
    return resolve().get(session, iAttribute);
  }

  @Override
  public Integer getId() {
    assert session.assertIfNotActive();
    return resolve().getId();
  }

  @Override
  public String getDescription() {
    assert session.assertIfNotActive();
    return resolve().getDescription();
  }

  @Override
  public SchemaProperty setDescription(String iDescription) {
    assert session.assertIfNotActive();
    resolveForWrite().setDescription(session, iDescription);
    return this;
  }

  @Override
  public DatabaseSessionEmbedded getBoundToSession() {
    return session;
  }

  @Override
  public PropertyTypeInternal getTypeInternal() {
    assert session.assertIfNotActive();
    return resolve().getTypeInternal();
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      var name = delegate.getName();
      var ownerName = delegate.getOwnerClass().getName();
      hashCode = name.hashCode() + 31 * ownerName.hashCode();
    }

    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }

    if (obj instanceof SchemaPropertyInternal schemaProperty) {
      if (session != schemaProperty.getBoundToSession()) {
        return false;
      }

      return delegate.getName().equals(schemaProperty.getName())
          && delegate.getOwnerClass().getName()
              .equals(schemaProperty.getOwnerClass().getName());
    }

    return false;
  }

  @Override
  public String toString() {
    if (session.isActiveOnCurrentThread()) {
      return delegate.getName() + " (type=" + delegate.getType() + ")";
    }

    return super.toString();
  }
}
