package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexBy;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainVertexAbstract;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaClassEntity;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;

public class YTDBSchemaClassImpl extends
    YTDBDomainVertexSchemaAbstract<SchemaClassEntity> implements
    YTDBSchemaClass {

  public YTDBSchemaClassImpl(YTDBGraphInternal graph, Identifiable identifiable) {
    super(graph, identifiable);
  }

  @Override
  public boolean abstractClass() {
    var entity = entityReadPreprocessing();
    return entity.isAbstractClass();
  }

  @Override
  public void abstractClass(boolean value) {
    var entity = entityWritePreprocessing();
    entity.setAbstractClass(value);
  }

  @Override
  public boolean strictMode() {
    var entity = entityReadPreprocessing();
    return entity.isStrictMode();
  }

  @Override
  public void strictMode(boolean mode) {
    var entity = entityWritePreprocessing();
    entity.setStrictMode(mode);
  }

  @Override
  public boolean hasParentClasses() {
    var entity = entityReadPreprocessing();
    return entity.hasParentClasses();
  }

  @Nonnull
  @Override
  public String name() {
    var entity = entityReadPreprocessing();
    return entity.getName();
  }

  @Override
  public void name(@Nonnull String name) {
    var entity = entityWritePreprocessing();
    entity.setName(name);
  }

  @Override
  public @Nonnull String description() {
    var entity = entityReadPreprocessing();
    return entity.getDescription();
  }

  @Override
  public void description(@Nonnull String description) {
    var entity = entityWritePreprocessing();
    entity.setDescription(description);
  }

  @Override
  public @Nonnull int[] collectionIds() {
    var entity = entityReadPreprocessing();
    var rids = entity.getCollectionIds();
    var ids = new int[rids.size()];
    for (var i = 0; i < ids.length; i++) {
      ids[i] = rids.get(i);
    }

    return ids;
  }

  @Override
  public @Nonnull int[] polymorphicCollectionIds() {
    var entity = entityReadPreprocessing();
    var rids = entity.getPolymorphicCollectionIds();
    var ids = new int[rids.size()];

    var i = 0;
    for (var rid : rids) {
      ids[i++] = rid;
    }

    return ids;
  }

  @Override
  public boolean isEdgeType() {
    var entity = entityReadPreprocessing();
    return entity.isEdgeType();
  }

  @Override
  public boolean isVertexType() {
    var entity = entityReadPreprocessing();
    return entity.isVertexType();
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> parentClasses() {
    var entity = entityReadPreprocessing();
    var superClasses = entity.getParentClasses();
    return mapToDomainClassIterator(superClasses);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> childClasses() {
    var entity = entityReadPreprocessing();
    var subClasses = entity.getChildClasses();

    return mapToDomainClassIterator(subClasses);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> descendants() {
    var entity = entityReadPreprocessing();
    var descendants = entity.getDescendants();

    return mapToDomainClassIterator(descendants.iterator());
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> ascendants() {
    var entity = entityReadPreprocessing();
    var ascendants = entity.getAscendants();

    return mapToDomainClassIterator(ascendants.iterator());
  }

  @Override
  public void addParentClass(@Nonnull YTDBSchemaClass parentClass) {
    var entity = entityWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) parentClass;
    entity.addParentClass(classImpl.getRawEntity());
  }

  @Override
  public void removeParentClass(@Nonnull YTDBSchemaClass parentClass) {
    var entity = entityWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) parentClass;
    entity.removeParentClass(classImpl.getRawEntity());
  }

  @Override
  public void addChildClass(@Nonnull YTDBSchemaClass childClass) {
    var entity = entityWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) childClass;
    entity.addChildClass(classImpl.getRawEntity());
  }

  @Override
  public void removeChildClass(@Nonnull YTDBSchemaClass childClass) {
    var entity = entityWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) childClass;
    entity.removeChildClass(classImpl.getRawEntity());
  }

  @Override
  public boolean isChildOf(@Nonnull String className) {
    var entity = entityReadPreprocessing();
    return entity.isChildOf(className);
  }

  @Override
  public boolean isChildOf(@Nonnull YTDBSchemaClass classInstance) {
    var schemaClassImpl = (YTDBSchemaClassImpl) classInstance;
    var entity = entityReadPreprocessing();
    return entity.isChildOf(schemaClassImpl.getRawEntity());
  }

  @Override
  public boolean isParentOf(@Nonnull String className) {
    var entity = entityReadPreprocessing();
    return entity.isChildOf(className);
  }

  @Override
  public boolean isParentOf(@Nonnull YTDBSchemaClass classInstance) {
    var entity = entityReadPreprocessing();
    var schemaClassImpl = (YTDBSchemaClassImpl) classInstance;

    return entity.isParentOf(schemaClassImpl.getRawEntity());
  }

  @Override
  public boolean iAssignableFrom(@Nonnull YTDBSchemaClass classInstance) {
    var entity = entityReadPreprocessing();
    var schemaClassImpl = (YTDBSchemaClassImpl) classInstance;

    return entity.iAssignableFrom(schemaClassImpl.getRawEntity());
  }

  @Override
  public boolean isAssignableFrom(@Nonnull String className) {
    var entity = entityReadPreprocessing();

    return entity.iAssignableFrom(className);
  }

  @Override
  public String customProperty(@Nonnull String propertyName) {
    var entity = entityReadPreprocessing();
    return entity.getCustomProperty(propertyName);
  }

  @Override
  public void customProperty(@Nonnull String propertyName, @Nullable String propertyValue) {
    var entity = entityWritePreprocessing();
    entity.setCustomProperty(propertyName, propertyValue);
  }

  @Override
  public void removeCustomProperty(@Nonnull String propertyName) {
    var entity = entityWritePreprocessing();
    entity.removeCustomProperty(propertyName);
  }

  @Override
  public void clearCustomProperties() {
    var entity = entityWritePreprocessing();
    entity.clearCustomProperties();
  }

  @Nonnull
  @Override
  public Iterator<String> customPropertyNames() {
    var entity = entityReadPreprocessing();
    return entity.getCustomPropertiesNames().iterator();
  }

  @Override
  public boolean hasCollectionId(int collectionId) {
    var entity = entityReadPreprocessing();
    return entity.hasCollectionId(collectionId);
  }

  @Override
  public boolean hasPolymorphicCollectionId(int collectionId) {
    var entity = entityReadPreprocessing();
    return entity.hasPolymorphicCollectionId(collectionId);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaProperty> declaredProperty(String... name) {
    var entity = entityReadPreprocessing();
    var declaredProperties = entity.getDeclaredProperties(name);

    return mapToDomainPropertyIterator(declaredProperties);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaProperty> schemaProperty(@Nonnull String... name) {
    var entity = entityReadPreprocessing();
    var schemaProperties = entity.getSchemaProperties(name);
    return mapToDomainPropertyIterator(schemaProperties.iterator());
  }

  @Override
  public @Nonnull YTDBSchemaProperty createDeclaredProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType) {
    var entity = entityWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var propertyEntity = session.newSchemaPropertyEntity(propertyName,
        PropertyTypeInternal.convertFromPublicType(propertyType));
    entity.addSchemaProperty(propertyEntity);

    return new YTDBSchemaPropertyImpl(graph, propertyEntity);
  }

  public void addSchemaProperty(@Nonnull YTDBSchemaProperty property) {
    var entity = entityWritePreprocessing();
    var schemaImpl = (YTDBSchemaPropertyImpl) property;

    entity.addSchemaProperty(schemaImpl.getRawEntity());
  }

  public void removeSchemaProperty(@Nonnull YTDBSchemaProperty property) {
    var entity = entityWritePreprocessing();
    var schemaImpl = (YTDBSchemaPropertyImpl) property;
    entity.removeSchemaProperty(schemaImpl.getRawEntity());
  }

  @Override
  public @Nonnull YTDBSchemaProperty createDeclaredProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull YTDBSchemaClass linkedClass) {
    var entity = entityWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var linkedClassImpl = (YTDBSchemaClassImpl) linkedClass;
    var propertyEntity = session.newSchemaPropertyEntity(propertyName,
        PropertyTypeInternal.convertFromPublicType(propertyType));
    propertyEntity.setLinkedClass(linkedClassImpl.getRawEntity());
    entity.addSchemaProperty(propertyEntity);

    return new YTDBSchemaPropertyImpl(graph, propertyEntity);
  }

  @Override
  public @Nonnull YTDBSchemaProperty createDeclaredProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull PropertyType linkedType) {
    var entity = entityWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var propertyEntity = session.newSchemaPropertyEntity(propertyName,
        PropertyTypeInternal.convertFromPublicType(propertyType));
    propertyEntity.setLinkedPropertyType(PropertyTypeInternal.convertFromPublicType(linkedType));
    entity.addSchemaProperty(propertyEntity);

    return new YTDBSchemaPropertyImpl(graph, propertyEntity);
  }

  @Override
  public void dropDeclaredProperty(@Nonnull String propertyName) {
    var entity = entityWritePreprocessing();
    entity.removeSchemaProperty(propertyName);
  }

  @Override
  public boolean existsSchemaProperty(@Nonnull String propertyName) {
    var entity = entityReadPreprocessing();
    return entity.existsSchemaProperty(propertyName);
  }

  @Override
  public YTDBSchemaIndex createIndex(@Nonnull String indexName, IndexType indexType,
      String... propertyNames) {
    if (propertyNames == null || propertyNames.length == 0) {
      throw new IllegalArgumentException("Property names cannot be null or empty");
    }

    var entity = entityWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var indexEntity = SchemaManager.createIndex(session, entity, indexName,
        ImmutableSchema.IndexType.fromPublicIndexType(indexType),
        propertyNames);

    return new YTDBSchemaIndexImpl(graph, indexEntity);
  }

  @Override
  public YTDBSchemaIndex createIndex(@Nonnull String indexName, IndexType indexType,
      boolean ignoreNulls, String... propertyNames) {
    if (propertyNames == null || propertyNames.length == 0) {
      throw new IllegalArgumentException("Property names cannot be null or empty");
    }

    var entity = entityWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var indexEntity = SchemaManager.createIndex(session, entity, indexName,
        ImmutableSchema.IndexType.fromPublicIndexType(indexType),
        propertyNames);

    indexEntity.setNullValuesIgnored(ignoreNulls);

    return new YTDBSchemaIndexImpl(graph, indexEntity);
  }

  @Override
  public YTDBSchemaIndex createIndex(@Nonnull String indexName, IndexType indexType,
      String[] propertyNames, IndexBy[] indexBy) {
    if (propertyNames == null || propertyNames.length == 0) {
      throw new IllegalArgumentException("Property names cannot be null or empty");
    }

    var entity = entityWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var indexEntity = SchemaManager.createIndex(session, entity, indexName,
        ImmutableSchema.IndexType.fromPublicIndexType(indexType), null,
        propertyNames, indexBy);

    return new YTDBSchemaIndexImpl(graph, indexEntity);
  }

  @Override
  public YTDBSchemaIndex createIndex(@Nonnull String indexName, IndexType indexType,
      boolean ignoreNulls, String[] propertyNames, IndexBy[] indexBy) {
    if (propertyNames == null || propertyNames.length == 0) {
      throw new IllegalArgumentException("Property names cannot be null or empty");
    }

    var entity = entityWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var indexEntity = SchemaManager.createIndex(session, entity, indexName,
        ImmutableSchema.IndexType.fromPublicIndexType(indexType), null,
        propertyNames, indexBy);
    indexEntity.setNullValuesIgnored(ignoreNulls);

    return new YTDBSchemaIndexImpl(graph, indexEntity);
  }

  @Override
  public Iterator<YTDBSchemaIndex> indexes(String... indexName) {
    var entity = entityReadPreprocessing();

    if (indexName == null || indexName.length == 0) {
      return YTDBIteratorUtils.map(entity.getIndexes(),
          indexEntity -> new YTDBSchemaIndexImpl(graph, indexEntity));
    }

    return YTDBIteratorUtils.map(
        YTDBIteratorUtils.filter(entity.getIndexes(),
            indexEntity -> ArrayUtils.contains(indexName, indexEntity.getName())),
        indexEntity -> new YTDBSchemaIndexImpl(graph, indexEntity)
    );
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBPTokenInternal[] pTokens() {
    return YTDBSchemaClassPTokenInternal.values();
  }

  @Override
  public YTDBPTokenInternal<YTDBDomainVertexAbstract<?>> pToken(String name) {
    //noinspection unchecked,rawtypes
    return (YTDBPTokenInternal) YTDBSchemaClassPTokenInternal.valueOf(name);
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBOutTokenInternal[] outTokens() {
    return YTDBSchemaClassOutTokenInternal.values();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBInTokenInternal[] inTokens() {
    return YTDBSchemaClassInTokenInternal.values();
  }

  @Override
  public YTDBInTokenInternal<YTDBDomainVertexAbstract<?>> inToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBInTokenInternal) YTDBSchemaClassInTokenInternal.valueOf(label);
  }

  @Override
  public YTDBOutTokenInternal<YTDBDomainVertexAbstract<?>> outToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBOutTokenInternal) YTDBSchemaClassOutTokenInternal.valueOf(label);
  }
}
