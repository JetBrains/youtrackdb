package com.jetbrains.youtrack.db.internal.client.remote.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableLong;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 *
 */
public class SchemaRemote extends SchemaShared {

  private final AtomicInteger updateRequests = new AtomicInteger(0);
  private final ThreadLocal<ModifiableLong> lockNesting = ThreadLocal.withInitial(
      ModifiableLong::new);

  public SchemaRemote() {
    super();
  }

  private final ThreadLocal<boolean[]> ignoreReloadRequest = ThreadLocal.withInitial(
      () -> new boolean[1]);

  @Override
  public SchemaClassImpl getOrCreateClass(
      DatabaseSessionInternal session, String iClassName, SchemaClassImpl... superClasses) {
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

    acquireSchemaWriteLock(session);
    try {
      cls = classes.get(iClassName.toLowerCase(Locale.ENGLISH));
      if (cls != null) {
        return cls;
      }

      cls = createClass(session, iClassName, clusterIds, superClasses);

      addClusterClassMap(session, cls);
    } finally {
      releaseSchemaWriteLock(session);
    }

    return cls;
  }

  protected SchemaClassImpl createClassInstance(String name) {
    return new SchemaClassRemote(this, name);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      int[] clusterIds,
      SchemaClassImpl... superClasses) {
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(session,
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }
    SchemaClassImpl result;

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassImpl.checkParametersConflict(session, Arrays.asList(superClasses));
    }

    acquireSchemaWriteLock(session);
    try {

      final var key = className.toLowerCase(Locale.ENGLISH);
      if (classes.containsKey(key)) {
        throw new SchemaException(session,
            "Class '" + className + "' already exists in current database");
      }

      checkClustersAreAbsent(clusterIds, session);

      var cmd = new StringBuilder("create class ");
      cmd.append('`');
      cmd.append(className);
      cmd.append('`');

      if (superClasses != null && superClasses.length > 0) {
        var first = true;
        for (var superClass : superClasses) {
          // Filtering for null
          if (superClass != null) {
            if (first) {
              cmd.append(" extends ");
            } else {
              cmd.append(", ");
            }
            cmd.append('`').append(superClass.getName(session)).append('`');
            first = false;
          }
        }
      }

      if (clusterIds != null) {
        if (clusterIds.length == 1 && clusterIds[0] == -1) {
          cmd.append(" abstract");
        } else {
          cmd.append(" cluster ");
          for (var i = 0; i < clusterIds.length; ++i) {
            if (i > 0) {
              cmd.append(',');
            } else {
              cmd.append(' ');
            }

            cmd.append(clusterIds[i]);
          }
        }
      }

      session.execute(cmd.toString()).close();
      reload(session);

      result = classes.get(className.toLowerCase(Locale.ENGLISH));
    } finally {
      releaseSchemaWriteLock(session);
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
      throw new SchemaException(session,
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }

    SchemaClassImpl result;

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    if (superClasses != null) {
      SchemaClassImpl.checkParametersConflict(session, Arrays.asList(superClasses));
    }
    acquireSchemaWriteLock(session);
    try {

      final var key = className.toLowerCase(Locale.ENGLISH);
      if (classes.containsKey(key)) {
        throw new SchemaException(session,
            "Class '" + className + "' already exists in current database");
      }

      var cmd = new StringBuilder("create class ");
      cmd.append('`');
      cmd.append(className);
      cmd.append('`');

      if (superClasses != null && superClasses.length > 0) {
        var first = true;
        for (var superClass : superClasses) {
          // Filtering for null
          if (superClass != null) {
            if (first) {
              cmd.append(" extends ");
            } else {
              cmd.append(", ");
            }
            cmd.append(superClass.getName(session));
            first = false;
          }
        }
      }

      if (clusters == 0) {
        cmd.append(" abstract");
      } else {
        cmd.append(" clusters ");
        cmd.append(clusters);
      }

      session.execute(cmd.toString()).close();
      reload(session);
      result = classes.get(className.toLowerCase(Locale.ENGLISH));
    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  private void checkClustersAreAbsent(final int[] iClusterIds, DatabaseSessionInternal session) {
    if (iClusterIds == null) {
      return;
    }

    for (var clusterId : iClusterIds) {
      if (clusterId < 0) {
        continue;
      }

      if (clustersToClasses.containsKey(clusterId)) {
        throw new SchemaException(session,
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
        throw new SchemaException(session,
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses(session).isEmpty()) {
        throw new SchemaException(session,
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses(session)
                + ". Remove the dependencies before trying to drop it again");
      }

      var cmd = "drop class `" + className + "` unsafe";
      session.execute(cmd).close();
      reload(session);

      var localCache = session.getLocalCache();
      for (var clusterId : cls.getClusterIds(session)) {
        localCache.freeCluster(clusterId);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void acquireSchemaWriteLock(DatabaseSessionInternal session) {
    updateIfRequested(session);

    lockNesting.get().increment();
  }

  @Override
  public void releaseSchemaWriteLock(DatabaseSessionInternal session) {
    super.releaseSchemaWriteLock(session);
    lockNesting.get().decrement();

    updateIfRequested(session);
  }

  private void updateIfRequested(@Nonnull DatabaseSessionInternal database) {
    var ignoreReloadRequest = this.ignoreReloadRequest.get();
    //stack overflow guard
    if (ignoreReloadRequest[0]) {
      return;
    }

    var lockNesting = this.lockNesting.get().value;
    if (lockNesting > 0) {
      return;
    }

    while (true) {
      var updateReqs = updateRequests.getAndSet(0);
      if (updateReqs > 0) {
        ignoreReloadRequest[0] = true;
        try {
          reload(database);
        } finally {
          ignoreReloadRequest[0] = false;
        }
      } else {
        break;
      }
    }
  }

  @Override
  public void releaseSchemaWriteLock(DatabaseSessionInternal session, final boolean iSave) {
    updateIfRequested(session);
  }

  @Override
  public void acquireSchemaReadLock(DatabaseSessionInternal session) {
    updateIfRequested(session);

    lockNesting.get().increment();
    super.acquireSchemaReadLock(session);
  }

  @Override
  public void releaseSchemaReadLock(DatabaseSessionInternal session) {
    super.releaseSchemaReadLock(session);
    lockNesting.get().decrement();

    updateIfRequested(session);
  }

  @Override
  public void checkEmbedded(DatabaseSessionInternal session) {
    throw new SchemaException(session,
        "'Internal' schema modification methods can be used only inside of embedded database");
  }

  public void requestUpdate() {
    updateRequests.incrementAndGet();
  }

  @Override
  public int addBlobCluster(DatabaseSessionInternal session, int clusterId) {
    throw new SchemaException(session,
        "Not supported operation use instead DatabaseSession.addBlobCluster");
  }

  @Override
  public void removeBlobCluster(DatabaseSessionInternal session, String clusterName) {
    throw new SchemaException(session,
        "Not supported operation use instead DatabaseSession.dropCluster");
  }
}
