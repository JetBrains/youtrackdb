package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.StorageComponentId;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.jspecify.annotations.NonNull;

public final class SchemaManager {

  public static final int CURRENT_VERSION_NUMBER = 5;

  public static final String SCHEMA_CLASS_NAME_INDEX = "$SchemaClassNameIndex";
  public static final String SCHEMA_COLLECTION_ID_CLASS_INDEX = "$CollectionIdClassIndex";

  public static final String GLOBAL_PROPERTY_NAME_TYPE_INDEX = "$GlobalPropertyNameTypeIndex";
  public static final String GLOBAL_PROPERTY_ID_INDEX = "$GlobalPropertyNameTypeIndex";

  public static final String INDEX_NAME_INDEX = "$IndexNameIndex";

  public static final String COLLECTION_IDS_TO_FREE_TRANSACTION_KEY = "$SchemaManager:CollectionIdsToFree";
  public static final String REMOVED_CLASSES_TRANSACTION_KEY = "$SchemaManager:RemovedClasses";

  private static final Set<String> INTERNAL_CLASSES = new HashSet<>();

  static {
    INTERNAL_CLASSES.add(SecurityUserImpl.CLASS_NAME);
    INTERNAL_CLASSES.add(Role.CLASS_NAME);
    INTERNAL_CLASSES.add(SecurityPolicy.CLASS_NAME);
    INTERNAL_CLASSES.add(Function.CLASS_NAME);
    INTERNAL_CLASSES.add(DBSequence.CLASS_NAME);
    INTERNAL_CLASSES.add(ScheduledEvent.CLASS_NAME);
    INTERNAL_CLASSES.add(EntityImpl.DEFAULT_CLASS_NAME);
    INTERNAL_CLASSES.add(SchemaClass.VERTEX_CLASS_NAME);
    INTERNAL_CLASSES.add(SchemaClass.EDGE_CLASS_NAME);
  }

