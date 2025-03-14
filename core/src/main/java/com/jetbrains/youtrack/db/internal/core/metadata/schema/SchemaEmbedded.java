package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SchemaEmbedded extends SchemaShared {

  public SchemaEmbedded() {
    super();
  }

  public SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      int[] clusterIds,
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
        result = doCreateClass(session, className, clusterIds, retry, superClasses);
        break;
      } catch (ClusterIdsAreEmptyException ignore) {
        classes.remove(className.toLowerCase(Locale.ENGLISH));
        clusterIds = createClusters(session, className);
        retry++;
      }
    }
    return result;
  }

  public SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      int clusters,
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

    return doCreateClass(session, className, clusters, superClasses);
  }

  private SchemaClassImpl doCreateClass(
      DatabaseSessionInternal session,
      final String className,
      final int clusters,
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

      final int[] clusterIds;
      if (clusters > 0) {
        clusterIds = createClusters(session, className, clusters);
      } else {
        // ABSTRACT
        clusterIds = new int[]{-1};
      }

      doRealCreateClass(session, className, superClassesList, clusterIds);

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

    } catch (ClusterIdsAreEmptyException e) {
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
      DatabaseSessionInternal database,
      String className,
      List<SchemaClassImpl> superClassesList,
      int[] clusterIds)
      throws ClusterIdsAreEmptyException {
    createClassInternal(database, className, clusterIds, superClassesList);
  }

  protected void createClassInternal(
      DatabaseSessionInternal session,
      final String className,
      final int[] clusterIdsToAdd,
      final List<SchemaClassImpl> superClasses)
      throws ClusterIdsAreEmptyException {
    acquireSchemaWriteLock(session);
    try {
      if (className == null || className.isEmpty()) {
        throw new SchemaException(session.getDatabaseName(), "Found class name null or empty");
      }

      checkEmbedded(session);

      checkClustersAreAbsent(clusterIdsToAdd);

      final int[] clusterIds;
      if (clusterIdsToAdd == null || clusterIdsToAdd.length == 0) {
        throw new ClusterIdsAreEmptyException();

      } else {
        clusterIds = clusterIdsToAdd;
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);

      final var key = className.toLowerCase(Locale.ENGLISH);

      if (classes.containsKey(key)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }

      var cls = createClassInstance(className, clusterIds);

      classes.put(key, cls);

      if (superClasses != null && !superClasses.isEmpty()) {
        cls.setSuperClassesInternal(session, superClasses);
        for (var superClass : superClasses) {
          // UPDATE INDEXES
          final var clustersToIndex = superClass.getPolymorphicClusterIds(session);
          final var clusterNames = new String[clustersToIndex.length];
          for (var i = 0; i < clustersToIndex.length; i++) {
            clusterNames[i] = session.getClusterNameById(clustersToIndex[i]);
          }

          for (var index : superClass.getIndexesInternal(session)) {
            for (var clusterName : clusterNames) {
              if (clusterName != null) {
                session
                    .getMetadata()
                    .getIndexManagerInternal()
                    .addClusterToIndex(session, clusterName, index.getName());
              }
            }
          }
        }
      }

      addClusterClassMap(session, cls);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected SchemaClassImpl createClassInstance(String className, int[] clusterIds) {
    return new SchemaClassEmbedded(this, className, clusterIds);
  }

  public SchemaClassImpl getOrCreateClass(
      DatabaseSessionInternal session, final String iClassName,
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

    int[] clusterIds = null;
    var retry = 0;

    while (true) {
      try {
        acquireSchemaWriteLock(session);
        try {
          cls = classes.get(iClassName.toLowerCase(Locale.ENGLISH));
          if (cls != null) {
            return cls;
          }

          cls = doCreateClass(session, iClassName, clusterIds, retry, superClasses);
          addClusterClassMap(session, cls);
        } finally {
          releaseSchemaWriteLock(session);
        }
        break;
      } catch (ClusterIdsAreEmptyException ignore) {
        clusterIds = createClusters(session, iClassName);
        retry++;
      }
    }

    return cls;
  }

  protected SchemaClassImpl doCreateClass(
      DatabaseSessionInternal session,
      final String className,
      int[] clusterIds,
      int retry,
      SchemaClassImpl... superClasses)
      throws ClusterIdsAreEmptyException {
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

      checkClustersAreAbsent(clusterIds);

      if (clusterIds == null || clusterIds.length == 0) {
        clusterIds =
            createClusters(
                session,
                className,
                session.getStorageInfo().getConfiguration().getMinimumClusters());
      }
      List<SchemaClassImpl> superClassesList = new ArrayList<>();
      if (superClasses != null) {
        for (var superClass : superClasses) {
          if (superClass != null) {
            superClassesList.add(superClass);
          }
        }
      }

      doRealCreateClass(session, className, superClassesList, clusterIds);

      result = classes.get(className.toLowerCase(Locale.ENGLISH));
      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onCreateClass(session, new SchemaClassProxy(result, session));
      }

    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  private int[] createClusters(DatabaseSessionInternal session, final String iClassName) {
    return createClusters(
        session, iClassName, session.getStorageInfo().getConfiguration().getMinimumClusters());
  }

  protected int[] createClusters(
      DatabaseSessionInternal session, String className, int minimumClusters) {
    className = className.toLowerCase(Locale.ENGLISH);

    int[] clusterIds;

    if (internalClasses.contains(className.toLowerCase(Locale.ENGLISH))) {
      // INTERNAL CLASS, SET TO 1
      minimumClusters = 1;
    }

    clusterIds = new int[minimumClusters];
    clusterIds[0] = session.getClusterIdByName(className);
    if (clusterIds[0] > -1) {
      // CHECK THE CLUSTER HAS NOT BEEN ALREADY ASSIGNED
      final var cls = clustersToClasses.get(clusterIds[0]);
      if (cls != null) {
        clusterIds[0] = session.addCluster(getNextAvailableClusterName(session, className));
      }
    } else
    // JUST KEEP THE CLASS NAME. THIS IS FOR LEGACY REASONS
    {
      clusterIds[0] = session.addCluster(className);
    }

    for (var i = 1; i < minimumClusters; ++i) {
      clusterIds[i] = session.addCluster(getNextAvailableClusterName(session, className));
    }

    return clusterIds;
  }

  private static String getNextAvailableClusterName(
      DatabaseSessionInternal session, final String className) {
    for (var i = 1; ; ++i) {
      final var clusterName = className + "_" + i;
      if (session.getClusterIdByName(clusterName) < 0)
      // FREE NAME
      {
        return clusterName;
      }
    }
  }

  protected void checkClustersAreAbsent(final int[] iClusterIds) {
    if (iClusterIds == null) {
      return;
    }

    for (var clusterId : iClusterIds) {
      if (clusterId < 0) {
        continue;
      }

      if (clustersToClasses.containsKey(clusterId)) {
        throw new SchemaException(
            "Cluster with id "
                + clusterId
                + " already belongs to class "
                + clustersToClasses.get(clusterId));
      }
    }
  }

  public void dropClass(DatabaseSessionInternal session, final String className) {
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
      for (var clusterId : cls.getClusterIds(session)) {
        localCache.freeCluster(clusterId);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void doDropClass(DatabaseSessionInternal session, String className) {
    dropClassInternal(session, className);
  }

  protected void dropClassInternal(DatabaseSessionInternal session, final String className) {
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
      for (var id : cls.getClusterIds(session)) {
        if (id != -1) {
          deleteCluster(session, id);
        }
      }

      dropClassIndexes(session, cls);

      classes.remove(key);

      removeClusterClassMap(session, cls);

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


  private static void dropClassIndexes(DatabaseSessionInternal session, final SchemaClassImpl cls) {
    final var indexManager = session.getMetadata().getIndexManagerInternal();

    for (final var index : indexManager.getClassIndexes(session, cls.getName(session))) {
      indexManager.dropIndex(session, index.getName());
    }
  }

  private static void deleteCluster(final DatabaseSessionInternal session, final int clusterId) {
    final var clusterName = session.getClusterNameById(clusterId);
    if (clusterName != null) {
      final var iteratorCluster = session.browseCluster(clusterName);
      if (iteratorCluster != null) {
        session.executeInTxBatches(
            iteratorCluster, (s, record) -> record.delete());
        session.dropClusterInternal(clusterId);
      }
    }

    session.getLocalCache().freeCluster(clusterId);
  }

  private void removeClusterClassMap(DatabaseSessionInternal session, final SchemaClassImpl cls) {
    for (var clusterId : cls.getClusterIds(session)) {
      if (clusterId < 0) {
        continue;
      }

      clustersToClasses.remove(clusterId);
    }
  }

  public void checkEmbedded(DatabaseSessionInternal session) {
  }

  void addClusterForClass(
      DatabaseSessionInternal session, final int clusterId, final SchemaClassImpl cls) {
    acquireSchemaWriteLock(session);
    try {
      if (clusterId < 0) {
        return;
      }

      checkEmbedded(session);

      final var existingCls = clustersToClasses.get(clusterId);
      if (existingCls != null && !cls.equals(existingCls)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cluster with id "
                + clusterId
                + " already belongs to class "
                + clustersToClasses.get(clusterId));
      }

      clustersToClasses.put(clusterId, cls);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  void removeClusterForClass(DatabaseSessionInternal session, int clusterId) {
    acquireSchemaWriteLock(session);
    try {
      if (clusterId < 0) {
        return;
      }

      checkEmbedded(session);

      clustersToClasses.remove(clusterId);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }
}
