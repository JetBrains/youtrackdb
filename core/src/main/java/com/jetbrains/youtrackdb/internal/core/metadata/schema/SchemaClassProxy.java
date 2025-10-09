package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.ProxedResource;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.RoundRobinCollectionSelectionStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaClassEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

public final class SchemaClassProxy extends ProxedResource<SchemaClassEntity> implements
    SchemaClass {

  public SchemaClassProxy(SchemaClassEntity delegate,
      @Nonnull DatabaseSessionEmbedded session) {
    super(delegate, session);
  }

  @Override
  public int getCollectionForNewInstance(EntityImpl entity) {
    assert this.session.assertIfNotActive();
    return RoundRobinCollectionSelectionStrategy.INSTANCE.getCollection(this.session, this, entity);
  }

  @Override
  public Set<Index> getInvolvedIndexes(String... properties) {
    assert this.session.assertIfNotActive();

    var result = new HashSet<Index>();
    var indexEntities = SchemaManager.getInvolvedIndexes(delegate, properties);
    indexEntitiesToIndexes(indexEntities.iterator(), result);
    return result;
  }

  @Override
  public Set<Index> getInvolvedIndexes(Collection<String> properties) {
    assert this.session.assertIfNotActive();

    var result = new HashSet<Index>();
    var indexEntities = SchemaManager.getInvolvedIndexes(delegate, properties);
    indexEntitiesToIndexes(indexEntities.iterator(), result);
    return result;
  }

  @Override
  public SchemaProperty createProperty(String propertyName,
      PropertyTypeInternal type, PropertyTypeInternal linkedType) {
    assert this.session.assertIfNotActive();
    return new SchemaPropertyProxy(
        SchemaManager.createProperty(session, delegate, propertyName, type, linkedType), session);
  }

  @Override
  public SchemaProperty createProperty(String propertyName,
      PropertyTypeInternal type, SchemaClass linkedClass) {
    assert this.session.assertIfNotActive();
    return new SchemaPropertyProxy(
        SchemaManager.createProperty(session, delegate, propertyName, type,
            linkedClass != null ? linkedClass.getImplementation() : null), session);
  }

  @Override
  public long count(DatabaseSessionInternal session) {
    assert this.session.assertIfNotActive();
    return session.countClass(delegate.getSchemaClassName());
  }

  @Override
  public void truncate() {
    assert this.session.assertIfNotActive();
    session.truncateClass(delegate.getSchemaClassName(), true);
  }

  @Override
  public long count(DatabaseSessionInternal session, boolean isPolymorphic) {
    assert this.session.assertIfNotActive();
    return session.countClass(delegate.getSchemaClassName(), isPolymorphic);
  }


  @Override
  public Set<Index> getClassInvolvedIndexes(String... properties) {
    assert this.session.assertIfNotActive();

    var result = new HashSet<Index>();
    var indexEntities = SchemaManager.getClassInvolvedIndexes(delegate, Arrays.asList(properties));
    indexEntitiesToIndexes(indexEntities.iterator(), result);

    return result;
  }


  @Override
  public Set<Index> getClassInvolvedIndexes(Collection<String> properties) {
    assert this.session.assertIfNotActive();

    var result = new HashSet<Index>();
    var indexEntities = SchemaManager.getClassInvolvedIndexes(delegate, properties);
    indexEntitiesToIndexes(indexEntities.iterator(), result);
    return result;
  }

  @Override
  public Collection<Index> getClassIndexesInternal() {
    assert this.session.assertIfNotActive();

    var indexEntities = YTDBIteratorUtils.set(delegate.getIndexes());
    var result = new ArrayList<Index>();
    indexEntitiesToIndexes(indexEntities.iterator(), result);
    return result;
  }

  @Nullable
  @Override
  public Index getClassIndex(DatabaseSessionInternal session, String name) {
    assert this.session.assertIfNotActive();
    var indexEntity = SchemaManager.getClassIndex(delegate, name);
    if (indexEntity == null) {
      return null;
    }

    return IndexFactory.newIndexProxy(indexEntity);
  }

  @Override
  public Set<String> getInvolvedIndexesNames(Collection<String> properties) {
    assert this.session.assertIfNotActive();
    return SchemaManager.getInvolvedIndexNames(delegate, properties);
  }

  @Override
  public Set<String> getInvolvedIndexesNames(String... properties) {
    assert this.session.assertIfNotActive();
    return SchemaManager.getInvolvedIndexNames(delegate, properties);
  }

  @Override
  public Set<String> getClassIndexes(Collection<String> properties) {
    assert this.session.assertIfNotActive();
    return SchemaManager.getClassInvolvedIndexNames(delegate, properties);
  }

  @Override
  public Set<String> getClassIndexes(String... properties) {
    assert this.session.assertIfNotActive();
    return SchemaManager.getClassInvolvedIndexNames(delegate, properties);
  }

  @Override
  public boolean areIndexed(Collection<String> properties) {
    assert this.session.assertIfNotActive();
    return SchemaManager.areIndexed(delegate, properties);
  }

  @Override
  public boolean areIndexed(String... properties) {
    assert this.session.assertIfNotActive();
    return SchemaManager.areIndexed(delegate, properties);
  }

  @Override
  public Set<String> getClassIndexes() {
    assert session.assertIfNotActive();

    return YTDBIteratorUtils.set(
        IteratorUtils.map(delegate.getIndexes(), SchemaIndexEntity::getName));
  }

  @Override
  public Set<String> getIndexNames() {
    assert session.assertIfNotActive();

    var shemaProperties = delegate.getSchemaProperties();
    var result = new HashSet<String>();

    for (var property : shemaProperties) {
      var indexEntries = property.getIndexes();

      while (indexEntries.hasNext()) {
        var indexEntry = indexEntries.next();
        result.add(indexEntry.getName());
      }
    }

    return result;
  }

  @Override
  public Set<Index> getIndexes() {
    assert session.assertIfNotActive();

    var shemaProperties = delegate.getSchemaProperties();
    var result = new HashSet<Index>();

    for (var property : shemaProperties) {
      var indexEntries = property.getIndexes();

      while (indexEntries.hasNext()) {
        var indexEntry = indexEntries.next();
        result.add(IndexFactory.newIndexSnapshot(indexEntry));
      }
    }

    return result;
  }

  @Override
  public SchemaClassEntity getImplementation() {
    return delegate;
  }

  @Override
  public boolean isAbstract() {
    assert session.assertIfNotActive();
    return delegate.isAbstractClass();
  }

  @Override
  public void setAbstract(boolean isAbstract) {
    assert session.assertIfNotActive();
    delegate.setAbstractClass(isAbstract);
  }

  @Override
  public boolean isStrictMode() {
    assert session.assertIfNotActive();
    return delegate.isStrictMode();
  }

  @Override
  public void setStrictMode(boolean strictMode) {
    assert session.assertIfNotActive();
    delegate.setStrictMode(strictMode);
  }

  @Override
  public boolean hasParentClasses() {
    assert session.assertIfNotActive();
    return delegate.hasParentClasses();
  }

  @Override
  public List<String> getParentClassesNames() {
    assert session.assertIfNotActive();

    return YTDBIteratorUtils.list(
        YTDBIteratorUtils.map(delegate.getParentClasses(), SchemaClassEntity::getName));
  }

  @Override
  public List<SchemaClass> getParentClasses() {
    assert session.assertIfNotActive();

    return YTDBIteratorUtils.list(
        YTDBIteratorUtils.map(delegate.getParentClasses(),
            entity ->
                new SchemaClassProxy(entity, session)));
  }

  @Override
  public void setParentClasses(@Nonnull List<? extends SchemaClass> classes) {
    assert session.assertIfNotActive();

    delegate.clearParentClasses();

    for (var clazz : classes) {
      var schemaClassEntity = clazz.getImplementation();
      delegate.addParentClass(schemaClassEntity);
    }
  }

  @Override
  public void addParentClass(SchemaClass parentClass) {
    assert session.assertIfNotActive();
    delegate.addParentClass(parentClass.getImplementation());
  }

  @Override
  public void removeSuperClass(SchemaClass parentClass) {
    assert session.assertIfNotActive();
    delegate.removeParentClass(parentClass.getImplementation());
  }

  @Override
  public String getName() {
    assert this.session.assertIfNotActive();
    return delegate.getName();
  }

  @Override
  public SchemaClass setName(String iName) {
    assert this.session.assertIfNotActive();
    delegate.setName(iName);
    return this;
  }

  @Override
  public String getDescription() {
    assert this.session.assertIfNotActive();
    return delegate.getDescription();
  }

  @Override
  public SchemaClass setDescription(String iDescription) {
    assert this.session.assertIfNotActive();
    delegate.setDescription(iDescription);
    return this;
  }

  @Override
  public Collection<SchemaProperty> getDeclaredProperties() {
    assert this.session.assertIfNotActive();
    return YTDBIteratorUtils.list(YTDBIteratorUtils.map(
        delegate.getDeclaredProperties(), entity -> new SchemaPropertyProxy(entity, session)
    ));
  }

  @Override
  public Collection<SchemaProperty> getProperties() {
    assert this.session.assertIfNotActive();
    return YTDBIteratorUtils.list(
        YTDBIteratorUtils.map(delegate.getSchemaProperties().iterator(),
            entity -> new SchemaPropertyProxy(entity, session))
    );
  }

  @Override
  public Map<String, SchemaProperty> getPropertiesMap() {
    assert session.assertIfNotActive();
    var result = new HashMap<String, SchemaProperty>();
    var schemaProperties = delegate.getSchemaProperties();
    for (var schemaProperty : schemaProperties) {
      result.put(schemaProperty.getName(), new SchemaPropertyProxy(schemaProperty, session));
    }

    return result;
  }

  @Nullable
  @Override
  public SchemaProperty getProperty(String propertyName) {
    assert session.assertIfNotActive();
    var result = delegate.getSchemaProperty(propertyName);
    return result != null ? new SchemaPropertyProxy(result, session) : null;
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyTypeInternal iType) {
    assert session.assertIfNotActive();
    var property = SchemaManager.createProperty(session, delegate, iPropertyName, iType);
    return new SchemaPropertyProxy(property, session);
  }

  @Override
  public void dropProperty(String iPropertyName) {
    assert session.assertIfNotActive();
    delegate.removeSchemaProperty(iPropertyName);
  }

  @Override
  public boolean existsProperty(String propertyName) {
    assert session.assertIfNotActive();
    return delegate.existsSchemaProperty(propertyName);
  }

  @Override
  public int[] getCollectionIds() {
    assert session.assertIfNotActive();
    return delegate.getPrimitiveCollectionIds();
  }

  @Override
  public int[] getPolymorphicCollectionIds() {
    assert session.assertIfNotActive();
    return delegate.getPrimitivePolymorphicCollectionIds();
  }

  @Override
  public Collection<SchemaClass> getChildClasses() {
    assert session.assertIfNotActive();

    return YTDBIteratorUtils.list(YTDBIteratorUtils.map(
        delegate.getChildClasses(), entity -> new SchemaClassProxy(entity, session)
    ));
  }

  @Override
  public Collection<SchemaClass> getDescendantClasses() {
    assert session.assertIfNotActive();

    return YTDBIteratorUtils.list(YTDBIteratorUtils.map(
        delegate.getDescendants().iterator(), entity -> new SchemaClassProxy(entity, session)
    ));

  }

  @Override
  public Collection<SchemaClass> getAscendantClasses() {
    assert session.assertIfNotActive();
    return YTDBIteratorUtils.list(YTDBIteratorUtils.map(
        delegate.getAscendants().iterator(), entity -> new SchemaClassProxy(entity, session)
    ));
  }

  @Override
  public boolean isChildOf(String iClassName) {
    assert session.assertIfNotActive();
    return delegate.isChildOf(iClassName);
  }

  @Override
  public boolean isChildOf(ImmutableSchemaClass iClass) {
    return delegate.isChildOf(iClass.getName());
  }

  @Override
  public boolean isParentOf(ImmutableSchemaClass iClass) {
    return delegate.isParentOf(iClass.getName());
  }


  @Override
  public void createIndex(String iName, IndexType indexType, String... fields) {
    assert session.assertIfNotActive();
    SchemaManager.createIndex(session, delegate, iName, indexType, fields);
  }


  @Override
  public void createIndex(String iName, IndexType indexType,
      Map<String, Object> metadata,
      String... properties) {
    assert session.assertIfNotActive();
    SchemaManager.createIndex(session, delegate, iName, indexType, metadata, properties, null);
  }

  @Override
  public boolean isEdgeType() {
    assert session.assertIfNotActive();
    return delegate.isEdgeType();
  }

  @Override
  public boolean isVertexType() {
    assert session.assertIfNotActive();
    return delegate.isVertexType();
  }

  @Override
  public String getCustom(String iName) {
    assert session.assertIfNotActive();
    return delegate.getCustomProperty(iName);
  }

  @Override
  public SchemaClass setCustom(String iName, String iValue) {
    assert session.assertIfNotActive();
    delegate.setCustomProperty(iName, iValue);
    return this;
  }

  @Override
  public void removeCustom(String iName) {
    assert session.assertIfNotActive();
    delegate.removeCustomProperty(iName);
  }

  @Override
  public void clearCustom() {
    assert session.assertIfNotActive();
    delegate.clearCustomProperties();
  }

  @Override
  public Set<String> getCustomPopertiesNames() {
    assert session.assertIfNotActive();
    return delegate.getCustomPropertiesNames();
  }

  @Override
  public boolean hasCollectionId(int collectionId) {
    assert session.assertIfNotActive();
    return delegate.hasCollectionId(collectionId);
  }

  @Override
  public boolean hasPolymorphicCollectionId(int collectionId) {
    assert session.assertIfNotActive();
    return delegate.hasPolymorphicCollectionId(collectionId);
  }

  @Override
  public int hashCode() {
    return delegate.getName().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (obj instanceof SchemaClass schemaClass) {
      return session == schemaClass.getBoundToSession() && delegate.getName().
          equals(schemaClass.getName());
    }

    return false;
  }

  @Override
  public DatabaseSessionEmbedded getBoundToSession() {
    return session;
  }

  @Override
  public boolean isUser() {
    return delegate.isChildOf(SecurityUserImpl.CLASS_NAME);
  }

  @Override
  public boolean isScheduler() {
    return delegate.isChildOf(ScheduledEvent.CLASS_NAME);
  }

  @Override
  public boolean isRole() {
    return delegate.isChildOf(Role.CLASS_NAME);
  }

  @Override
  public boolean isSecurityPolicy() {
    return delegate.isChildOf(SecurityPolicy.CLASS_NAME);
  }

  @Override
  public boolean isFunction() {
    return delegate.isChildOf(FunctionLibraryImpl.CLASSNAME);
  }

  @Override
  public boolean isSequence() {
    return delegate.isChildOf(DBSequence.CLASS_NAME);
  }

  @Override
  public String toString() {
    if (session.isActiveOnCurrentThread()) {
      return delegate.getName();
    }

    return super.toString();
  }

  private static void indexEntitiesToIndexes(@Nonnull Iterator<SchemaIndexEntity> indexEntities,
      @Nonnull Collection<Index> result) {
    while (indexEntities.hasNext()) {
      var indexEntity = indexEntities.next();
      var index = IndexFactory.newIndexProxy(indexEntity);
      result.add(index);
    }
  }
}
