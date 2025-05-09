package com.jetbrains.youtrack.db.internal.client.remote.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableLong;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.LazySchemaClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

  @Nullable
  @Override
  public SchemaClassImpl getOrCreateClass(
      DatabaseSessionInternal session, String iClassName, SchemaClassImpl... superClasses) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock(session);
    try {
      var cls = classesRefs.get(normalizeClassName(iClassName));
      if (cls != null) {
        cls.loadIfNeededWithTemplate(session, createClassInstance(iClassName));
        return cls.getDelegate();
      }
    } finally {
      releaseSchemaReadLock(session);
    }

    SchemaClassImpl cls;

    int[] collectionIds = null;

    acquireSchemaWriteLock(session);
    try {
      var lazySchemaClass = classesRefs.get(normalizeClassName(iClassName));
      if (lazySchemaClass != null) {
        lazySchemaClass.loadIfNeededWithTemplate(session, createClassInstance(iClassName));
        return lazySchemaClass.getDelegate();
      }

      cls = createClass(session, iClassName, collectionIds, superClasses);

      addCollectionClassMap(session, cls);
    } finally {
      releaseSchemaWriteLock(session);
    }

    return cls;
  }

  @Override
  protected SchemaClassImpl createClassInstance(String name) {
    return new SchemaClassRemote(this, name);
  }

  @Override
  public SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      int[] collectionIds,
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

      final String key = normalizeClassName(className);
      if (classesRefs.containsKey(key)) {
        throw new SchemaException("Class '" + className + "' already exists in current database");
      }

      checkCollectionsAreAbsent(collectionIds, session);

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

      if (collectionIds != null) {
        if (collectionIds.length == 1 && collectionIds[0] == -1) {
          cmd.append(" abstract");
        } else {
          cmd.append(" collection ");
          for (var i = 0; i < collectionIds.length; ++i) {
            if (i > 0) {
              cmd.append(',');
            } else {
              cmd.append(' ');
            }

            cmd.append(collectionIds[i]);
          }
        }
      }

      session.execute(cmd.toString()).close();
      reload(session);

      result = classesRefs.get(normalizeClassName(className)).getDelegate();
    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  @Override
  public SchemaClassImpl createClass(
      DatabaseSessionInternal session,
      final String className,
      int collections,
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

      final String key = className.toLowerCase(Locale.ENGLISH);
      if (classesRefs.containsKey(key)) {
        throw new SchemaException("Class '" + className + "' already exists in current database");
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

      if (collections == 0) {
        cmd.append(" abstract");
      } else {
        cmd.append(" collections ");
        cmd.append(collections);
      }

      session.execute(cmd.toString()).close();
      reload(session);
      result = classesRefs.get(normalizeClassName(className)).getDelegate();
    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  private void checkCollectionsAreAbsent(final int[] iCollectionIds,
      DatabaseSessionInternal session) {
    if (iCollectionIds == null) {
      return;
    }

    for (var collectionId : iCollectionIds) {
      if (collectionId < 0) {
        continue;
      }

      if (collectionsToClasses.containsKey(collectionId)) {
        throw new SchemaException(session,
            "Collection with id "
                + collectionId
                + " already belongs to class "
                + collectionsToClasses.get(collectionId));
      }
    }
  }

  @Override
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

      SchemaClassImpl cls = classesRefs.get(key).getDelegate();

      if (cls == null) {
        throw new SchemaException(session,
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses(session).isEmpty()) {
        throw new SchemaException(
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses(session)
                + ". Remove the dependencies before trying to drop it again");
      }

      var cmd = "drop class `" + className + "` unsafe";
      // mark potentially dropped class as dirty
      markClassDirty(session, cls);
      markSuperClassesDirty(session, cls);
      markSubClassesDirty(session, cls);
      session.execute(cmd).close();
      reload(session);

      var localCache = session.getLocalCache();
      for (var collectionId : cls.getCollectionIds(session)) {
        localCache.freeCollection(collectionId);
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
  public int addBlobCollection(DatabaseSessionInternal session, int collectionId) {
    throw new SchemaException(session,
        "Not supported operation use instead DatabaseSession.addBlobCollection");
  }

  @Override
  public void removeBlobCollection(DatabaseSessionInternal session, String collectionName) {
    throw new SchemaException(session,
        "Not supported operation use instead DatabaseSession.dropCollection");
  }
}
