package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import java.util.ArrayList;

public final class SchemaIndexProxy implements SchemaIndex {

  private final SchemaIndexEntity entity;
  private IndexDefinition indexDefinition;

  public SchemaIndexProxy(SchemaIndexEntity entity) {
    this.entity = entity;
  }

  @Override
  public IndexDefinition getIndexDefinition() {
    var classToIndex = entity.getClassToIndex();
    if (classToIndex == null) {
      throw new DatabaseException(entity.getSession(), "classToIndex is null");
    }

    if (indexDefinition != null) {
      return indexDefinition;
    }

    var schemaClass = new SchemaClassProxy(classToIndex, entity.getSession());
    var collates = new ArrayList<Collate>();
    var indexType = entity.getIndexType();

    var propertyNames = YTDBIteratorUtils.list(YTDBIteratorUtils.map(entity.getPropertiesToIndex(),
        SchemaPropertyEntity::getName));
    var indexBys = entity.getIndexBys();

    var keyTypes = entity.getKeyTypes();
    indexDefinition = IndexDefinitionFactory.createIndexDefinition(schemaClass, propertyNames,
        indexBys,
        keyTypes,
        collates, indexType.name());
    return indexDefinition;
  }

  @Override
  public int getId() {
    var id = entity.getIndexId();
    if (id == null) {
      throw new DatabaseException(entity.getSession(), "Index id is null");
    }

    return id;
  }

  @Override
  public String getName() {
    return entity.getName();
  }

  @Override
  public IndexType getType() {
    return entity.getIndexType();
  }
}