  public static void checkClassNameIfValid(String name) throws SchemaException {
    if (name == null) {
      throw new IllegalArgumentException("Name is null");
    }

    name = name.trim();
    final var nameSize = name.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = name.charAt(i);
      if (c == ':')
      // INVALID CHARACTER
      {
        throw new IllegalArgumentException("Invalid character ':' in class name '" + name + "'");
      }
    }

  }

  public static void checkPropertyNameIfValid(String iName) {
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
        throw new IllegalArgumentException("Invalid character ':' in property name '" + c + "'");
      }
    }
  }

  @Nullable
  public static Character checkIndexNameIfValid(String iName) {
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

  public static SchemaClassEntity createClass(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String className) {
    return session.newSchemaClassEntity(className);
  }

  public static SchemaClassEntity createClass(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull final String className,
      @Nonnull final SchemaClassEntity superClass) {
    var entity = session.newSchemaClassEntity(className);
    entity.addParentClass(superClass);

    return entity;
  }

  public static SchemaClassEntity createClass(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull String className,
      SchemaClassEntity... superClasses) {
    var entity = session.newSchemaClassEntity(className);

    for (var superClass : superClasses) {
      entity.addParentClass(superClass);
    }

    return entity;
  }

  public static SchemaClassEntity createAbstractClass(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String className) {
    var entity = session.newSchemaClassEntity(className);
    entity.setAbstractClass(true);
    return entity;
  }

  public static SchemaClassEntity createAbstractClass(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull final String className,
      @Nonnull final SchemaClassEntity superClass) {
    var entity = session.newSchemaClassEntity(className);
    entity.setAbstractClass(true);
    entity.addParentClass(superClass);
    return entity;
  }

  public static SchemaClassEntity createAbstractClass(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull String className,
      SchemaClassEntity... superClasses) {
    var entity = session.newSchemaClassEntity(className);
    entity.setAbstractClass(true);

    for (var superClass : superClasses) {
      entity.addParentClass(superClass);
    }

    return entity;
  }

  public static SchemaClassEntity getOrCreateClass(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String className) {
    var classEntity = getClass(session, className);

    if (classEntity == null) {
      classEntity = createClass(session, className);
    }

    return classEntity;
  }


  public static SchemaClassEntity getClassByCollectionId(@Nonnull DatabaseSessionEmbedded session,
      int collectionId) {

    var index = session.getIndex(SCHEMA_COLLECTION_ID_CLASS_INDEX);
    var result = index.getRids(session, collectionId).findFirst();

    return (SchemaClassEntity) result.map(session::load).orElse(null);
  }


  public static boolean existsClass(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String className) {
    var index = session.getIndex(SCHEMA_COLLECTION_ID_CLASS_INDEX);
    return index.getRids(session, className).findAny().isPresent();
  }

  @Nullable
  public static SchemaClassEntity getClass(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String className) {
    var index = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
    return (SchemaClassEntity) index.getRids(session, className).findFirst()
        .map(session::load).orElse(null);
  }

  @Nullable
  public static RID getClassLink(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String className) {
    var index = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
    return index.getRids(session, className).findFirst().orElse(null);
  }

  @Nonnull
  public static Collection<SchemaClassEntity> getClasses(@Nonnull DatabaseSessionEmbedded session) {
    var schemaClassIterator = new RecordIteratorCollection<SchemaClassEntity>(session,
        session.getMetadata().getCollectionSchemaClassId(), true);

    var classes = new ArrayList<SchemaClassEntity>();
    while (schemaClassIterator.hasNext()) {
      var cls = schemaClassIterator.next();
      classes.add(cls);
    }

    return classes;
  }

  @Nonnull
  public static SchemaPropertyEntity createProperty(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String propertyName,
      @Nonnull final PropertyTypeInternal type) {
    return session.newSchemaPropertyEntity(propertyName, type);
  }


  @Nonnull
  public static SchemaPropertyEntity createProperty(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull final String propertyName,
      @Nonnull final PropertyTypeInternal type,
      @Nonnull final SchemaClassEntity linkedClass) {
    var entity = session.newSchemaPropertyEntity(propertyName, type);
    entity.setLinkedClass(linkedClass);
    return entity;
  }


  @Nonnull
  public static SchemaPropertyEntity createProperty(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull final String propertyName,
      @Nonnull final PropertyTypeInternal type,
      @Nonnull final PropertyTypeInternal linkedType) {
    var entity = session.newSchemaPropertyEntity(propertyName, type);
    entity.setLinkedPropertyType(linkedType);

    return entity;
  }

  public static void createIndex(
      @Nonnull final DatabaseSessionEmbedded session,
      @Nonnull final SchemaClassEntity schemaClassEntity,
      @Nonnull final String indexName,
      @Nonnull final INDEX_TYPE indexType,
      final String... properties) {
    if (properties.length == 0) {
      throw new DatabaseException(session,
          "Index" + indexName + " must have at least one property.");
    }

    createIndexEntityInternal(session, schemaClassEntity, indexName, indexType, properties);
  }


  public static void createIndex(
      @Nonnull DatabaseSessionEmbedded session,
      @Nonnull final SchemaClassEntity schemaClassEntity,
      @Nonnull String indexName,
      @Nonnull INDEX_TYPE indexType,
      @Nonnull Map<String, Object> metadata,
      String... properties) {
    if (properties.length == 0) {
      throw new DatabaseException(session,
          "Index" + indexName + " must have at least one property");
    }

    var entity = createIndexEntityInternal(session, schemaClassEntity, indexName, indexType,
        properties);
    entity.setMetadata(metadata);
  }

  private static SchemaIndexEntity createIndexEntityInternal(
      @Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaClassEntity schemaClassEntity,
      @Nonnull String indexName, @Nonnull INDEX_TYPE indexType,
      String[] properties) {
    var entity = session.newSchemaIndexEntity();
    entity.setName(indexName);
    entity.setClassToIndex(schemaClassEntity);
    entity.setIndexType(indexType);

    var propertyEntities = schemaClassEntity.getSchemaProperties(properties);
    if (propertyEntities.size() != properties.length) {
      throw new DatabaseException(session,
          "Class " + schemaClassEntity.getName()
              + " must have all properties specified directly or through superclasses.");
    }

    for (var propertyEntity : propertyEntities) {
      entity.addClassPropertyToIndex(propertyEntity);
    }

    return entity;
  }

  public static boolean areIndexed(@Nonnull SchemaClassEntity schemaClassEntity,
      @Nonnull final Collection<String> properties) {
    if (properties.isEmpty()) {
      return true;
    }

    var propertyEntities = schemaClassEntity.getSchemaProperties(properties.toArray(new String[0]));
    if (propertyEntities.size() != properties.size()) {
      return false;
    }

    Set<String> propertyNames;
    if (properties instanceof Set<?>) {
      propertyNames = (Set<String>) properties;
    } else {
      propertyNames = new HashSet<>(properties);
    }

    for (var propertyEntity : propertyEntities) {
      var propertyInvolvedIndexes = propertyEntity.getInvolvedIndexes();
      while (propertyInvolvedIndexes.hasNext()) {
        var involvedIndex = propertyInvolvedIndexes.next();

        if (filterPropertyIndex(involvedIndex, propertyNames)) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean filterPropertyIndex(SchemaIndexEntity indexEntity,
      Set<String> propertyNames) {
    var propertiesToIndex = indexEntity.getClassProperties();
    var propertyIndex = 0;
    var containsRequiredProperties = true;

    while (propertiesToIndex.hasNext() && propertyIndex < propertyNames.size()) {
      var propertyToIndex = propertiesToIndex.next();
      var propertyToIndexName = propertyToIndex.getName();

      if (!propertyNames.contains(propertyToIndexName)) {
        containsRequiredProperties = false;
        break;
      }

      propertyIndex++;
    }

    containsRequiredProperties &= propertyIndex == propertyNames.size();

    return containsRequiredProperties;
  }

  public static boolean areIndexed(
      @Nonnull SchemaClassEntity schemaClassEntity, final String... properties) {
    return areIndexed(schemaClassEntity, Arrays.asList(properties));
  }

  public static Set<String> getInvolvedIndexNames(@Nonnull SchemaClassEntity schemaClassEntity,
      final String... properties) {
    return getInvolvedIndexNames(schemaClassEntity, Arrays.asList(properties));
  }

  public static Set<SchemaIndexEntity> getInvolvedIndexes(
      @Nonnull SchemaClassEntity schemaClassEntity, String... properties) {
    return getInvolvedIndexes(schemaClassEntity, Arrays.asList(properties));
  }

  public static Set<String> getInvolvedIndexNames(@Nonnull SchemaClassEntity schemaClassEntity,
      @Nonnull final Collection<String> properties) {
    return getInvolvedIndexes(schemaClassEntity, properties).stream()
        .map(SchemaIndexEntity::getName)
        .collect(Collectors.toSet());
  }

  @Nonnull
  public static Set<SchemaIndexEntity> getInvolvedIndexes(
      @Nonnull SchemaClassEntity schemaClassEntity,
      @Nonnull Collection<String> properties) {
    if (properties.isEmpty()) {
      return Collections.emptySet();
    }

    var propertyEntities = schemaClassEntity.getSchemaProperties(properties.toArray(new String[0]));
    if (propertyEntities.size() != properties.size()) {
      return Collections.emptySet();
    }

    Set<String> propertyNames;
    if (properties instanceof Set<?>) {
      propertyNames = (Set<String>) properties;
    } else {
      propertyNames = new HashSet<>(properties);
    }

    var involvedIndexes = new HashSet<SchemaIndexEntity>();
    for (var propertyEntity : propertyEntities) {
      var propertyInvolvedIndexes = propertyEntity.getInvolvedIndexes();
      filterPropertyIndexes(propertyInvolvedIndexes, propertyNames, involvedIndexes);
    }

    return involvedIndexes;
  }

  public static Set<String> getClassInvolvedIndexNames(@Nonnull SchemaClassEntity schemaClassEntity,
      @Nonnull final Collection<String> properties) {
    return getClassInvolvedIndexes(schemaClassEntity, properties).stream()
        .map(SchemaIndexEntity::getName)
        .collect(Collectors.toSet());
  }

  public static Set<SchemaIndexEntity> getClassInvolvedIndexes(
      @Nonnull SchemaClassEntity schemaClassEntity,
      @Nonnull Collection<String> properties) {
    if (properties.isEmpty()) {
      return Collections.emptySet();
    }

    var propertyEntities = schemaClassEntity.getSchemaProperties(properties.toArray(new String[0]));
    if (propertyEntities.size() != properties.size()) {
      return Collections.emptySet();
    }

    Set<String> propertyNames;
    if (properties instanceof Set<?>) {
      propertyNames = (Set<String>) properties;
    } else {
      propertyNames = new HashSet<>(properties);
    }

    var involvedIndexes = new HashSet<SchemaIndexEntity>();
    var classIndexes = schemaClassEntity.getInvolvedIndexes();
    filterPropertyIndexes(classIndexes, propertyNames, involvedIndexes);

    return involvedIndexes;
  }

  private static void filterPropertyIndexes(Iterator<SchemaIndexEntity> propertyInvolvedIndexes,
      Set<String> propertyNames, HashSet<SchemaIndexEntity> involvedIndexes) {
    while (propertyInvolvedIndexes.hasNext()) {
      var involvedIndex = propertyInvolvedIndexes.next();

      if (filterPropertyIndex(involvedIndex, propertyNames)) {
        involvedIndexes.add(involvedIndex);
      }
    }
  }

  public static Set<String> getClassInvolvedIndexNames(@Nonnull SchemaClassEntity schemaClassEntity,
      final String... properties) {
    return getClassInvolvedIndexNames(schemaClassEntity, Arrays.asList(properties));
  }

  @Nullable
  public static SchemaIndexEntity getClassIndex(@Nonnull SchemaClassEntity schemaClassEntity,
      @Nonnull final String name) {
    var session = schemaClassEntity.getSession();
    var indexNamesIndex = session.getIndex(INDEX_NAME_INDEX);

    var indexEntity = indexNamesIndex.getRids(session, name)
        .map(rid -> (SchemaIndexEntity) session.load(rid)).findFirst()
        .orElse(null);
    if (indexEntity == null) {
      return null;
    }

    if (name.equals(indexEntity.getName())) {
      return indexEntity;
    }

    return null;
  }

  public static Set<String> getClassIndexNames(@Nonnull SchemaClassEntity schemaClassEntity) {
    //noinspection unchecked
    return (Set<String>) IteratorUtils.asSet(
        IteratorUtils.map(schemaClassEntity.getInvolvedIndexes(), SchemaIndexEntity::getName));
  }

  public static Set<SchemaClassEntity> getClassIndexes(
      @Nonnull SchemaClassEntity schemaClassEntity) {
    //noinspection unchecked
    return (Set<SchemaClassEntity>) IteratorUtils.asSet(schemaClassEntity.getInvolvedIndexes());
  }

  @Nullable
  public static SchemaGlobalPropertyEntity getGlobalPropertyById(
      @Nonnull DatabaseSessionEmbedded session, int id) {
    var index = session.getIndex(GLOBAL_PROPERTY_ID_INDEX);
    var result = index.getRids(session, id).findFirst();
    if (result.isEmpty()) {
      return null;
    }

    var rid = result.get();
    return session.load(rid);
  }

  public static List<SchemaGlobalPropertyEntity> getGlobalProperties(
      @Nonnull DatabaseSessionEmbedded session) {
    var globalPropertyCollectionId = session.getMetadata().getCollectionGlobalPropertyId();

    var globalPropertyIterator = new RecordIteratorCollection<SchemaGlobalPropertyEntity>(session,
        globalPropertyCollectionId, true);
    return IteratorUtils.list(globalPropertyIterator);
  }

  static SchemaGlobalPropertyEntity findOrCreateGlobalProperty(
      @Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String name,
      @Nonnull final PropertyTypeInternal type) {
    var typeName = type.name();
    var namePropertyIndex = session.getIndex(GLOBAL_PROPERTY_NAME_TYPE_INDEX);
    var globalPropertyRid = namePropertyIndex.getRids(session, new CompositeKey(name, typeName))
        .findFirst();

    return globalPropertyRid.map(rid -> (SchemaGlobalPropertyEntity) session.load(rid))
        .orElseGet(() -> {
          var idIndex = session.getIndex(GLOBAL_PROPERTY_ID_INDEX);
          var nextId =
              idIndex.descStream(session).findFirst().map(pair -> (Integer) pair.getFirst())
                  .orElse(-1) + 1;
          var entity = session.newSchemaGlobalPropertyEntity(name, type, nextId);

          var activeTransaction = session.getActiveTransaction();
          idIndex.put(activeTransaction, nextId, entity.getIdentity());

          namePropertyIndex.put(activeTransaction, new CompositeKey(name, typeName),
              entity.getIdentity());

          return entity;
        });
  }

  public static void dropClass(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final String className) {
    var classEntity = getClass(session, className);
    if (classEntity == null) {
      throw new DatabaseException(session,
          "Class '" + className + "' was not found in current database");
    }

    classEntity.delete();
  }

  private static void dropClassIndexes(@Nonnull final SchemaClassEntity cls) {
    var involvedIndexes = cls.getInvolvedIndexes();
    while (involvedIndexes.hasNext()) {
      var indexEntity = involvedIndexes.next();
      indexEntity.delete();
    }
  }

  public static void onSchemaBeforeClassCreate(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaClassEntity entity) {
    int collectionsCount;

    var transaction = session.getActiveTransaction();
    var className = entity.getName();

    if (className != null && INTERNAL_CLASSES.contains(className)) {
      collectionsCount = 1;
    } else {
      if (entity.isAbstractClass()) {
        collectionsCount = 0;
      } else {
        collectionsCount = session.getStorageInfo().getConfiguration().getMinimumCollections();
      }
    }

    if (collectionsCount > 0) {
      var collectionIds = new ArrayList<StorageComponentId>(collectionsCount);

      for (var i = 0; i < collectionsCount; i++) {
        collectionIds.add(new StorageComponentId(transaction.generateTempStorageComponentId()));
      }

      entity.setCollectionIds(collectionIds);
    }

    try {
      entity.validate();
    } catch (ValidationException e) {
      //skip not valid entity, TX will be rolled back on commit
      return;
    }

    var schemaClassNameIndex = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
    schemaClassNameIndex.put(transaction, className, entity.getIdentity());

    if (collectionsCount > 0) {
      var collectionIdClassIndex = session.getIndex(SCHEMA_COLLECTION_ID_CLASS_INDEX);
      for (var collectionId : entity.getCollectionIds()) {
        collectionIdClassIndex.put(transaction, collectionId, entity.getIdentity());
      }
    }
  }

  public static void onSchemaClassBeforeUpdate(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaClassEntity entity) {
    try {
      entity.validate();
    } catch (ValidationException e) {
      //skip not valid entity, TX will be rolled back on commit
      return;
    }

    var dirtyFields = entity.getDirtyPropertiesBetweenCallbacks();
    var transaction = session.getActiveTransaction();

    var schemaClassNameProperty = YTDBSchemaClassPTokenInternal.name.name();
    if (dirtyFields.contains(schemaClassNameProperty)) {
      var originalValue = entity.getOriginalValue(schemaClassNameProperty);

      var className = entity.getName();

      var schemaClassNameIndex = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
      var activeTransaction = session.getActiveTransaction();
      schemaClassNameIndex.remove(activeTransaction, originalValue);
      schemaClassNameIndex.put(activeTransaction, className, entity.getIdentity());
    }

    if (dirtyFields.contains(YTDBSchemaClassPTokenInternal.abstractClass.name())) {
      if (entity.isAbstractClass()) {
        var collectionIds = entity.getCollectionIds();

        if (collectionIds != null) {
          for (var collectionId : collectionIds) {
            if (!collectionId.isTemporary()) {
              throw new DatabaseException(session,
                  "Cannot change abstract class to non-abstract as it "
                      + "is associated with existing collections");
            }
          }
        }

        entity.clearCollectionIds();
      } else {
        var collectionsCount = session.getStorageInfo().getConfiguration()
            .getMinimumCollections();
        var collectionIds = new ArrayList<StorageComponentId>(collectionsCount);

        for (var i = 0; i < collectionsCount; i++) {
          collectionIds.add(new StorageComponentId(transaction.generateTempStorageComponentId()));
        }

        entity.setCollectionIds(collectionIds);

        var collectionIdClassIndex = session.getIndex(SCHEMA_COLLECTION_ID_CLASS_INDEX);
        for (var collectionId : entity.getCollectionIds()) {
          collectionIdClassIndex.put(transaction, collectionId, entity.getIdentity());
        }
      }
    }
  }

  public static void onSchemaClassBeforeDelete(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaClassEntity entity) {
    entity.validate();

    if (entity.hasSubClasses()) {
      throw new DatabaseException(session,
          "Class " + entity.getName() + " has sub-classes and can not be deleted");
    }

    dropClassIndexes(entity);

    var activeTransaction = session.getActiveTransaction();
    var schemaClassNameIndex = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
    schemaClassNameIndex.remove(activeTransaction, entity.getName());

    var collectionIdClassIndex = session.getIndex(SCHEMA_COLLECTION_ID_CLASS_INDEX);
    for (var collectionId : entity.getCollectionIds()) {
      collectionIdClassIndex.remove(activeTransaction, collectionId);
    }
  }

  public static void onSchemaClassBeforeCommit(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaClassEntity entity, RecordOperation recordOperation) {
    var dirtyProperties = entity.getDirtyProperties();
    var transaction = session.getActiveTransaction();

    if (recordOperation.type == RecordOperation.CREATED
        || recordOperation.type == RecordOperation.UPDATED) {
      if (dirtyProperties.contains(YTDBSchemaClassOutTokenInternal.superClass.name())) {
        var superClasses = entity.getParentClasses();
        checkParametersConflict(superClasses);
      }
    } else if (recordOperation.type == RecordOperation.DELETED) {
      var collectionIds = entity.getCollectionIds();

      if (!collectionIds.isEmpty() && !collectionIds.getFirst().isTemporary()) {
        @SuppressWarnings("unchecked")
        var collectinIdsToFree = (List<Integer>) transaction.getCustomData(
            COLLECTION_IDS_TO_FREE_TRANSACTION_KEY);

        if (collectinIdsToFree == null) {
          collectinIdsToFree = new ArrayList<>();
          transaction.setCustomData(COLLECTION_IDS_TO_FREE_TRANSACTION_KEY, collectinIdsToFree);
        }

        for (var collectionId : collectionIds) {
          if (!collectionId.isTemporary()) {
            collectinIdsToFree.add(collectionId.getId());
          }
        }
      }

      @SuppressWarnings("unchecked")
      var removedClassNames = (List<String>) transaction.getCustomData(
          REMOVED_CLASSES_TRANSACTION_KEY);
      if (removedClassNames == null) {
        removedClassNames = new ArrayList<>();
        transaction.setCustomData(REMOVED_CLASSES_TRANSACTION_KEY, removedClassNames);
      }

      removedClassNames.add(entity.getName());
    }
  }

  public static void onSchemaClassAfterCommit(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaClassEntity entity, RecordOperation recordOperation) {
    if (recordOperation.type == RecordOperation.CREATED) {
      for (var it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext(); ) {
        //noinspection deprecation
        it.next().onCreateClass(session, entity);
      }

      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onCreateClass(session, new SchemaClassProxy(entity, session));
      }
    }
  }

  public static void onSchemaAfterCommit(@Nonnull DatabaseSessionEmbedded session) {
    var transaction = session.getActiveTransaction();
    var localCache = session.getLocalCache();

    @SuppressWarnings("unchecked")
    var collectionsToFree = (List<Integer>) transaction.getCustomData(
        COLLECTION_IDS_TO_FREE_TRANSACTION_KEY);
    if (collectionsToFree != null) {
      for (var collectionId : collectionsToFree) {
        localCache.freeCollection(collectionId);
      }
    }

    @SuppressWarnings("unchecked")
    var removedClassNames = (List<String>)
        transaction.getCustomData(REMOVED_CLASSES_TRANSACTION_KEY);
    if (removedClassNames != null) {
      for (var removedClassName : removedClassNames) {
        for (var it = YouTrackDBEnginesManager.instance()
            .getDbLifecycleListeners();
            it.hasNext(); ) {
          //noinspection deprecation
          it.next().onDropClass(session, removedClassName);
        }

        for (var oSessionListener : session.getListeners()) {
          oSessionListener.onDropClass(session, removedClassName);
        }
      }
    }
  }

  private static void checkParametersConflict
      (@Nonnull Iterator<SchemaClassEntity> superClasses) {
    final Map<String, SchemaPropertyEntity> cumulative = new HashMap<>();

    while (superClasses.hasNext()) {
      var superClass = superClasses.next();
      var superClassProperties = superClass.getSchemaProperties();

      for (var superClassProperty : superClassProperties) {
        var superClassPropertyName = superClassProperty.getName();

        if (cumulative.containsKey(superClassPropertyName)) {
          final var existingProperty = cumulative.get(superClassPropertyName);

          if (!existingProperty.getType().equals(superClassProperty.getType())) {
            throw new SchemaException(
                "Properties conflict detected: '"
                    + existingProperty
                    + "] vs ["
                    + superClassProperty
                    + "]");
          }
        } else {
          cumulative.put(superClassPropertyName, superClassProperty);
        }
      }
    }
  }

  public static int[] readableCollections(
      final DatabaseSessionInternal db, final int[] iCollectionIds, String className) {
    var listOfReadableIds = new IntArrayList();

    var all = true;
    for (var collectionId : iCollectionIds) {
      try {
        // This will exclude (filter out) any specific classes without explicit read permission.
        if (className != null) {
          db.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ, className);
        }

        final var collectionName = db.getCollectionNameById(collectionId);
        db.checkSecurity(Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_READ, collectionName);
        listOfReadableIds.add(collectionId);
      } catch (SecurityAccessException ignore) {
        all = false;
        // if the collection is inaccessible it's simply not processed in the list.add
      }
    }

    // JUST RETURN INPUT ARRAY (FASTER)
    if (all) {
      return iCollectionIds;
    }

    final var readableCollectionIds = new int[listOfReadableIds.size()];
    var index = 0;
    for (var i = 0; i < listOfReadableIds.size(); i++) {
      readableCollectionIds[index++] = listOfReadableIds.getInt(i);
    }

    return readableCollectionIds;
  }

  @Nullable
  public static String decodeClassName(String s) {
    if (s == null) {
      return null;
    }
    s = s.trim();
    if (!s.isEmpty() && s.charAt(0) == '`' && s.charAt(s.length() - 1) == '`') {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  private static void checkSecurityConstraintsForIndexCreate(
      DatabaseSessionEmbedded database, SchemaIndexEntity schemaIndexEntity) {
    var security = database.getSharedContext().getSecurity();

    var indexClass = schemaIndexEntity.getName();
    var indexedProperties = IteratorUtils.asSet(
        IteratorUtils.map(schemaIndexEntity.getClassProperties(),
            SchemaPropertyEntity::getName
        )
    );

    if (indexedProperties.size() == 1) {
      return;
    }

    Set<String> classesToCheck = new HashSet<>();
    classesToCheck.add(indexClass);

    var clazz = database.getMetadata().getFastImmutableSchema().getClass(indexClass);
    if (clazz == null) {
      return;
    }

    clazz.getAllSubclasses().forEach(x -> classesToCheck.add(x.getName()));
    clazz.getAllSuperClasses().forEach(x -> classesToCheck.add(x.getName()));
    var allFilteredProperties =
        security.getAllFilteredProperties(database);

    for (var className : classesToCheck) {
      Set<SecurityResourceProperty> indexedAndFilteredProperties;
      try (var stream = allFilteredProperties.stream()) {
        indexedAndFilteredProperties =
            stream
                .filter(x -> x.getClassName().equalsIgnoreCase(className))
                .filter(x -> indexedProperties.contains(x.getPropertyName()))
                .collect(Collectors.toSet());
      }

      if (!indexedAndFilteredProperties.isEmpty()) {
        try (var stream = indexedAndFilteredProperties.stream()) {
          throw new DatabaseException(database.getDatabaseName(),
              "Cannot create index on "
                  + indexClass
                  + "["
                  + (stream
                  .map(SecurityResourceProperty::getPropertyName)
                  .collect(Collectors.joining(", ")))
                  + " because of existing property security rules");
        }
      }
    }
  }

  public static void onSchemaBeforeIndexCreate(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaIndexEntity entity) {
    try {
      entity.validate();
    } catch (ValidationException e) {
      //skip not valid entity, TX will be rolled back on commit
      return;
    }

    var indexNameIndex = session.getIndex(INDEX_NAME_INDEX);
    var transaction = session.getActiveTransaction();
    indexNameIndex.put(transaction, entity.getName(), entity.getIdentity());
  }

  public static void onSchemaIndexBeforeCommit(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaIndexEntity entity) {
    checkSecurityConstraintsForIndexCreate(session, entity);
  }

  public static void onSchemaPropertyBeforeCreate(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaPropertyEntity entity) {
    updateGlobalPropertyLink(session, entity);
  }

  public static void onSchemaPropertyBeforeUpdate(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaPropertyEntity entity) {
    if (entity.isPropertyTypeChanged() || entity.isNameChanged()) {
      updateGlobalPropertyLink(session, entity);
    }
  }

  private static void updateGlobalPropertyLink(@NonNull DatabaseSessionEmbedded session,
      @NonNull SchemaPropertyEntity entity) {
    var globalProperty = findOrCreateGlobalProperty(session, entity.getName(),
        entity.getPropertyType());
    entity.setGlobalPropertyLink(globalProperty);
  }

  public static void onSchemaPropertyAfterCommit(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull SchemaPropertyEntity property, RecordOperation recordOperation) {
    if (recordOperation.type == RecordOperation.UPDATED) {
      if (property.isPropertyTypeChanged()
          || property.isLinkedTypeChanged() || property.isLinkedClassChanged()) {
        var declaringClass = property.getDeclaringClass();
        var linkedClass = property.getLinkedClass();
        String linkedClassName;
        if (linkedClass != null) {
          linkedClassName = linkedClass.getName();
        } else {
          linkedClassName = null;
        }

        var className = declaringClass.getName();
        var propertyName = property.getName();
        var type = property.getPropertyType();

        checkPersistentPropertiesOnTypeCompatibility(session, className,
            propertyName, type, property.getLinkedPropertyType(),
            linkedClassName);
        firePropertyTypeMigration(session, className, propertyName, type);
      }
    }
  }

  public enum INDEX_TYPE {
    UNIQUE,
    NOTUNIQUE,
    FULLTEXT,
    SPATIAL
  }

  private static void checkPersistentPropertyType(
      @Nonnull final DatabaseSessionEmbedded session,
      @Nonnull final String className,
      @Nonnull final String propertyName,
      @Nonnull final PropertyTypeInternal type,
      @Nullable String linkedClassName) {
    final var strictSQL = session.getStorageInfo().getConfiguration().isStrictSql();

    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(className, strictSQL));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName, strictSQL));
    builder.append(".type() not in [");

    final var cur = type.getCastable().iterator();
    while (cur.hasNext()) {
      builder.append('"').append(cur.next().name()).append('"');
      if (cur.hasNext()) {
        builder.append(",");
      }
    }
    builder
        .append("] and ")
        .append(getEscapedName(propertyName, strictSQL))
        .append(" is not null ");
    if (type.isMultiValue()) {
      builder
          .append(" and ")
          .append(getEscapedName(propertyName, strictSQL))
          .append(".size() <> 0 limit 1");
    }

    try (final var res = session.query(builder.toString())) {
      if (res.hasNext()) {
        throw new DatabaseException(session,
            "The database contains some schema-less data in the property '"
                + className
                + "."
                + propertyName
                + "' that is not compatible with the type "
                + type
                + ". Fix those records and change the schema again");
      }
    }

    if (linkedClassName != null) {
      checkAllLikedObjects(session, className, propertyName, type, linkedClassName);
    }
  }

  private static void checkAllLikedObjects(
      DatabaseSessionEmbedded session, String className, String propertyName,
      PropertyTypeInternal type,
      String linkedClassName) {
    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(className, true));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName, true)).append(" is not null ");
    if (type.isMultiValue()) {
      builder.append(" and ").append(getEscapedName(propertyName, true)).append(".size() > 0");
    }

    try (final var res = session.query(builder.toString())) {
      while (res.hasNext()) {
        var item = res.next();
        switch (type) {
          case EMBEDDEDLIST:
          case LINKLIST:
          case EMBEDDEDSET:
          case LINKSET:
            Collection<?> emb = item.getProperty(propertyName);
            emb.stream()
                .filter(x -> !matchesType(session, x, linkedClassName))
                .findFirst()
                .ifPresent(
                    x -> {
                      throw new SchemaException(session.getDatabaseName(),
                          "The database contains some schema-less data in the property '"
                              + className
                              + "."
                              + propertyName
                              + "' that is not compatible with the type "
                              + type
                              + " "
                              + linkedClassName
                              + ". Fix those records and change the schema again. "
                              + x);
                    });
            break;
          case EMBEDDED:
          case LINK:
            var elem = item.getProperty(propertyName);
            if (!matchesType(session, elem, linkedClassName)) {
              throw new SchemaException(session.getDatabaseName(),
                  "The database contains some schema-less data in the property '"
                      + className
                      + "."
                      + propertyName
                      + "' that is not compatible with the type "
                      + type
                      + " "
                      + linkedClassName
                      + ". Fix those records and change the schema again!");
            }
            break;
        }
      }
    }

  }

  private static boolean matchesType(DatabaseSessionEmbedded db, Object x,
      String linkedClassName) {
    if (x instanceof Result) {
      x = ((Result) x).asEntity();
    }
    if (x instanceof RID) {
      try {
        var transaction = db.getActiveTransaction();
        x = transaction.load(((RID) x));
      } catch (RecordNotFoundException e) {
        return true;
      }
    }
    if (x == null) {
      return true;
    }
    if (!(x instanceof Entity)) {
      return false;
    }
    return !(x instanceof EntityImpl)
        || linkedClassName.equalsIgnoreCase(((EntityImpl) x).getSchemaClassName());
  }


  private static String getEscapedName(final String iName, final boolean iStrictSQL) {
    if (iStrictSQL)
    // ESCAPE NAME
    {
      return "`" + iName + "`";
    }
    return iName;
  }

  public static void checkLinkTypeSupport(PropertyTypeInternal type) {
    if (type != PropertyTypeInternal.EMBEDDEDSET && type != PropertyTypeInternal.EMBEDDEDLIST
        && type != PropertyTypeInternal.EMBEDDEDMAP) {
      throw new SchemaException("Linked type is not supported for type: " + type);
    }
  }


  public static void checkSupportLinkedClass(PropertyTypeInternal type) {
    if (type != PropertyTypeInternal.LINK
        && type != PropertyTypeInternal.LINKSET
        && type != PropertyTypeInternal.LINKLIST
        && type != PropertyTypeInternal.LINKMAP
        && type != PropertyTypeInternal.EMBEDDED
        && type != PropertyTypeInternal.EMBEDDEDSET
        && type != PropertyTypeInternal.EMBEDDEDLIST
        && type != PropertyTypeInternal.EMBEDDEDMAP
        && type != PropertyTypeInternal.LINKBAG) {
      throw new SchemaException("Linked class is not supported for type: " + type);
    }
  }

  private static void firePropertyTypeMigration(
      @Nonnull final DatabaseSessionEmbedded session,
      @Nonnull final String className,
      @Nonnull final String propertyName,
      @Nonnull final PropertyTypeInternal type) {
    final var strictSQL =
        session.getStorageInfo().getConfiguration().isStrictSql();

    var recordsToUpdate =
        session.query(
            "select from "
                + getEscapedName(className, strictSQL)
                + " where "
                + getEscapedName(propertyName, strictSQL)
                + ".type() <> \""
                + type.name()
                + "\"").toRidList();

    for (var rid : recordsToUpdate) {
      var entity = (EntityImpl) session.loadEntity(rid);
      var value = entity.getPropertyInternal(propertyName);
      if (value == null) {
        return;
      }

      var valueType = PropertyTypeInternal.getTypeByValue(value);
      if (valueType != type) {
        entity.setPropertyInternal(propertyName, value, type);
      }
    }
  }

  private static void checkPersistentPropertiesOnTypeCompatibility(DatabaseSessionEmbedded session,
      @Nonnull final String className,
      @Nonnull final String propertyName,
      @Nonnull final PropertyTypeInternal type,
      @Nullable final PropertyTypeInternal linkedType,
      @Nullable final String linkedClassName) {
    checkPersistentPropertyType(session, className, propertyName, type, linkedClassName);

    if (linkedType != null) {
      checkLinkTypeSupport(type);
    }
    if (linkedClassName != null) {
      checkSupportLinkedClass(type);
    }

    firePropertyTypeMigration(session, className, propertyName, type);
  }
}

