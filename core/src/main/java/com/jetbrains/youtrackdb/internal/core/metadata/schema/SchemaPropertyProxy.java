package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ProxedResource;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class SchemaPropertyProxy extends ProxedResource<SchemaPropertyEntity> implements
    SchemaProperty {

  private Comparable<Object> maxComparable;
  private Comparable<Object> minComparable;

  public SchemaPropertyProxy(SchemaPropertyEntity iDelegate,
      DatabaseSessionEmbedded session) {
    super(iDelegate, session);
  }

  @Override
  public Collection<String> getIndexNames() {
    assert session.assertIfNotActive();
    return YTDBIteratorUtils.list(
        YTDBIteratorUtils.map(delegate.getIndexes(), SchemaIndexEntity::getName)
    );
  }

  @Override
  public Collection<Index> getIndexes() {
    return YTDBIteratorUtils.list(
        YTDBIteratorUtils.map(delegate.getIndexes(),
            IndexFactory::newIndexProxy
        )
    );
  }

  @Override
  public String createIndex(INDEX_TYPE iType) {
    var declaringClass = delegate.getDeclaringClass();
    var indexName = delegate.getFullName();
    SchemaManager.createIndex(delegate.getSession(), declaringClass, delegate.getFullName(), iType,
        delegate.getName());
    return indexName;
  }

  @Override
  public String createIndex(INDEX_TYPE iType, Map<String, Object> metadata) {
    var declaringClass = delegate.getDeclaringClass();
    var indexName = delegate.getFullName();
    SchemaManager.createIndex(delegate.getSession(), declaringClass, delegate.getFullName(), iType,
        metadata, delegate.getName());
    return indexName;
  }

  @Override
  public String getName() {
    assert session.assertIfNotActive();
    return delegate.getName();
  }

  @Override
  public String getFullName() {
    assert session.assertIfNotActive();
    return delegate.getFullName();
  }

  @Override
  public void setName(String name) {
    assert session.assertIfNotActive();
    delegate.setName(name);
  }

  @Override
  public PropertyTypeInternal getType() {
    assert session.assertIfNotActive();
    return delegate.getPropertyType();
  }

  @Nullable
  @Override
  public SchemaClass getLinkedClass() {
    assert session.assertIfNotActive();
    var result = delegate.getLinkedClass();
    return result == null ? null : new SchemaClassProxy(result, session);
  }

  @Override
  public void setLinkedClass(SchemaClass schemaClass) {
    assert session.assertIfNotActive();
    delegate.setLinkedClass(schemaClass != null ? schemaClass.getImplementation() : null);
  }

  @Nullable
  @Override
  public PropertyTypeInternal getLinkedType() {
    assert session.assertIfNotActive();
    return delegate.getLinkedPropertyType();
  }

  @Override
  public void setLinkedType(@Nonnull PropertyTypeInternal type) {
    assert session.assertIfNotActive();
    delegate.setLinkedPropertyType(type);
  }

  @Override
  public boolean isNotNull() {
    assert session.assertIfNotActive();
    return delegate.isNotNull();
  }

  @Override
  public void setNotNull(boolean iNotNull) {
    assert session.assertIfNotActive();
    delegate.setNotNull(iNotNull);
  }

  @Override
  public Collate getCollate() {
    assert session.assertIfNotActive();
    return SQLEngine.getCollate(delegate.getCollate());
  }

  @Override
  public SchemaProperty setCollate(String iCollateName) {
    assert session.assertIfNotActive();
    delegate.setCollateName(iCollateName);
    return this;
  }

  @Override
  public void setCollate(Collate collate) {
    assert session.assertIfNotActive();
    if (collate != null) {
      delegate.setCollateName(collate.getName());
    } else {
      delegate.setCollateName(null);
    }
  }

  @Override
  public boolean isMandatory() {
    assert session.assertIfNotActive();
    return delegate.isMandatory();
  }

  @Override
  public void setMandatory(boolean mandatory) {
    assert session.assertIfNotActive();
    delegate.setMandatory(mandatory);
  }

  @Override
  public boolean isReadonly() {
    assert session.assertIfNotActive();
    return delegate.isReadonly();
  }

  @Override
  public void setReadonly(boolean readonly) {
    assert session.assertIfNotActive();
    delegate.setReadonly(readonly);
  }

  @Override
  public String getMin() {
    assert session.assertIfNotActive();
    return delegate.getMin();
  }

  @Override
  public void setMin(String min) {
    assert session.assertIfNotActive();
    minComparable = null;
    delegate.setMin(min);
  }

  @Override
  public String getMax() {
    assert session.assertIfNotActive();
    return delegate.getMax();
  }

  @Nullable
  @Override
  public Comparable<Object> getMinComparable() {
    var min = getMin();
    if (min == null) {
      return null;
    }

    if (minComparable == null) {
      minComparable = SchemaPropertySnapshot.createMinComparable(session, min, getType(),
          getFullName());
    }

    return minComparable;
  }

  @Nullable
  @Override
  public Comparable<Object> getMaxComparable() {
    var max = getMax();
    if (max == null) {
      return null;
    }

    if (maxComparable == null) {
      maxComparable = SchemaPropertySnapshot.createMaxComparable(session, max, getType(),
          getFullName());
    }

    return maxComparable;
  }

  @Override
  public void setMax(String max) {
    assert session.assertIfNotActive();
    maxComparable = null;
    delegate.setMax(max);
  }

  @Override
  public String getDefaultValue() {
    assert session.assertIfNotActive();
    return delegate.getDefaultValue();
  }

  @Override
  public void setDefaultValue(String defaultValue) {
    assert session.assertIfNotActive();
    delegate.setDefaultValue(defaultValue);
  }


  @Override
  public String getRegexp() {
    assert session.assertIfNotActive();
    return delegate.getRegexp();
  }

  @Override
  public void setRegexp(String regexp) {
    assert session.assertIfNotActive();
    delegate.setRegexp(regexp);
  }

  @Override
  public void setType(PropertyTypeInternal iType) {
    assert session.assertIfNotActive();
    delegate.setPropertyType(iType);
  }

  @Override
  public String getCustomProperty(String iName) {
    assert session.assertIfNotActive();
    return delegate.getCustomProperty(iName);
  }

  @Override
  public void setCustomProperty(String iName,
      String iValue) {
    assert session.assertIfNotActive();
    delegate.setCustomProperty(iName, iValue);
  }

  @Override
  public void removeCustomProperty(String iName) {
    assert session.assertIfNotActive();
    delegate.removeCustomProperty(iName);
  }

  @Override
  public void clearCustomProperties() {
    assert session.assertIfNotActive();
    delegate.clearCustomProperties();
  }

  @Override
  public Set<String> getCustomPropertyNames() {
    assert session.assertIfNotActive();
    return delegate.getCustomPropertyNames();
  }

  @Nullable
  @Override
  public SchemaClass getOwnerClass() {
    assert session.assertIfNotActive();
    var result = delegate.getDeclaringClass();
    return result == null ? null : new SchemaClassProxy(result, session);
  }

  @Override
  public Integer getId() {
    assert session.assertIfNotActive();
    return delegate.getGlobalPropertyId();
  }

  @Override
  public String getDescription() {
    assert session.assertIfNotActive();
    return delegate.getDescription();
  }

  @Override
  public SchemaProperty setDescription(String iDescription) {
    assert session.assertIfNotActive();
    delegate.setDescription(iDescription);
    return this;
  }

  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }

  @Override
  public int hashCode() {
    var name = delegate.getName();
    var ownerName = delegate.getDeclaringClass().getName();
    return name.hashCode() + 31 * ownerName.hashCode();
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
          && delegate.getDeclaringClass().getName()
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
