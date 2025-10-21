package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexBy;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainVertexAbstract;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;

public class YTDBSchemaPropertyImpl extends
    YTDBDomainVertexSchemaAbstract<SchemaPropertyEntity> implements YTDBSchemaProperty {

  public YTDBSchemaPropertyImpl(YTDBGraphInternal graph, Identifiable identifiable) {
    super(graph, identifiable);
  }

  @Override
  public @Nonnull String name() {
    var entity = entityReadPreprocessing();
    return entity.getName();
  }

  @Override
  public void name(@Nonnull String propertyName) {
    var entity = entityWritePreprocessing();
    entity.setName(propertyName);
  }

  @Override
  public @Nonnull String fullName() {
    var entity = entityReadPreprocessing();
    return entity.getFullName();
  }

  @Override
  public @Nonnull PropertyType type() {
    var entity = entityReadPreprocessing();
    return entity.getPropertyType().getPublicPropertyType();
  }

  @Override
  public void type(@Nonnull PropertyType propertyType) {
    var entity = entityWritePreprocessing();
    entity.setPropertyType(PropertyTypeInternal.convertFromPublicType(propertyType));
  }


  @Override
  public YTDBSchemaClass linkedClass() {
    var entity = entityReadPreprocessing();
    var linkedClass = entity.getLinkedClass();
    return new YTDBSchemaClassImpl(graph, linkedClass);
  }

  @Override
  public void linkedClass(YTDBSchemaClass linkedClass) {
    var entity = entityWritePreprocessing();
    if (linkedClass == null) {
      entity.setLinkedClass(null);
      return;
    }

    var linkedClassImpl = (YTDBSchemaClassImpl) linkedClass;
    var linkedClassEntity = linkedClassImpl.getRawEntity();
    entity.setLinkedClass(linkedClassEntity);
  }

  @Override
  public PropertyType linkedType() {
    var entity = entityReadPreprocessing();
    return entity.getLinkedPropertyType().getPublicPropertyType();
  }

  @Override
  public void linkedType(@Nullable PropertyType linkedPropertyType) {
    var entity = entityWritePreprocessing();
    entity.setLinkedPropertyType(PropertyTypeInternal.convertFromPublicType(linkedPropertyType));
  }


  @Override
  public boolean notNull() {
    var entity = entityReadPreprocessing();
    return entity.isNotNull();
  }

  @Override
  public void notNull(boolean notNull) {
    var entity = entityWritePreprocessing();
    entity.setNotNull(notNull);
  }

  @Override
  public String collateName() {
    var entity = entityReadPreprocessing();
    return entity.getCollate();
  }

  @Override
  public void collateName(String collateName) {
    var entity = entityWritePreprocessing();
    entity.setCollateName(collateName);
  }

  @Override
  public boolean mandatory() {
    var entity = entityReadPreprocessing();
    return entity.isMandatory();
  }

  @Override
  public void mandatory(boolean mandatory) {
    var entity = entityWritePreprocessing();
    entity.setMandatory(mandatory);
  }

  @Override
  public boolean readonly() {
    var entity = entityReadPreprocessing();
    return entity.isReadonly();
  }

  @Override
  public void readonly(boolean readonly) {
    var entity = entityWritePreprocessing();
    entity.setReadonly(readonly);
  }

  @Override
  public String min() {
    var entity = entityReadPreprocessing();
    return entity.getMin();
  }

  @Override
  public void min(String min) {
    var entity = entityWritePreprocessing();
    entity.setMin(min);
  }

  @Override
  public String max() {
    var entity = entityReadPreprocessing();
    return entity.getMax();
  }

  @Override
  public void max(String max) {
    var entity = entityWritePreprocessing();
    entity.setMax(max);
  }

  @Override
  public String defaultValue() {
    var entity = entityReadPreprocessing();
    return entity.getDefaultValue();
  }

  @Override
  public void defaultValue(String defaultValue) {
    var entity = entityWritePreprocessing();
    entity.setDefaultValue(defaultValue);
  }

  @Override
  public String regexp() {
    var entity = entityReadPreprocessing();
    return entity.getRegexp();
  }


  @Override
  public void regexp(String regexp) {
    var entity = entityWritePreprocessing();
    entity.setRegexp(regexp);
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

  @Override
  public Iterator<String> customPropertyNames() {
    var entity = entityReadPreprocessing();
    return entity.getCustomPropertyNames().iterator();
  }

  @Nonnull
  @Override
  public YTDBSchemaClass declaringClass() {
    var entity = entityReadPreprocessing();
    return new YTDBSchemaClassImpl(graph, entity.getDeclaringClass());
  }

  @Override
  public @Nonnull String description() {
    var entity = entityReadPreprocessing();
    return entity.getDescription();
  }

  @Override
  public void description(String description) {
    var entity = entityWritePreprocessing();
    entity.setDescription(description);
  }

  @Override
  public YTDBSchemaIndex createIndex(
      String indexName, YTDBSchemaIndex.IndexType indexType) {
    var entity = entityWritePreprocessing();

    var session = graph.tx().getDatabaseSession();
    var declaringClass = entity.getDeclaringClass();

    var indexEntity = session.newSchemaIndexEntity();
    indexEntity.setName(indexName);
    indexEntity.setIndexType(IndexType.fromPublicIndexType(indexType));
    indexEntity.setClassToIndex(declaringClass);
    indexEntity.addClassPropertyToIndex(entity);

    return new YTDBSchemaIndexImpl(graph, indexEntity);
  }

  @Override
  public YTDBSchemaIndex createIndex(String indexName, YTDBSchemaIndex.IndexType indexType,
      IndexBy indexBy) {
    return createIndex(indexName, indexType, indexBy, false);
  }

  @Override
  public YTDBSchemaIndex createIndex(String indexName, YTDBSchemaIndex.IndexType indexType,
      IndexBy indexBy, boolean ignoreNulls) {
    var entity = entityWritePreprocessing();

    var session = graph.tx().getDatabaseSession();
    var declaringClass = entity.getDeclaringClass();

    var indexEntity = session.newSchemaIndexEntity();
    indexEntity.setName(indexName);
    indexEntity.setIndexType(IndexType.fromPublicIndexType(indexType));
    indexEntity.setClassToIndex(declaringClass);
    indexEntity.addClassPropertyToIndex(entity,
        SchemaIndexEntity.IndexBy.fromPublicIndexBy(indexBy));
    indexEntity.setNullValuesIgnored(ignoreNulls);

    return new YTDBSchemaIndexImpl(graph, indexEntity);
  }

  @Override
  public Iterator<YTDBSchemaIndex> indexes(String... indexName) {
    var entity = entityReadPreprocessing();
    var indexes = entity.getIndexes();

    if (indexes != null && indexName.length > 0) {
      return YTDBIteratorUtils.map(
          YTDBIteratorUtils.filter(indexes,
              indexEntity -> ArrayUtils.contains(indexName, indexEntity.getName())),
          indexEntity -> new YTDBSchemaIndexImpl(graph, indexEntity)
      );
    } else {
      return YTDBIteratorUtils.map(
          indexes, indexEntity -> new YTDBSchemaIndexImpl(graph, indexEntity)
      );
    }
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBPTokenInternal[] pTokens() {
    return YTDBSchemaPropertyPTokenInternal.values();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBOutTokenInternal[] outTokens() {
    return YTDBSchemaPropertyOutTokenInternal.values();
  }

  @Override
  protected YTDBPTokenInternal<YTDBDomainVertexAbstract<?>> pToken(String name) {
    //noinspection unchecked,rawtypes
    return (YTDBPTokenInternal) YTDBSchemaClassPTokenInternal.valueOf(name);
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBInTokenInternal[] inTokens() {
    return YTDBSchemaPropertyInTokenInternal.values();
  }

  @Override
  public YTDBInTokenInternal<YTDBDomainVertexAbstract<?>> inToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBInTokenInternal) YTDBSchemaPropertyInTokenInternal.valueOf(label);
  }

  @Override
  public YTDBOutTokenInternal<YTDBDomainVertexAbstract<?>> outToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBOutTokenInternal) YTDBSchemaPropertyOutTokenInternal.valueOf(label);
  }

}
