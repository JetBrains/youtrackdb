package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

public class SchemaEmbedded extends SchemaShared {

  public SchemaEmbedded() {
    super();
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int[] collectionIds,
      SchemaClassImpl... superClasses) {
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(session.getDatabaseName(),
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }

    SchemaClassImpl result;
    var retry = 0;

    while (true) {
      try {
        result = doCreateClass(session, className, collectionIds, retry, superClasses);
        break;
      } catch (CollectionIdsAreEmptyException ignore) {
        classes.remove(className.toLowerCase(Locale.ENGLISH));
        collectionIds = createCollections(session, className);
        retry++;
      }
    }
    return result;
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int collections,
      SchemaClassImpl... superClasses) {
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(session.getDatabaseName(),
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }

    return doCreateClass(session, className, collections, superClasses);
  }

  private SchemaClassImpl doCreateClass(
      DatabaseSessionEmbedded session,
      final String className,
      final int collections,
      SchemaClassImpl... superClasses) {
    SchemaClassImpl result;

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassImpl.checkParametersConflict(session, Arrays.asList(superClasses));
    }
    acquireSchemaWriteLock(session);
    try {

      final var key = className.toLowerCase(Locale.ENGLISH);
      if (classes.containsKey(key)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }
      List<SchemaClassImpl> superClassesList = new ArrayList<>();
      if (superClasses != null) {
        for (var superClass : superClasses) {
          // Filtering for null
          if (superClass != null) {
            superClassesList.add(superClass);
          }
        }
      }

      final int[] collectionIds;
      if (collections > 0) {
        collectionIds = createCollections(session, className, collections);
      } else {
        // ABSTRACT
        collectionIds = new int[]{-1};
      }

      doRealCreateClass((DatabaseSessionEmbedded) session, className, superClassesList,
          collectionIds);

      result = classes.get(className.toLowerCase(Locale.ENGLISH));
      // WAKE UP DB LIFECYCLE LISTENER
      for (var it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext(); ) {
        //noinspection deprecation
        it.next().onCreateClass(session, result);
      }

      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onCreateClass(session, new SchemaClassProxy(result, session));
      }

    } catch (CollectionIdsAreEmptyException e) {
      throw BaseException.wrapException(
          new SchemaException(session.getDatabaseName(), "Cannot create class '" + className + "'"),
          e,
          session.getDatabaseName());
    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  protected void doRealCreateClass(
      DatabaseSessionEmbedded database,
      String className,
      List<SchemaClassImpl> superClassesList,
      int[] collectionIds)
      throws CollectionIdsAreEmptyException {
    createClassInternal(database, className, collectionIds, superClassesList);
  }

  protected void createClassInternal(
      DatabaseSessionEmbedded session,
      final String className,
      final int[] collectionIdsToAdd,
      final List<SchemaClassImpl> superClasses)
      throws CollectionIdsAreEmptyException {
    acquireSchemaWriteLock(session);
    try {
      if (className == null || className.isEmpty()) {
        throw new SchemaException(session.getDatabaseName(), "Found class name null or empty");
      }

      checkEmbedded(session);

      checkCollectionsAreAbsent(collectionIdsToAdd);

      final int[] collectionIds;
      if (collectionIdsToAdd == null || collectionIdsToAdd.length == 0) {
        throw new CollectionIdsAreEmptyException();

      } else {
        collectionIds = collectionIdsToAdd;
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);

      final var key = className.toLowerCase(Locale.ENGLISH);

      if (classes.containsKey(key)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }

      var cls = createClassInstance(className, collectionIds);

      classes.put(key, cls);

      if (superClasses != null && !superClasses.isEmpty()) {
        cls.setSuperClassesInternal(session, superClasses);
        for (var superClass : superClasses) {
          // UPDATE INDEXES
          final var collectionsToIndex = superClass.getPolymorphicCollectionIds(session);
          final var collectionNames = new String[collectionsToIndex.length];
          for (var i = 0; i < collectionsToIndex.length; i++) {
            collectionNames[i] = session.getCollectionNameById(collectionsToIndex[i]);
          }

          for (var index : superClass.getIndexesInternal(session)) {
            for (var collectionName : collectionNames) {
              if (collectionName != null) {
                session
                    .getSharedContext()
                    .getIndexManager()
                    .addCollectionToIndex(session, collectionName, index.getName());
              }
            }
          }
        }
      }

      addCollectionClassMap(session, cls);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected SchemaClassImpl createClassInstance(String className, int[] collectionIds) {
    return new SchemaClassEmbedded(this, className, collectionIds);
  }

  @Nullable
  public SchemaClassImpl getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName,
      final SchemaClassImpl... superClasses) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock(session);
    try {
      var cls = classes.get(iClassName.toLowerCase(Locale.ENGLISH));
      if (cls != null) {
        return cls;
      }
    } finally {
      releaseSchemaReadLock(session);
    }

    SchemaClassImpl cls;

    int[] collectionIds = null;
    var retry = 0;

    while (true) {
      try {
        acquireSchemaWriteLock(session);
        try {
          cls = classes.get(iClassName.toLowerCase(Locale.ENGLISH));
          if (cls != null) {
            return cls;
          }

          cls = doCreateClass(session, iClassName, collectionIds, retry, superClasses);
          addCollectionClassMap(session, cls);
        } finally {
          releaseSchemaWriteLock(session);
        }
        break;
      } catch (CollectionIdsAreEmptyException ignore) {
        collectionIds = createCollections(session, iClassName);
        retry++;
      }
    }

    return cls;
  }

  protected SchemaClassImpl doCreateClass(
      DatabaseSessionEmbedded session,
      final String className,
      int[] collectionIds,
      int retry,
      SchemaClassImpl... superClasses)
      throws CollectionIdsAreEmptyException {
    SchemaClassImpl result;
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassImpl.checkParametersConflict(session, Arrays.asList(superClasses));
    }

    acquireSchemaWriteLock(session);
    try {

      final var key = className.toLowerCase(Locale.ENGLISH);
      if (classes.containsKey(key) && retry == 0) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }

      checkCollectionsAreAbsent(collectionIds);

      if (collectionIds == null || collectionIds.length == 0) {
        collectionIds =
            createCollections(
                session,
                className,
                session.getStorageInfo().getConfiguration().getMinimumCollections());
      }
      List<SchemaClassImpl> superClassesList = new ArrayList<>();
      if (superClasses != null) {
        for (var superClass : superClasses) {
          if (superClass != null) {
            superClassesList.add(superClass);
          }
        }
      }

      doRealCreateClass((DatabaseSessionEmbedded) session, className, superClassesList,
          collectionIds);

      result = classes.get(className.toLowerCase(Locale.ENGLISH));
      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onCreateClass(session, new SchemaClassProxy(result, session));
      }

    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  private int[] createCollections(DatabaseSessionInternal session, final String iClassName) {
    return createCollections(
        session, iClassName, session.getStorageInfo().getConfiguration().getMinimumCollections());
  }

  protected int[] createCollections(
      DatabaseSessionInternal session, String className, int minimumCollections) {
    className = className.toLowerCase(Locale.ENGLISH);

    int[] collectionIds;

    if (internalClasses.contains(className.toLowerCase(Locale.ENGLISH))) {
      // INTERNAL CLASS, SET TO 1
      minimumCollections = 1;
    }

    collectionIds = new int[minimumCollections];
    collectionIds[0] = session.getCollectionIdByName(className);
    if (collectionIds[0] > -1) {
      // CHECK THE COLLECTION HAS NOT BEEN ALREADY ASSIGNED
      final var cls = collectionsToClasses.get(collectionIds[0]);
      if (cls != null) {
        collectionIds[0] = session.addCollection(
            getNextAvailableCollectionName(session, className));
      }
    } else
    // JUST KEEP THE CLASS NAME. THIS IS FOR LEGACY REASONS
    {
      collectionIds[0] = session.addCollection(className);
    }

    for (var i = 1; i < minimumCollections; ++i) {
      collectionIds[i] = session.addCollection(getNextAvailableCollectionName(session, className));
    }

    return collectionIds;
  }

  private static String getNextAvailableCollectionName(
      DatabaseSessionInternal session, final String className) {
    for (var i = 1; ; ++i) {
      final var collectionName = className + "_" + i;
      if (session.getCollectionIdByName(collectionName) < 0)
      // FREE NAME
      {
        return collectionName;
      }
    }
  }

  protected void checkCollectionsAreAbsent(final int[] iCollectionIds) {
    if (iCollectionIds == null) {
      return;
    }

    for (var collectionId : iCollectionIds) {
      if (collectionId < 0) {
        continue;
      }

      if (collectionsToClasses.containsKey(collectionId)) {
        throw new SchemaException(
            "Collection with id "
                + collectionId
                + " already belongs to class "
                + collectionsToClasses.get(collectionId));
      }
    }
  }

  public void dropClass(DatabaseSessionEmbedded session, final String className) {
    acquireSchemaWriteLock(session);
    try {
      if (session.getTransactionInternal().isActive()) {
        throw new IllegalStateException("Cannot drop a class inside a transaction");
      }

      if (className == null) {
        throw new IllegalArgumentException("Class name is null");
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

      final var key = className.toLowerCase(Locale.ENGLISH);

      var cls = classes.get(key);

      if (cls == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses(session).isEmpty()) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses(session)
                + ". Remove the dependencies before trying to drop it again");
      }

      doDropClass(session, className);

      var localCache = session.getLocalCache();
      for (var collectionId : cls.getCollectionIds(session)) {
        localCache.freeCollection(collectionId);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void doDropClass(DatabaseSessionEmbedded session, String className) {
    dropClassInternal(session, className);
  }

  protected void dropClassInternal(DatabaseSessionEmbedded session, final String className) {
    acquireSchemaWriteLock(session);
    try {
      if (session.getTransactionInternal().isActive()) {
        throw new IllegalStateException("Cannot drop a class inside a transaction");
      }

      if (className == null) {
        throw new IllegalArgumentException("Class name is null");
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

      final var key = className.toLowerCase(Locale.ENGLISH);

      final var cls = classes.get(key);
      if (cls == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses(session).isEmpty()) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses(session)
                + ". Remove the dependencies before trying to drop it again");
      }

      checkEmbedded(session);

      for (var superClass : cls.getSuperClasses(session)) {
        // REMOVE DEPENDENCY FROM SUPERCLASS
        superClass.removeBaseClassInternal(session, cls);
      }
      for (var id : cls.getCollectionIds(session)) {
        if (id != -1) {
          deleteCollection(session, id);
        }
      }

      dropClassIndexes((DatabaseSessionEmbedded) session, cls);

      classes.remove(key);

      removeCollectionClassMap(session, cls);

      // WAKE UP DB LIFECYCLE LISTENER
      for (var it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext(); ) {
        //noinspection deprecation
        it.next().onDropClass(session, cls);
      }

      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onDropClass(session, new SchemaClassProxy(cls, session));
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected SchemaClassImpl createClassInstance(String name) {
    return new SchemaClassEmbedded(this, name);
  }


  private static void dropClassIndexes(DatabaseSessionEmbedded session, final SchemaClassImpl cls) {
    final var indexManager = session.getSharedContext().getIndexManager();

    for (final var index : indexManager.getClassIndexes(session, cls.getName(session))) {
      indexManager.dropIndex(session, index.getName());
    }
  }

  private static void deleteCollection(final DatabaseSessionInternal session,
      final int collectionId) {
    final var collectionName = session.getCollectionNameById(collectionId);
    if (collectionName != null) {
      final var iteratorCollection = session.browseCollection(collectionName);
      if (iteratorCollection != null) {
        session.executeInTxBatches(
            iteratorCollection, (s, record) -> record.delete());
        session.dropCollectionInternal(collectionId);
      }
    }

    session.getLocalCache().freeCollection(collectionId);
  }

  private void removeCollectionClassMap(DatabaseSessionInternal session,
      final SchemaClassImpl cls) {
    for (var collectionId : cls.getCollectionIds(session)) {
      if (collectionId < 0) {
        continue;
      }

      collectionsToClasses.remove(collectionId);
    }
  }

  public void checkEmbedded(DatabaseSessionInternal session) {
  }

  void addCollectionForClass(
      DatabaseSessionInternal session, final int collectionId, final SchemaClassImpl cls) {
    acquireSchemaWriteLock(session);
    try {
      if (collectionId < 0) {
        return;
      }

      checkEmbedded(session);

      final var existingCls = collectionsToClasses.get(collectionId);
      if (existingCls != null && !cls.equals(existingCls)) {
        throw new SchemaException(session.getDatabaseName(),
            "Collection with id "
                + collectionId
                + " already belongs to class "
                + collectionsToClasses.get(collectionId));
      }

      collectionsToClasses.put(collectionId, cls);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  void removeCollectionForClass(DatabaseSessionInternal session, int collectionId) {
    acquireSchemaWriteLock(session);
    try {
      if (collectionId < 0) {
        return;
      }

      checkEmbedded(session);

      collectionsToClasses.remove(collectionId);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }
}
