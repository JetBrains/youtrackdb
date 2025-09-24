package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.index.CollectionId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollection;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

public final class SchemaManager {

  public static final int CURRENT_VERSION_NUMBER = 5;

  public static final String SCHEMA_CLASS_NAME_INDEX = "$SchemaClassNameIndex";
  public static final String SCHEMA_COLLECTION_ID_CLASS_INDEX = "$CollectionIdClassIndex";

  public static final String GLOBAL_PROPERTY_NAME_TYPE_INDEX = "$GlobalPropertyNameTypeIndex";
  public static final String GLOBAL_PROPERTY_ID_INDEX = "$GlobalPropertyNameTypeIndex";

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

  @SuppressWarnings("JavaExistingMethodCanBeUsed")
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
    entity.addSuperClass(superClass);

    return entity;
  }

  public static SchemaClassEntity createClass(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull String className,
      SchemaClassEntity... superClasses) {
    var entity = session.newSchemaClassEntity(className);

    for (var superClass : superClasses) {
      entity.addSuperClass(superClass);
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
    entity.addSuperClass(superClass);
    return entity;
  }

  public static SchemaClassEntity createAbstractClass(
      @Nonnull DatabaseSessionEmbedded session, @Nonnull String className,
      SchemaClassEntity... superClasses) {
    var entity = session.newSchemaClassEntity(className);
    entity.setAbstractClass(true);

    for (var superClass : superClasses) {
      entity.addSuperClass(superClass);
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


  private static void dropClassIndexes(DatabaseSessionEmbedded session,
      final SchemaClassEntity cls) {
    final var indexManager = session.getSharedContext().getIndexManager();

    for (final var index : indexManager.getClassIndexes(session, cls.getName())) {
      indexManager.dropIndex(session, index.getName());
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
      var collectionIds = new ArrayList<CollectionId>(collectionsCount);

      for (var i = 0; i < collectionsCount; i++) {
        collectionIds.add(new CollectionId(transaction.generateTempCollectionId()));
      }

      entity.setCollectionIds(collectionIds);
    }

    try {
      entity.validate();
    } catch (ValidationException e) {
      //skip not valid entity, TX will be rolled back on commit
      return;
    }

    checkClassDoesNotExist(session, className);

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
      checkClassDoesNotExist(session, className);

      var schemaClassNameIndex = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
      var activeTransaction = session.getActiveTransaction();
      schemaClassNameIndex.remove(activeTransaction, originalValue);
      schemaClassNameIndex.put(activeTransaction, className, entity.getIdentity());
    }

    if (dirtyFields.contains(YTDBSchemaClassPTokenInternal.abstractClass.name())) {
      if (entity.isAbstractClass()) {
        var collectionIds = entity.getCollectionIds();

        for (var collectionId : collectionIds) {
          if (!collectionId.isTemporary()) {
            throw new DatabaseException(session,
                "Cannot change abstract class to non-abstract as it "
                    + "is associated with existing collections");
          }
        }

        entity.clearCollectionIds();
      } else {
        var collectionsCount = session.getStorageInfo().getConfiguration().getMinimumCollections();
        var collectionIds = new ArrayList<CollectionId>(collectionsCount);

        for (var i = 0; i < collectionsCount; i++) {
          collectionIds.add(new CollectionId(transaction.generateTempCollectionId()));
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

    dropClassIndexes(session, entity);

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
        var superClasses = entity.getSuperClasses();
        checkParametersConflict(superClasses);
      }

      if (dirtyProperties.contains(YTDBSchemaClassPTokenInternal.collectionIds.name())) {
        var collectionIds = entity.getCollectionIds();

        if (!collectionIds.isEmpty() && collectionIds.getFirst().isTemporary()) {
          var allocatedCollectionIds = new ArrayList<Integer>(collectionIds.size());

          for (var collectionId : collectionIds) {
            if (collectionId.isTemporary()) {
              var realCollectionId = session.allocateCollection();

              var collectionIterator = new RecordIteratorCollection<SchemaClassEntity>(session,
                  realCollectionId, true);
              while (collectionIterator.hasNext()) {
                var record = collectionIterator.next();
                ((ChangeableRecordId) record.getIdentity()).setCollectionId(realCollectionId);
              }

              collectionId.setId(realCollectionId);
              allocatedCollectionIds.add(realCollectionId);
            }
          }

          if (!allocatedCollectionIds.isEmpty()) {
            transaction.addRollbackAction(dbSession -> {
              for (var collectionId : allocatedCollectionIds) {
                dbSession.freeCollection(collectionId);
              }
            });
          }
        }
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


  private static void checkParametersConflict(@Nonnull Iterator<SchemaClassEntity> superClasses) {
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

  private static void checkClassDoesNotExist(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull String className) {
    var schemaClassNameIndex = session.getIndex(SCHEMA_CLASS_NAME_INDEX);
    if (schemaClassNameIndex.getRids(session, className).findAny().isPresent()) {
      throw new IllegalArgumentException("Class '" + className + "' is already present in schema");
    }
  }
}

