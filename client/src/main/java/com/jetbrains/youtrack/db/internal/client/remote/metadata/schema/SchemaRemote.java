package com.jetbrains.youtrack.db.internal.client.remote.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.session.SessionListener;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.LazySchemaClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 */
public class SchemaRemote extends SchemaShared {

  private final AtomicBoolean skipPush = new AtomicBoolean(false);

  public SchemaRemote() {
    super();
  }

  @Override
  public SchemaClass getOrCreateClass(
      DatabaseSessionInternal database, String iClassName, SchemaClass... superClasses) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock();
    try {
      LazySchemaClass cls = classesRefs.get(normalizeClassName(iClassName));
      if (cls != null) {
        cls.loadIfNeededWithTemplate(database, createClassInstance(iClassName));
        return cls.getDelegate();
      }
    } finally {
      releaseSchemaReadLock();
    }

    SchemaClass cls;

    int[] clusterIds = null;

    acquireSchemaWriteLock(database);
    try {
      LazySchemaClass lazySchemaClass = classesRefs.get(normalizeClassName(iClassName));
      if (lazySchemaClass != null) {
        lazySchemaClass.loadIfNeededWithTemplate(database, createClassInstance(iClassName));
        return lazySchemaClass.getDelegate();
      }

      cls = createClass(database, iClassName, clusterIds, superClasses);

      addClusterClassMap(cls);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return cls;
  }

  protected SchemaClassImpl createClassInstance(String name) {
    return new SchemaClassRemote(this, name);
  }

  public SchemaClass createClass(
      DatabaseSessionInternal database,
      final String className,
      int[] clusterIds,
      SchemaClass... superClasses) {
    final Character wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    if (wrongCharacter != null) {
      throw new SchemaException(
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }
    SchemaClass result;

    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassImpl.checkParametersConflict(database, Arrays.asList(superClasses));
    }

    acquireSchemaWriteLock(database);
    try {

      final String key = normalizeClassName(className);
      if (classesRefs.containsKey(key)) {
        throw new SchemaException("Class '" + className + "' already exists in current database");
      }

      checkClustersAreAbsent(clusterIds);

      StringBuilder cmd = new StringBuilder("create class ");
      cmd.append('`');
      cmd.append(className);
      cmd.append('`');

      List<SchemaClass> superClassesList = new ArrayList<SchemaClass>();
      if (superClasses != null && superClasses.length > 0) {
        boolean first = true;
        for (SchemaClass superClass : superClasses) {
          // Filtering for null
          if (superClass != null) {
            if (first) {
              cmd.append(" extends ");
            } else {
              cmd.append(", ");
            }
            cmd.append('`').append(superClass.getName()).append('`');
            first = false;
            superClassesList.add(superClass);
          }
        }
      }

      if (clusterIds != null) {
        if (clusterIds.length == 1 && clusterIds[0] == -1) {
          cmd.append(" abstract");
        } else {
          cmd.append(" cluster ");
          for (int i = 0; i < clusterIds.length; ++i) {
            if (i > 0) {
              cmd.append(',');
            } else {
              cmd.append(' ');
            }

            cmd.append(clusterIds[i]);
          }
        }
      }

      database.command(cmd.toString()).close();
      reload(database);

      result = classesRefs.get(normalizeClassName(className)).getDelegate();

      // WAKE UP DB LIFECYCLE LISTENER
      for (Iterator<DatabaseLifecycleListener> it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext(); ) {
        it.next().onCreateClass(database, result);
      }

      for (Iterator<SessionListener> it = database.getListeners().iterator(); it.hasNext(); ) {
        it.next().onCreateClass(database, result);
      }

    } finally {
      releaseSchemaWriteLock(database);
    }

    return result;
  }

  public SchemaClass createClass(
      DatabaseSessionInternal database,
      final String className,
      int clusters,
      SchemaClass... superClasses) {
    final Character wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    if (wrongCharacter != null) {
      throw new SchemaException(
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }

    SchemaClass result;

    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassImpl.checkParametersConflict(database, Arrays.asList(superClasses));
    }
    acquireSchemaWriteLock(database);
    try {

      final String key = className.toLowerCase(Locale.ENGLISH);
      if (classesRefs.containsKey(key)) {
        throw new SchemaException("Class '" + className + "' already exists in current database");
      }

      StringBuilder cmd = new StringBuilder("create class ");
      cmd.append('`');
      cmd.append(className);
      cmd.append('`');

      List<SchemaClass> superClassesList = new ArrayList<SchemaClass>();
      if (superClasses != null && superClasses.length > 0) {
        boolean first = true;
        for (SchemaClass superClass : superClasses) {
          // Filtering for null
          if (superClass != null) {
            if (first) {
              cmd.append(" extends ");
            } else {
              cmd.append(", ");
            }
            cmd.append(superClass.getName());
            first = false;
            superClassesList.add(superClass);
          }
        }
      }

      if (clusters == 0) {
        cmd.append(" abstract");
      } else {
        cmd.append(" clusters ");
        cmd.append(clusters);
      }

      database.command(cmd.toString()).close();
      reload(database);
      result = classesRefs.get(normalizeClassName(className)).getDelegate();

      // WAKE UP DB LIFECYCLE LISTENER
      for (Iterator<DatabaseLifecycleListener> it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext(); ) {
        it.next().onCreateClass(database, result);
      }

      for (Iterator<SessionListener> it = database.getListeners().iterator(); it.hasNext(); ) {
        it.next().onCreateClass(database, result);
      }

    } finally {
      releaseSchemaWriteLock(database);
    }

    return result;
  }

  private void checkClustersAreAbsent(final int[] iClusterIds) {
    if (iClusterIds == null) {
      return;
    }

    for (int clusterId : iClusterIds) {
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

  public void dropClass(DatabaseSessionInternal database, final String className) {

    acquireSchemaWriteLock(database);
    try {
      if (database.getTransaction().isActive()) {
        throw new IllegalStateException("Cannot drop a class inside a transaction");
      }

      if (className == null) {
        throw new IllegalArgumentException("Class name is null");
      }

      database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

      final String key = className.toLowerCase(Locale.ENGLISH);

      SchemaClass cls = classesRefs.get(key).getDelegate();

      if (cls == null) {
        throw new SchemaException("Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses(database).isEmpty()) {
        throw new SchemaException(
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses(database)
                + ". Remove the dependencies before trying to drop it again");
      }

      String cmd = "drop class `" + className + "` unsafe";
      database.command(cmd).close();
      reload(database);

      var localCache = database.getLocalCache();
      for (int clusterId : cls.getClusterIds()) {
        localCache.freeCluster(clusterId);
      }
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  @Override
  public void acquireSchemaWriteLock(DatabaseSessionInternal database) {
    skipPush.set(true);
  }

  @Override
  public void releaseSchemaWriteLock(DatabaseSessionInternal database, final boolean iSave) {
    skipPush.set(false);
  }

  @Override
  public void checkEmbedded() {
    throw new SchemaException(
        "'Internal' schema modification methods can be used only inside of embedded database");
  }

  public void update(DatabaseSessionInternal session, EntityImpl schema) {
    if (!skipPush.get()) {
      fromStream(session, schema);
      this.snapshot = null;
    }
  }

  @Override
  public int addBlobCluster(DatabaseSessionInternal database, int clusterId) {
    throw new SchemaException(
        "Not supported operation use instead DatabaseSession.addBlobCluster");
  }

  @Override
  public void removeBlobCluster(DatabaseSessionInternal database, String clusterName) {
    throw new SchemaException(
        "Not supported operation use instead DatabaseSession.dropCluster");
  }
}
