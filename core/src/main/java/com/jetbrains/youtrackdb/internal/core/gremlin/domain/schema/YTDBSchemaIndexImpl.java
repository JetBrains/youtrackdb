package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainVertexAbstract;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YTDBSchemaIndexImpl extends
    YTDBDomainVertexSchemaAbstract<SchemaIndexEntity> implements YTDBSchemaIndex {
  public YTDBSchemaIndexImpl(YTDBGraphInternal graph, Identifiable identifiable) {
    super(graph, identifiable);
  }

  @Nonnull
  @Override
  public String name() {
    var entity = entityReadPreprocessing();
    return entity.getName();
  }

  @Override
  public void name(String name) {
    var entity = entityWritePreprocessing();
    entity.setName(name);
  }

  @Override
  public boolean nullValuesIgnored() {
    var entity = entityReadPreprocessing();
    return entity.isNullValuesIgnored();
  }

  @Nullable
  @Override
  public Map<String, ?> metadata() {
    var entity = entityReadPreprocessing();
    return entity.getMetadata();
  }

  @Nonnull
  @Override
  public PropertyType[] keyTypes() {
    var entity = entityReadPreprocessing();
    var keyTypes = entity.getKeyTypes();

    var publicKeyTypes = new PropertyType[keyTypes.size()];
    for (var i = 0; i < keyTypes.size(); i++) {
      publicKeyTypes[i] = keyTypes.get(i).getPublicPropertyType();
    }

    return publicKeyTypes;
  }

  @Override
  public IndexBy[] indexBy() {
    var entity = entityReadPreprocessing();
    var entityIndexBys = entity.getIndexBys();
    if (entityIndexBys == null) {
      return new IndexBy[0];
    }

    var indexBys = new IndexBy[entityIndexBys.size()];
    for (var i = 0; i < entityIndexBys.size(); i++) {
      var entityIndexBy = entityIndexBys.get(i);
      indexBys[i] = entityIndexBy.toPublicIndexBy();
    }

    return indexBys;
  }

  @Nonnull
  @Override
  public IndexType indexType() {
    var entity = entityReadPreprocessing();

    return entity.getIndexType().toPublicIndexType();
  }

  @Nullable
  @Override
  public YTDBSchemaClass classToIndex() {
    var entity = entityReadPreprocessing();

    var classToIndex = entity.getClassToIndex();
    if (classToIndex == null) {
      return null;
    }

    return new YTDBSchemaClassImpl(graph, classToIndex);
  }

  @Nonnull
  @Override
  public Iterator<YTDBSchemaProperty> propertiesToIndex() {
    var entity = entityReadPreprocessing();
    var propertiesIterator = entity.getPropertiesToIndex();
    var publicProperties = new ArrayList<YTDBSchemaProperty>();

    while (propertiesIterator.hasNext()) {
      var property = propertiesIterator.next();
      publicProperties.add(new YTDBSchemaPropertyImpl(graph, property));
    }

    return publicProperties.iterator();
  }

  @SuppressWarnings("rawtypes")
  @Override
  protected YTDBPTokenInternal[] pTokens() {
    return YTDBSchemaIndexPTokenInternal.values();
  }

  @Override
  protected YTDBPTokenInternal<YTDBDomainVertexAbstract<?>> pToken(String name) {
    //noinspection unchecked,rawtypes
    return (YTDBPTokenInternal) YTDBSchemaIndexPTokenInternal.valueOf(name);
  }

  @SuppressWarnings("rawtypes")
  @Override
  protected YTDBOutTokenInternal[] outTokens() {
    return YTDBSchemaIndexOutTokenInternal.values();
  }

  @Override
  protected YTDBOutTokenInternal<YTDBDomainVertexAbstract<?>> outToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBOutTokenInternal) YTDBSchemaIndexOutTokenInternal.valueOf(label);
  }

  @SuppressWarnings("rawtypes")
  @Override
  protected YTDBInTokenInternal[] inTokens() {
    return YTDBSchemaIndexInTokenInternal.values();
  }

  @Override
  protected YTDBInTokenInternal<YTDBDomainVertexAbstract<?>> inToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBInTokenInternal) YTDBSchemaIndexInTokenInternal.valueOf(label);
  }
}
