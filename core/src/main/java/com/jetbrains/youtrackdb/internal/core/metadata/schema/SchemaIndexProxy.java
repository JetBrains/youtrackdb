package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.ValueModifier;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
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
    var propertyNames = new ArrayList<String>();
    var collates = new ArrayList<Collate>();
    var indexType = entity.getIndexType();

    var propertiesToIndex = entity.getClassPropertiesToIndexWithModifiers();
    while (propertiesToIndex.hasNext()) {
      var propertyModifierPair = propertiesToIndex.next();
      var property = propertyModifierPair.first();
      var modifier = propertyModifierPair.second();
      if (modifier == ValueModifier.NONE) {
        propertyNames.add(property.getName());
      } else {
        propertyNames.add(property.getName() + " by  " + modifier.name());
      }

      var collate = property.getCollate();
      if (collate == null) {
        collates.add(null);
      } else {
        var collateInstance = SQLEngine.getCollate(collate);
        collates.add(collateInstance);
      }
    }

    var keyTypes = entity.getKeyTypes();
    indexDefinition = IndexDefinitionFactory.createIndexDefinition(schemaClass, propertyNames,
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
  public INDEX_TYPE getType() {
    return entity.getIndexType();
  }
}
