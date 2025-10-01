package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.ValueModifier;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.ArrayList;

public class SchemaIndexSnapshot implements SchemaIndex {

  private final IndexDefinition indexDefinition;
  private final int id;
  private final String name;
  private final INDEX_TYPE type;

  public SchemaIndexSnapshot(SchemaIndexEntity entity) {
    name = entity.getName();
    type = entity.getIndexType();
    id = entity.getIndexId();

    var classToIndex = entity.getClassToIndex();
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
  public INDEX_TYPE getType() {
    return type;
  }
}
