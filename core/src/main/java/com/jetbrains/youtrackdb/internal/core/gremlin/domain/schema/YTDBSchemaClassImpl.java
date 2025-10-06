package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainVertexAbstract;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaClassEntity;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YTDBSchemaClassImpl extends
    YTDBDomainVertexSchemaAbstract<SchemaClassEntity> implements
    YTDBSchemaClass {

  public YTDBSchemaClassImpl(YTDBGraphInternal graph, Identifiable identifiable) {
    super(graph, identifiable);
  }

  @Override
  public boolean abstractClass() {
    var entity = propertyReadPreprocessing();
    return entity.isAbstractClass();
  }

  @Override
  public void abstractClass(boolean value) {
    var entity = propertyWritePreprocessing();
    entity.setAbstractClass(value);
  }

  @Override
  public boolean strictMode() {
    var entity = propertyReadPreprocessing();
    return entity.isStrictMode();
  }

  @Override
  public void strictMode(boolean mode) {
    var entity = propertyWritePreprocessing();
    entity.setStrictMode(mode);
  }

  @Override
  public boolean hasParentClasses() {
    var entity = propertyReadPreprocessing();
    return entity.hasParentClasses();
  }

  @Nonnull
  @Override
  public String name() {
    var entity = propertyReadPreprocessing();
    return entity.getName();
  }

  @Override
  public void name(@Nonnull String name) {
    var entity = propertyWritePreprocessing();
    entity.setName(name);
  }

  @Override
  public @Nonnull String description() {
    var entity = propertyReadPreprocessing();
    return entity.getDescription();
  }

  @Override
  public void description(@Nonnull String description) {
    var entity = propertyWritePreprocessing();
    entity.setDescription(description);
  }

  @Override
  public @Nonnull int[] collectionIds() {
    var entity = propertyReadPreprocessing();
    var rids = entity.getCollectionIds();
    var ids = new int[rids.size()];
    for (var i = 0; i < ids.length; i++) {
      ids[i] = rids.get(i);
    }

    return ids;
  }

  @Override
  public @Nonnull int[] polymorphicCollectionIds() {
    var entity = propertyReadPreprocessing();
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
    var entity = propertyReadPreprocessing();
    return entity.isEdgeType();
  }

  @Override
  public boolean isVertexType() {
    var entity = propertyReadPreprocessing();
    return entity.isVertexType();
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> parentClasses() {
    var entity = propertyReadPreprocessing();
    var superClasses = entity.getParentClasses();
    return mapToDomainClassIterator(superClasses);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> childClasses() {
    var entity = propertyReadPreprocessing();
    var subClasses = entity.getChildClasses();

    return mapToDomainClassIterator(subClasses);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> descendants() {
    var entity = propertyReadPreprocessing();
    var descendants = entity.getDescendants();

    return mapToDomainClassIterator(descendants.iterator());
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> ascendants() {
    var entity = propertyReadPreprocessing();
    var ascendants = entity.getAscendants();

    return mapToDomainClassIterator(ascendants.iterator());
  }

  @Override
  public void addParentClass(@Nonnull YTDBSchemaClass parentClass) {
    var entity = propertyWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) parentClass;
    entity.addParentClass(classImpl.getRawEntity());
  }

  @Override
  public void removeParentClass(@Nonnull YTDBSchemaClass parentClass) {
    var entity = propertyWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) parentClass;
    entity.removeParentClass(classImpl.getRawEntity());
  }

  @Override
  public void addChildClass(@Nonnull YTDBSchemaClass childClass) {
    var entity = propertyWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) childClass;
    entity.addChildClass(classImpl.getRawEntity());
  }

  @Override
  public void removeChildClass(@Nonnull YTDBSchemaClass childClass) {
    var entity = propertyWritePreprocessing();
    var classImpl = (YTDBSchemaClassImpl) childClass;
    entity.removeChildClass(classImpl.getRawEntity());
  }

  @Override
  public boolean isChildOf(@Nonnull String className) {
    var entity = propertyReadPreprocessing();
    return entity.isChildOf(className);
  }

  @Override
  public boolean isChildOf(@Nonnull YTDBSchemaClass classInstance) {
    var schemaClassImpl = (YTDBSchemaClassImpl) classInstance;
    var entity = propertyReadPreprocessing();
    return entity.isChildOf(schemaClassImpl.getRawEntity());
  }

  @Override
  public boolean isParentOf(@Nonnull String className) {
    var entity = propertyReadPreprocessing();
    return entity.isChildOf(className);
  }

  @Override
  public boolean isParentOf(@Nonnull YTDBSchemaClass classInstance) {
    var entity = propertyReadPreprocessing();
    var schemaClassImpl = (YTDBSchemaClassImpl) classInstance;
    return entity.isParentOf(schemaClassImpl.getRawEntity());
  }

  @Override
  public String customProperty(@Nonnull String propertyName) {
    var entity = propertyReadPreprocessing();
    return entity.getCustomProperty(propertyName);
  }

  @Override
  public void customProperty(@Nonnull String propertyName, @Nullable String propertyValue) {
    var entity = propertyWritePreprocessing();
    entity.setCustomProperty(propertyName, propertyValue);
  }

  @Override
  public void removeCustomProperty(@Nonnull String propertyName) {
    var entity = propertyWritePreprocessing();
    entity.removeCustomProperty(propertyName);
  }

  @Override
  public void clearCustomProperties() {
    var entity = propertyWritePreprocessing();
    entity.clearCustomProperties();
  }

  @Nonnull
  @Override
  public Iterator<String> customPropertyNames() {
    var entity = propertyReadPreprocessing();
    return entity.getCustomPropertiesNames().iterator();
  }

  @Override
  public boolean hasCollectionId(int collectionId) {
    var entity = propertyReadPreprocessing();
    return entity.hasCollectionId(collectionId);
  }

  @Override
  public boolean hasPolymorphicCollectionId(int collectionId) {
    var entity = propertyReadPreprocessing();
    return entity.hasPolymorphicCollectionId(collectionId);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaProperty> declaredProperty(String... name) {
    var entity = propertyReadPreprocessing();
    var declaredProperties = entity.getDeclaredProperties(name);

    return mapToDomainPropertyIterator(declaredProperties);
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaProperty> schemaProperty(@Nonnull String... name) {
    var entity = propertyReadPreprocessing();
    var schemaProperties = entity.getSchemaProperties(name);
    return mapToDomainPropertyIterator(schemaProperties.iterator());
  }

  @Override
  public @Nonnull YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType) {
    var entity = propertyWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var propertyEntity = session.newSchemaPropertyEntity(propertyName,
        PropertyTypeInternal.convertFromPublicType(propertyType));
    entity.addSchemaProperty(propertyEntity);

    return new YTDBSchemaPropertyImpl(graph, propertyEntity);
  }

  public void addSchemaProperty(@Nonnull YTDBSchemaProperty property) {
    var entity = propertyWritePreprocessing();
    var schemaImpl = (YTDBSchemaPropertyImpl) property;

    entity.addSchemaProperty(schemaImpl.getRawEntity());
  }

  public void removeSchemaProperty(@Nonnull YTDBSchemaProperty property) {
    var entity = propertyWritePreprocessing();
    var schemaImpl = (YTDBSchemaPropertyImpl) property;
    entity.removeSchemaProperty(schemaImpl.getRawEntity());
  }

  @Override
  public @Nonnull YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull YTDBSchemaClass linkedClass) {
    var entity = propertyWritePreprocessing();
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
  public @Nonnull YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull PropertyType linkedType) {
    var entity = propertyWritePreprocessing();
    var tx = graph.tx();
    var session = tx.getDatabaseSession();

    var propertyEntity = session.newSchemaPropertyEntity(propertyName,
        PropertyTypeInternal.convertFromPublicType(propertyType));
    propertyEntity.setLinkedPropertyType(PropertyTypeInternal.convertFromPublicType(linkedType));
    entity.addSchemaProperty(propertyEntity);

    return new YTDBSchemaPropertyImpl(graph, propertyEntity);
  }

  @Override
  public void dropSchemaProperty(@Nonnull String propertyName) {
    var entity = propertyWritePreprocessing();
    entity.removeSchemaProperty(propertyName);
  }

  @Override
  public boolean existsSchemaProperty(@Nonnull String propertyName) {
    var entity = propertyReadPreprocessing();
    return entity.existsSchemaProperty(propertyName);
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
