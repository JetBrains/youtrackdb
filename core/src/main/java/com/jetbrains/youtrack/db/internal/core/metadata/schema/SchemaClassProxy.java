package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ProxedResource;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

public final class SchemaClassProxy extends ProxedResource<SchemaClassImpl> implements
    SchemaClassInternal {

  private int hashCode = 0;

  public SchemaClassProxy(SchemaClassImpl delegate,
      @Nonnull DatabaseSessionInternal session) {
    super(delegate, session);
  }

  @Override
  public ClusterSelectionStrategy getClusterSelection() {
    assert this.session.assertIfNotActive();
    return delegate.getClusterSelection(session);
  }

  @Override
  public int getClusterForNewInstance(EntityImpl entity) {
    assert this.session.assertIfNotActive();
    return delegate.getClusterSelection(this.session).getCluster(this.session, this, entity);
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session, String... fields) {
    assert this.session.assertIfNotActive();
    return delegate.getInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return delegate.getInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyTypeInternal iType, PropertyTypeInternal iLinkedType, boolean unsafe) {
    assert this.session.assertIfNotActive();
    return new SchemaPropertyProxy(
        delegate.createProperty(session, iPropertyName, iType, iLinkedType, unsafe), session);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyTypeInternal iType, SchemaClass iLinkedClass, boolean unsafe) {
    assert this.session.assertIfNotActive();
    return new SchemaPropertyProxy(delegate.createProperty(session, iPropertyName, iType,
        iLinkedClass != null ? ((SchemaClassInternal) iLinkedClass).getImplementation() : null,
        unsafe), session);
  }

  @Override
  public Set<Index> getIndexesInternal() {
    assert this.session.assertIfNotActive();
    return delegate.getIndexesInternal(session);
  }

  @Override
  public void getIndexesInternal(DatabaseSessionInternal session, Collection<Index> indices) {
    assert this.session.assertIfNotActive();
    delegate.getIndexesInternal(this.session, indices);
  }

  @Override
  public long count(DatabaseSessionInternal session) {
    assert this.session.assertIfNotActive();
    return delegate.count(this.session);
  }

  @Override
  public void truncate() {
    assert this.session.assertIfNotActive();
    delegate.truncate(session);
  }

  @Override
  public long count(DatabaseSessionInternal session, boolean isPolymorphic) {
    assert this.session.assertIfNotActive();
    return delegate.count(this.session, isPolymorphic);
  }

  @Override
  public SchemaPropertyInternal getPropertyInternal(String propertyName) {
    assert this.session.assertIfNotActive();
    var result = delegate.getPropertyInternal(session, propertyName);
    return result != null ? new SchemaPropertyProxy(result, session) : null;
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      String... fields) {
    assert this.session.assertIfNotActive();
    return delegate.getClassInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return delegate.getClassInvolvedIndexesInternal(this.session, fields);
  }

  @Override
  public Set<Index> getClassIndexesInternal() {
    assert this.session.assertIfNotActive();
    return delegate.getClassIndexesInternal(session);
  }

  @Override
  public Index getClassIndex(DatabaseSessionInternal session, String name) {
    assert this.session.assertIfNotActive();
    return delegate.getClassIndex(this.session, name);
  }

  @Override
  public SchemaClass set(ATTRIBUTES attribute,
      Object value) {
    assert session.assertIfNotActive();
    delegate.set(session, attribute, value);
    return this;
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSessionInternal session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return delegate.getInvolvedIndexes(this.session, fields);
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSessionInternal session, String... fields) {
    assert this.session.assertIfNotActive();
    return delegate.getInvolvedIndexes(this.session, fields);
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session,
      Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return delegate.getClassInvolvedIndexes(this.session, fields);
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session, String... fields) {
    assert this.session.assertIfNotActive();
    return delegate.getClassInvolvedIndexes(this.session, fields);
  }

  @Override
  public boolean areIndexed(DatabaseSessionInternal session, Collection<String> fields) {
    assert this.session.assertIfNotActive();
    return delegate.areIndexed(this.session, fields);
  }

  @Override
  public boolean areIndexed(DatabaseSessionInternal session, String... fields) {
    assert this.session.assertIfNotActive();
    return delegate.areIndexed(this.session, fields);
  }

  @Override
  public Set<String> getClassIndexes() {
    assert session.assertIfNotActive();
    return delegate.getClassIndexes(session);
  }

  @Override
  public Set<String> getIndexes() {
    assert session.assertIfNotActive();
    return delegate.getIndexes(session);
  }

  @Override
  public SchemaClassImpl getImplementation() {
    return delegate;
  }

  @Override
  public boolean isAbstract() {
    assert session.assertIfNotActive();
    return delegate.isAbstract(session);
  }

  @Override
  public SchemaClass setAbstract(boolean iAbstract) {
    assert session.assertIfNotActive();
    delegate.setAbstract(session, iAbstract);
    return this;
  }

  @Override
  public boolean isStrictMode() {
    assert session.assertIfNotActive();
    return delegate.isStrictMode(session);
  }

  @Override
  public void setStrictMode(boolean iMode) {
    assert session.assertIfNotActive();
    delegate.setStrictMode(session, iMode);
  }

  @Override
  public boolean hasSuperClasses() {
    assert session.assertIfNotActive();
    return delegate.hasSuperClasses(session);
  }

  @Override
  public List<String> getSuperClassesNames() {
    assert session.assertIfNotActive();
    return delegate.getSuperClassesNames(session);
  }

  @Override
  public List<SchemaClass> getSuperClasses() {
    assert session.assertIfNotActive();
    var result = delegate.getSuperClasses(session);
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

    var classesImpl = new ArrayList<SchemaClassImpl>(classes.size());
    for (var schemaClass : classes) {
      classesImpl.add(((SchemaClassInternal) schemaClass).getImplementation());
    }
    delegate.setSuperClasses(session, classesImpl);

    return this;
  }

  @Override
  public SchemaClass addSuperClass(SchemaClass superClass) {
    assert session.assertIfNotActive();
    delegate.addSuperClass(session,
        ((SchemaClassInternal) superClass).getImplementation());
    return this;
  }

  @Override
  public void removeSuperClass(SchemaClass superClass) {
    assert session.assertIfNotActive();
    delegate.removeSuperClass(this.session, ((SchemaClassInternal) superClass).getImplementation());
  }

  @Override
  public String getName() {
    assert this.session.assertIfNotActive();
    return delegate.getName(this.session);
  }

  @Override
  public SchemaClass setName(String iName) {
    assert this.session.assertIfNotActive();
    delegate.setName(this.session, iName);
    hashCode = 0;
    return this;
  }

  @Override
  public String getDescription() {
    assert this.session.assertIfNotActive();
    return delegate.getDescription(this.session);
  }

  @Override
  public SchemaClass setDescription(String iDescription) {
    assert this.session.assertIfNotActive();
    delegate.setDescription(this.session, iDescription);
    return this;
  }

  @Override
  public String getStreamableName() {
    assert this.session.assertIfNotActive();
    return delegate.getStreamableName(this.session);
  }

  @Override
  public Collection<SchemaProperty> declaredProperties() {
    assert this.session.assertIfNotActive();
    var result = delegate.declaredProperties(this.session);

    var resultProxy = new ArrayList<SchemaProperty>(result.size());
    for (var schemaProperty : result) {
      resultProxy.add(new SchemaPropertyProxy(schemaProperty, this.session));
    }

    return resultProxy;
  }

  @Override
  public Collection<SchemaProperty> properties() {
    assert this.session.assertIfNotActive();
    var result = delegate.properties(this.session);

    var resultProxy = new ArrayList<SchemaProperty>(result.size());
    for (var schemaProperty : result) {
      resultProxy.add(new SchemaPropertyProxy(schemaProperty, this.session));
    }

    return resultProxy;
  }

  @Override
  public Map<String, SchemaProperty> propertiesMap() {
    assert session.assertIfNotActive();
    var result = delegate.propertiesMap(session);

    var resultProxy = new HashMap<String, SchemaProperty>(result.size());
    for (var entry : result.entrySet()) {
      resultProxy.put(entry.getKey(), new SchemaPropertyProxy(entry.getValue(), session));
    }

    return resultProxy;
  }

  @Override
  public SchemaProperty getProperty(String iPropertyName) {
    assert session.assertIfNotActive();
    var result = delegate.getProperty(session, iPropertyName);
    return result != null ? new SchemaPropertyProxy(result, session) : null;
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType) {
    assert session.assertIfNotActive();
    var result = delegate.createProperty(session, iPropertyName, iType);
    return new SchemaPropertyProxy(result, session);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType, SchemaClass iLinkedClass) {
    assert session.assertIfNotActive();

    var result = delegate.createProperty(session, iPropertyName, iType,
        iLinkedClass != null ? ((SchemaClassInternal) iLinkedClass).getImplementation() : null);
    return new SchemaPropertyProxy(result, session);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType, PropertyType iLinkedType) {
    assert session.assertIfNotActive();
    var result = delegate.createProperty(session, iPropertyName, iType, iLinkedType);
    return new SchemaPropertyProxy(result, session);
  }

  @Override
  public void dropProperty(String iPropertyName) {
    assert session.assertIfNotActive();
    delegate.dropProperty(session, iPropertyName);
  }

  @Override
  public boolean existsProperty(String iPropertyName) {
    assert session.assertIfNotActive();
    return delegate.existsProperty(session, iPropertyName);
  }

  @Override
  public int[] getClusterIds() {
    assert session.assertIfNotActive();
    return delegate.getClusterIds(session);
  }

  @Override
  public int[] getPolymorphicClusterIds() {
    assert session.assertIfNotActive();
    return delegate.getPolymorphicClusterIds(session);
  }

  @Override
  public Collection<SchemaClass> getSubclasses() {
    assert session.assertIfNotActive();
    var result = delegate.getSubclasses(session);
    var resultProxy = new ArrayList<SchemaClass>(result.size());

    for (var schemaClass : result) {
      resultProxy.add(new SchemaClassProxy(schemaClass, session));
    }

    return resultProxy;
  }

  @Override
  public Collection<SchemaClass> getAllSubclasses() {
    assert session.assertIfNotActive();
    var result = delegate.getAllSubclasses(session);
    var resultProxy = new ArrayList<SchemaClass>(result.size());

    for (var schemaClass : result) {
      resultProxy.add(new SchemaClassProxy(schemaClass, session));
    }

    return resultProxy;

  }

  @Override
  public Collection<SchemaClass> getAllSuperClasses() {
    assert session.assertIfNotActive();
    var result = delegate.getAllSuperClasses(session);
    var resultProxy = new ArrayList<SchemaClass>(result.size());

    for (var schemaClass : result) {
      resultProxy.add(new SchemaClassProxy(schemaClass, session));
    }

    return resultProxy;
  }

  @Override
  public boolean isSubClassOf(String iClassName) {
    assert session.assertIfNotActive();
    return delegate.isSubClassOf(session, iClassName);
  }

  @Override
  public boolean isSubClassOf(SchemaClass iClass) {
    assert session.assertIfNotActive();
    return delegate.isSubClassOf(session, ((SchemaClassInternal) iClass).getImplementation());
  }

  @Override
  public boolean isSuperClassOf(SchemaClass iClass) {
    assert session.assertIfNotActive();
    return delegate.isSuperClassOf(session, ((SchemaClassInternal) iClass).getImplementation());
  }

  @Override
  public void createIndex(String iName, INDEX_TYPE iType,
      String... fields) {
    assert session.assertIfNotActive();
    delegate.createIndex(session, iName, iType, fields);
  }

  @Override
  public void createIndex(String iName, String iType, String... fields) {
    assert session.assertIfNotActive();
    delegate.createIndex(session, iName, iType, fields);
  }

  @Override
  public void createIndex(String iName, INDEX_TYPE iType,
      ProgressListener iProgressListener, String... fields) {
    assert session.assertIfNotActive();
    delegate.createIndex(session, iName, iType, iProgressListener, fields);
  }

  @Override
  public void createIndex(String iName, String iType,
      ProgressListener iProgressListener, Map<String, Object> metadata, String algorithm,
      String... fields) {
    assert session.assertIfNotActive();
    delegate.createIndex(session, iName, iType, iProgressListener, metadata, algorithm, fields);
  }

  @Override
  public void createIndex(String iName, String iType,
      ProgressListener iProgressListener, Map<String, Object> metadata, String... fields) {
    assert session.assertIfNotActive();
    delegate.createIndex(session, iName, iType, iProgressListener, metadata, fields);
  }

  @Override
  public boolean isEdgeType() {
    assert session.assertIfNotActive();
    return delegate.isEdgeType(session);
  }

  @Override
  public boolean isVertexType() {
    assert session.assertIfNotActive();
    return delegate.isVertexType(session);
  }

  @Override
  public String getCustom(String iName) {
    assert session.assertIfNotActive();
    return delegate.getCustom(session, iName);
  }

  @Override
  public SchemaClass setCustom(String iName, String iValue) {
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
    return delegate.getCustomKeys(session);
  }

  @Override
  public boolean hasClusterId(int clusterId) {
    assert session.assertIfNotActive();
    return delegate.hasClusterId(session, clusterId);
  }

  @Override
  public boolean hasPolymorphicClusterId(int clusterId) {
    assert session.assertIfNotActive();
    return delegate.hasPolymorphicClusterId(session, clusterId);
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      hashCode = delegate.getName(session).hashCode();
    }

    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (obj instanceof SchemaClassInternal schemaClass) {
      return session == schemaClass.getBoundToSession() && delegate.getName(session).
          equals(schemaClass.getName());
    }

    return false;
  }

  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }

  @Override
  public String toString() {
    if (session.isActiveOnCurrentThread()) {
      return delegate.getName(session);
    }

    return super.toString();
  }
}
