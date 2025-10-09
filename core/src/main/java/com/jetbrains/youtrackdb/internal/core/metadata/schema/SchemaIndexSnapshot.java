package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;

public class SchemaIndexSnapshot implements SchemaIndex {

  private final IndexDefinition indexDefinition;
  private final int id;
  private final String name;
  private final IndexType type;

  public SchemaIndexSnapshot(SchemaIndexEntity entity) {
    name = entity.getName();
    type = entity.getIndexType();
    id = entity.getIndexId();

    var classToIndex = entity.getClassToIndex();
    var schemaClass = new SchemaClassProxy(classToIndex, entity.getSession());
    var indexType = entity.getIndexType();
    var indexBys = entity.getIndexBys();
    var keyTypes = entity.getKeyTypes();

    var propertyNames = YTDBIteratorUtils.list(
        YTDBIteratorUtils.map(entity.getPropertiesToIndex(), SchemaPropertyEntity::getName)
    );
    var collates = YTDBIteratorUtils.list(
        YTDBIteratorUtils.map(
            YTDBIteratorUtils.map(entity.getPropertiesToIndex(), SchemaPropertyEntity::getCollate),
            SQLEngine::getCollate
        )
    );

    indexDefinition = IndexDefinitionFactory.createIndexDefinition(schemaClass, propertyNames,
        indexBys,
        keyTypes,
        collates,
        indexType.name());
  }

  @Override
  public IndexDefinition getIndexDefinition() {
    return indexDefinition;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public IndexType getType() {
    return type;
  }
}
