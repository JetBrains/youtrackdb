package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ProxedResource;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SchemaPropertyProxy extends ProxedResource<SchemaPropertyEntity> implements
    SchemaProperty {

  private int hashCode = 0;

  public SchemaPropertyProxy(SchemaPropertyEntity iDelegate,
      DatabaseSessionEmbedded session) {
    super(iDelegate, session);
  }

  @Override
  public Collection<String> getAllIndexes() {
    assert session.assertIfNotActive();
    return delegate.getAllIndexes(session);
  }

  @Override
  public Collection<Index> getAllIndexesInternal() {
    assert session.assertIfNotActive();
    return delegate.getAllIndexesInternal(session);
  }

  @Override
  public String getName() {
    assert session.assertIfNotActive();
    return delegate.getName();
  }

  @Override
  public String getFullName() {
    assert session.assertIfNotActive();
    return delegate.getFullName(session);
  }

  @Override
  public SchemaProperty setName(String iName) {
    assert session.assertIfNotActive();
    delegate.setName(session, iName);
    hashCode = 0;
    return this;
  }

  @Override
  public void set(ATTRIBUTES attribute, Object iValue) {
    assert session.assertIfNotActive();
    delegate.set(session, attribute, iValue);
  }

  @Override
  public PropertyType getType() {
    assert session.assertIfNotActive();
    return delegate.getType();
  }

  @Nullable
  @Override
  public SchemaClass getLinkedClass() {
    assert session.assertIfNotActive();
    var result = delegate.getLinkedClass(session);
    return result == null ? null : new SchemaClassProxy(result, session);
  }

  @Override
  public SchemaProperty setLinkedClass(SchemaClass oClass) {
    assert session.assertIfNotActive();
    delegate.setLinkedClass(session,
        oClass != null ? oClass.getImplementation() : null);
    return this;
  }

  @Nullable
  @Override
  public PropertyType getLinkedType() {
    assert session.assertIfNotActive();
    var linkedType = delegate.getLinkedType();
    if (linkedType == null) {
      return null;
    }

    return linkedType.getPublicPropertyType();
  }

  @Override
  public SchemaProperty setLinkedType(@Nonnull PropertyType type) {
    assert session.assertIfNotActive();
    delegate.setLinkedType(session, PropertyTypeInternal.convertFromPublicType(type));
    return this;
  }

  @Override
  public boolean isNotNull() {
    assert session.assertIfNotActive();
    return delegate.isNotNull();
  }

  @Override
  public SchemaProperty setNotNull(boolean iNotNull) {
    assert session.assertIfNotActive();
    delegate.setNotNull(session, iNotNull);
    return this;
  }

  @Override
  public Collate getCollate() {
    assert session.assertIfNotActive();
    return delegate.getCollate();
  }

  @Override
  public SchemaProperty setCollate(String iCollateName) {
    assert session.assertIfNotActive();
    delegate.setCollate(session, iCollateName);
    return this;
  }

  @Override
  public SchemaProperty setCollate(Collate collate) {
    assert session.assertIfNotActive();
    delegate.setCollate(session, collate);
    return this;
  }

  @Override
  public boolean isMandatory() {
    assert session.assertIfNotActive();
    return delegate.isMandatory();
  }

  @Override
  public SchemaProperty setMandatory(boolean mandatory) {
    assert session.assertIfNotActive();
    delegate.setMandatory(session, mandatory);
    return this;
  }

  @Override
  public boolean isReadonly() {
    assert session.assertIfNotActive();
    return delegate.isReadonly();
  }

  @Override
  public SchemaProperty setReadonly(boolean iReadonly) {
    assert session.assertIfNotActive();
    delegate.setReadonly(session, iReadonly);
    return this;
  }

  @Override
  public String getMin() {
    assert session.assertIfNotActive();
    return delegate.getMin();
  }

  @Override
  public SchemaProperty setMin(String min) {
    assert session.assertIfNotActive();
    delegate.setMin(session, min);
    return this;
  }

  @Override
  public String getMax() {
    assert session.assertIfNotActive();
    return delegate.getMax();
  }

  @Override
  public SchemaProperty setMax(String max) {
    assert session.assertIfNotActive();
    delegate.setMax(session, max);
    return this;
  }

  @Override
  public String getDefaultValue() {
    assert session.assertIfNotActive();
    return delegate.getDefaultValue();
  }

  @Override
  public SchemaProperty setDefaultValue(String defaultValue) {
    assert session.assertIfNotActive();
    delegate.setDefaultValue(session, defaultValue);
    return this;
  }

  @Override
  public String createIndex(INDEX_TYPE iType) {
    assert session.assertIfNotActive();
    return delegate.createIndex(session, iType);
  }

  @Override
  public String createIndex(String iType) {
    assert session.assertIfNotActive();
    return delegate.createIndex(session, iType);
  }

  @Override
  public String createIndex(String iType, Map<String, Object> metadata) {
    assert session.assertIfNotActive();
    return delegate.createIndex(session, iType, metadata);
  }

  @Override
  public String createIndex(INDEX_TYPE iType, Map<String, Object> metadata) {
    assert session.assertIfNotActive();
    return delegate.createIndex(session, iType, metadata);
  }

  @Override
  public String getRegexp() {
    assert session.assertIfNotActive();
    return delegate.getRegexp();
  }

  @Override
  public SchemaProperty setRegexp(String regexp) {
    assert session.assertIfNotActive();
    delegate.setRegexp(session, regexp);
    return this;
  }

  @Override
  public SchemaProperty setType(PropertyType iType) {
    assert session.assertIfNotActive();
    delegate.setType(session, PropertyTypeInternal.convertFromPublicType(iType));
    return this;
  }

  @Override
  public String getCustom(String iName) {
    assert session.assertIfNotActive();
    return delegate.getCustom(iName);
  }

  @Override
  public SchemaProperty setCustom(String iName,
      String iValue) {
    assert session.assertIfNotActive();
    delegate.setCustom(session, iName, iValue);
    return this;
  }

  @Override
  public void removeCustom(String iName) {
    assert session.assertIfNotActive();
    delegate.removeCustom(session, iName);
  }

  @Override
  public void clearCustom() {
    assert session.assertIfNotActive();
    delegate.clearCustom(session);
  }

  @Override
  public Set<String> getCustomKeys() {
    assert session.assertIfNotActive();
    return delegate.getCustomKeys();
  }

  @Nullable
  @Override
  public SchemaClass getOwnerClass() {
    assert session.assertIfNotActive();
    var result = delegate.getOwnerClass();
    return result == null ? null : new SchemaClassProxy(result, session);
  }

  @Override
  public Object get(ATTRIBUTES iAttribute) {
    assert session.assertIfNotActive();
    return delegate.get(session, iAttribute);
  }

  @Override
  public Integer getId() {
    assert session.assertIfNotActive();
    return delegate.getId();
  }

  @Override
  public String getDescription() {
    assert session.assertIfNotActive();
    return delegate.getDescription();
  }

  @Override
  public SchemaProperty setDescription(String iDescription) {
    assert session.assertIfNotActive();
    delegate.setDescription(session, iDescription);
    return this;
  }

  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }

  @Override
  public PropertyTypeInternal getTypeInternal() {
    assert session.assertIfNotActive();
    return delegate.getTypeInternal();
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

    if (obj instanceof SchemaProperty schemaProperty) {
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
