package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaPropertyPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;

import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import java.util.Iterator;
import javax.annotation.Nullable;

public class SchemaManager {
  public static final String SCHEMA_CLASS_NAME_INDEX = "$SchemaClassNameIndex";
  public static final String GLOBAL_PROPERTY_INDEX = "$GlobalPropertyIndex";

  @Nullable
  public static RID getSchemaClassEntityRID(DatabaseSessionEmbedded session, String className) {
    var index = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
    var result = index.getRids(session, className).findFirst();

    return result.orElse(null);
  }

  @Nullable
  public static SchemaClassEntity getSchemaClassEntity(DatabaseSessionEmbedded session,
      String className) {
    var rid = getSchemaClassEntityRID(session, className);
    if (rid == null) {
      return null;
    }

    return session.load(rid);
  }

  public static Iterator<SchemaClassEntity> getSchemaClassEntities(DatabaseSessionEmbedded session) {
    var collectionId = session.getCollectionIdByName(
        MetadataDefault.COLLECTION_NAME_SCHEMA_PROPERTY);
    return new RecordIteratorCollection<>(session, collectionId, true);
  }
  
  public static int getSchemaClassEntitesCount(DatabaseSessionEmbedded session) {
    
  }

  @Nullable
  public static Character checkPropertyNameIfValid(String iName) {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    iName = iName.trim();

    final var nameSize = iName.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = iName.charAt(i);
      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '=')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  public static void onSchemaPropertyAfterCreate(DatabaseSessionEmbedded session,
      SchemaPropertyEntity schemaPropertyEntity) {
    try {
      schemaPropertyEntity.validate();
    } catch (ValidationException e) {
      //not in valid state wait till it will be fully updated
      return;
    }

    var propertyType = schemaPropertyEntity.getPropertyType();
    var globalPropertyId = findOrCreateGlobalProperty(session, schemaPropertyEntity.getName(),
        PropertyTypeInternal.convertFromPublicType(propertyType));
    schemaPropertyEntity.setGlobalPropertyLink(globalPropertyId);
  }

  public static void onSchemaPropertyBeforeDelete(DatabaseSessionEmbedded session,
      SchemaPropertyEntity schemaPropertyEntity) {
    var declaringClass = schemaPropertyEntity.getDeclaringClass();

    //class is deleted nothing to do
    if (declaringClass == null) {
      return;
    }
    //rollback tx if it is not valid
    schemaPropertyEntity.validate();

    firePropertyDeleteMigration(session, declaringClass.getName(),
        schemaPropertyEntity.getName());
  }

  public static void onSchemaPropertyAfterUpdate(DatabaseSessionEmbedded session,
      SchemaPropertyEntity schemaPropertyEntity) {
    var dirtyProperties = schemaPropertyEntity.getDirtyPropertiesBetweenCallbacks();

    if (dirtyProperties.contains(YTDBSchemaPropertyPTokenInternal.name.name())) {
      try {
        schemaPropertyEntity.validate();
      } catch (ValidationException e) {
        //not in valid state wait till it will be fully updated
        return;
      }

      var originalName = schemaPropertyEntity.getOriginalValue(
          YTDBSchemaPropertyPTokenInternal.name.name()).toString();
      var newName = schemaPropertyEntity.getName();

      if (!originalName.equals(newName)) {
        var declaringClass = schemaPropertyEntity.getDeclaringClass();
        var type = PropertyTypeInternal.convertFromPublicType(
            schemaPropertyEntity.getPropertyType());
        var globalPropertyId = findOrCreateGlobalProperty(session, newName, type);
        schemaPropertyEntity.setGlobalPropertyLink(globalPropertyId);

        firePropertyNameMigration(session, declaringClass.getName(), originalName, newName, type);
      }
    } else if (dirtyProperties.contains(YTDBSchemaPropertyPTokenInternal.type.name())) {
      try {
        schemaPropertyEntity.validate();
      } catch (ValidationException e) {
        //not in valid state wait till it will be fully updated
        return;
      }

      var newPropertyType = schemaPropertyEntity.getPropertyType();

      var globalPropertyId = findOrCreateGlobalProperty(session, schemaPropertyEntity.getName(),
          PropertyTypeInternal.convertFromPublicType(newPropertyType));
      schemaPropertyEntity.setGlobalPropertyLink(globalPropertyId);

      var declaringClass = schemaPropertyEntity.getDeclaringClass();
      firePropertyTypeMigration(session, declaringClass.getName(), schemaPropertyEntity.getName(),
          PropertyTypeInternal.convertFromPublicType(newPropertyType));
    }
  }

  private static void firePropertyNameMigration(
      final DatabaseSessionInternal database,
      final String declaringClass,
      final String propertyName,
      final String newPropertyName,
      final PropertyTypeInternal type) {
    var classIterator = new RecordIteratorClass(database, declaringClass,
        false, true);

    while (classIterator.hasNext()) {
      var entity = classIterator.next();
      var oldValue = entity.getPropertyInternal(propertyName);

      if (oldValue != null) {
        entity.setPropertyInternal(newPropertyName, entity.getPropertyInternal(propertyName),
            type);
      }
    }
  }

  private static void firePropertyDeleteMigration(
      final DatabaseSessionInternal database,
      final String declaringClass,
      final String propertyName) {
    var classIterator = new RecordIteratorClass(database, declaringClass,
        false, true);
    while (classIterator.hasNext()) {
      var entity = classIterator.next();
      entity.removePropertyInternal(propertyName);
    }
  }

  private static void firePropertyTypeMigration(
      final DatabaseSessionInternal database, final String declaringClass,
      final String propertyName,
      final PropertyTypeInternal type) {
    var classIterator = new RecordIteratorClass(database, declaringClass,
        false, true);

    while (classIterator.hasNext()) {
      var entity = classIterator.next();
      var propertyType = entity.getPropertyType(propertyName);

      if (propertyType == null) {
        continue;
      }

      var propertyTypeInternal = PropertyTypeInternal.getTypeByValue(propertyType);
      if (propertyTypeInternal != type) {
        entity.setPropertyInternal(propertyName, entity.getPropertyInternal(propertyName),
            type);
      }
    }
  }

  private static RID findOrCreateGlobalProperty(DatabaseSessionEmbedded session, String name,
      PropertyTypeInternal type) {
    var index = session.getIndex(GLOBAL_PROPERTY_INDEX);
    var key = new CompositeKey(name, type.name());

    var result = index.getRids(session, key).findFirst();
    if (result.isPresent()) {
      return result.get();
    }

    var entity = session.newSchemaGlobalPropertyEntity(name, type);
    index.put(session.getActiveTransaction(), key, entity);

    return entity.getIdentity();
  }
}
