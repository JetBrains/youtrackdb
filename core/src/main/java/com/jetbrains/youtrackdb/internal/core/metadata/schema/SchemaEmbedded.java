package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

public class SchemaEmbedded extends SchemaShared {

  public SchemaEmbedded() {
    super();
  }

  @Override
  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int[] collectionIds,
      SchemaClassImpl... superClasses) {
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(className);
    if (wrongCharacter != null) {
      throw new SchemaException(session.getDatabaseName(),
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + className
              + "'");
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_CREATE);
    SchemaClassImpl result;
    var retry = 0;

    while (true) {
      try {
        result = doCreateClass(session, className, collectionIds, retry, superClasses);
        break;
      } catch (CollectionIdsAreEmptyException ignore) {
        acquireSchemaWriteLock(session);
        try {
          classes.remove(className);
          collectionIds = createCollections(session, className);
        } finally {
          releaseSchemaWriteLock(session, false);
        }
        retry++;
      }
    }
    return result;
  }

  @Override
  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int collections,
      SchemaClassImpl... superClasses) {
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(className);
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

      if (classes.containsKey(className)) {
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
        collectionIds = new int[] {-1};
      }

      doRealCreateClass(session, className, superClassesList,
          collectionIds);

      result = classes.get(className);
      // WAKE UP DB LIFECYCLE LISTENER
      for (var it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext();) {
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

      if (classes.containsKey(className)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }

      var cls = createClassInstance(className, collectionIds);

      classes.put(className, cls);

      if (superClasses != null && !superClasses.isEmpty()) {
        cls.setSuperClassesInternal(session, superClasses, true);
        for (var superClass : superClasses) {
          // UPDATE INDEXES
          final var collectionsToIndex = superClass.getPolymorphicCollectionIds();
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
                    .addCollectionToIndex(session, collectionName, index.getName(), true);
              }
            }
          }
        }
      }

      addCollectionClassMap(cls);

      if (txLocal && !session.isSeedingTxSchemaState()) {
        // Transaction-local create: only the tx-local schema metadata changed now. Record the
        // created class so the commit knows to write its new per-class record (the structural
        // collection/index allocation is reconciled at commit). The seeding guard is load-bearing:
        // copyForTx -> fromStream re-creates every committed class through this same path while
        // seeding the tx-local copy, so without the guard the seed would dump the entire committed
        // schema into the changed-class set and re-enter the mutex engage. This mirrors the seeding
        // guard the index-manager membership site already applies.
        var txState = session.getTxSchemaState();
        if (txState == null) {
          throw new IllegalStateException(
              "a tx-local create must run with a seeded tx-local schema state");
        }
        txState.markClassChanged(className);
      }

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected SchemaClassImpl createClassInstance(String className, int[] collectionIds) {
    return new SchemaClassEmbedded(this, className, collectionIds);
  }

  @Override
  @Nullable public SchemaClassImpl getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName,
      final SchemaClassImpl... superClasses) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock();
    try {
      var cls = classes.get(iClassName);
      if (cls != null) {
        return cls;
      }
    } finally {
      releaseSchemaReadLock();
    }

    SchemaClassImpl cls;

    int[] collectionIds = null;
    var retry = 0;

    while (true) {
      try {
        acquireSchemaWriteLock(session);
        try {
          cls = classes.get(iClassName);
          if (cls != null) {
            return cls;
          }

          cls = doCreateClass(session, iClassName, collectionIds, retry, superClasses);
          addCollectionClassMap(cls);
        } finally {
          releaseSchemaWriteLock(session);
        }
        break;
      } catch (CollectionIdsAreEmptyException ignore) {
        acquireSchemaWriteLock(session);
        try {
          collectionIds = createCollections(session, iClassName);
        } finally {
          releaseSchemaWriteLock(session, false);
        }
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

      if (classes.containsKey(className) && retry == 0) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' already exists in current database");
      }

      checkCollectionsAreAbsent(collectionIds);

      if (collectionIds == null || collectionIds.length == 0) {
        collectionIds =
            createCollections(
                session,
                className,
                session.getStorage().getMinimumCollections());
      }
      List<SchemaClassImpl> superClassesList = new ArrayList<>();
      if (superClasses != null) {
        for (var superClass : superClasses) {
          if (superClass != null) {
            superClassesList.add(superClass);
          }
        }
      }

      doRealCreateClass(session, className, superClassesList,
          collectionIds);

      result = classes.get(className);
      for (var oSessionListener : session.getListeners()) {
        oSessionListener.onCreateClass(session, new SchemaClassProxy(result, session));
      }

    } finally {
      releaseSchemaWriteLock(session);
    }

    return result;
  }

  private int[] createCollections(DatabaseSessionEmbedded session, final String iClassName) {
    return createCollections(
        session, iClassName, session.getStorage().getMinimumCollections());
  }

  protected int[] createCollections(
      DatabaseSessionEmbedded session, String className, int minimumCollections) {
    var lowerName = className.toLowerCase(Locale.ENGLISH);

    if (internalClasses.contains(lowerName)) {
      // INTERNAL CLASS, SET TO 1
      minimumCollections = 1;
    }

    // Inside a schema transaction a create does not allocate a real storage collection: that is the
    // eager, self-committing allocation the metadata-first inversion removes, because it leaves a
    // stray collection on disk if the transaction rolls back. Instead the class carries a provisional
    // id (<= -2) the commit resolves to a real id once it creates the real collection inside the
    // commit's own atomic operation. The seeding guard mirrors the create-path recording guard in
    // createClassInternal: copyForTx -> fromStream re-creates every committed class through this same
    // path while seeding the tx-local copy, and a committed class already owns real collection ids,
    // so the seed must take the eager branch (it loads existing collections, it does not allocate).
    final boolean provisional = txLocal && !session.isSeedingTxSchemaState();
    final TxSchemaState txState;
    if (provisional) {
      txState = session.getTxSchemaState();
      if (txState == null) {
        throw new IllegalStateException(
            "a tx-local collection allocation must run with a seeded tx-local schema state");
      }
    } else {
      txState = null;
    }

    var collectionIds = new int[minimumCollections];
    for (var i = 0; i < minimumCollections; i++) {
      // The counter-only name (c_<counter>, no class-name component) still advances the tx-local
      // collection counter so a single transaction creating several classes generates distinct
      // names; the commit uses these names when it creates the real collections. The name is
      // computed even on the provisional branch to keep the counter in step with the eager branch.
      var collectionName = nextCollectionName(session);
      if (provisional) {
        // Carry the generated name with the provisional id: the commit creates the real collection
        // under this name, and the tx-local counter has advanced past it by commit time, so the
        // commit cannot regenerate it.
        collectionIds[i] = txState.allocateProvisionalCollectionId(collectionName);
      } else {
        collectionIds[i] = session.addCollection(collectionName);
      }
    }

    return collectionIds;
  }

  protected void checkCollectionsAreAbsent(final int[] iCollectionIds) {
    if (iCollectionIds == null) {
      return;
    }

    for (var collectionId : iCollectionIds) {
      // A provisional id (<= -2) is validated like a real id — it must not already belong to a
      // class in the in-memory reverse map. Only the abstract-class marker is skipped.
      if (collectionId == ABSTRACT_COLLECTION_ID) {
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

  @Override
  public void dropClass(DatabaseSessionEmbedded session, final String className) {
    acquireSchemaWriteLock(session);
    try {
      // Outside the transaction-local path a class drop is not transactional and still throws on an
      // active transaction. On the transaction-local copy the drop rides the user transaction: the
      // metadata removal lands in the private copy and the structural reconciliation (collection
      // and engine deletion) is computed and applied at commit.
      if (!txLocal && session.getTransactionInternal().isActive()) {
        throw new IllegalStateException("Cannot drop a class inside a transaction");
      }

      if (className == null) {
        throw new IllegalArgumentException("Class name is null");
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

      var cls = classes.get(className);

      if (cls == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses().isEmpty()) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses()
                + ". Remove the dependencies before trying to drop it again");
      }

      doDropClass(session, className);

      if (!txLocal) {
        // The shared local-collection cache is only freed on the legacy top-level path; a tx-local
        // drop frees its collections at commit, alongside the structural reconciliation.
        var localCache = session.getLocalCache();
        for (var collectionId : cls.getCollectionIds()) {
          localCache.freeCollection(collectionId);
        }
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
      if (!txLocal && session.getTransactionInternal().isActive()) {
        throw new IllegalStateException("Cannot drop a class inside a transaction");
      }

      if (className == null) {
        throw new IllegalArgumentException("Class name is null");
      }

      session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

      final var cls = classes.get(className);
      if (cls == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + className + "' was not found in current database");
      }

      if (!cls.getSubclasses().isEmpty()) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '"
                + className
                + "' cannot be dropped because it has sub classes "
                + cls.getSubclasses()
                + ". Remove the dependencies before trying to drop it again");
      }

      checkEmbedded(session);

      for (var superClass : cls.getSuperClasses()) {
        // REMOVE DEPENDENCY FROM SUPERCLASS
        superClass.removeBaseClassInternal(session, cls);
      }

      if (txLocal) {
        // Transaction-local drop: only the schema metadata changes now. The collection and index
        // deletion is structural reconciliation that the commit computes from the committed-vs-
        // tx-local collection-id difference and applies inside the commit's own atomic operation,
        // so a rollback leaves the shared structure and the indexes' collection membership
        // untouched. Record the dropped class so the commit knows to delete its per-class record.
        var txState = session.getTxSchemaState();
        if (txState == null) {
          throw new IllegalStateException(
              "a tx-local drop must run with a seeded tx-local schema state");
        }
        txState.markClassChanged(className);
        // Drop the class's indexes with it, mirroring the non-transactional branch's eager
        // dropClassIndexes: each index associated with the dropped class in the transaction's
        // view is recorded into the overlay's drop category (recordDropped cancels a pending
        // same-tx create and records a committed drop), and the existing commit machinery then
        // deletes the engine, the files, the entity record, and the shared-registry entries.
        // Without this recording a dropped class's committed indexes survived the commit as
        // fully registered orphans over deleted collections (a commit-reconciliation seam
        // predating this recording), and a same-tx create-then-drop failed the whole commit
        // trying to build an engine over
        // the dropped class's collection. The overlay-aware getClassIndexes resolves the
        // effective set — committed indexes (through the rename map when the class was renamed
        // earlier in this transaction) plus tx-created handles minus already-dropped names —
        // and must run BEFORE the recordClassDropped purge below, which removes the rename
        // entry this resolution may need.
        var indexManager = session.getSharedContext().getIndexManager();
        var classIndexes = indexManager.getClassIndexes(session, className);
        if (!classIndexes.isEmpty()) {
          var overlayForDrops = txState.ensureIndexOverlay();
          for (var index : classIndexes) {
            overlayForDrops.recordDropped(index.getName());
          }
        }
        // Keep the class-rename bookkeeping sound: a dropped class's rename entry must be
        // purged and its committed name retired, or a later class recycling one of its names
        // would wrongly re-associate the dropped class's committed indexes at commit. Only an
        // existing overlay needs the hook — with no overlay there are no renames to purge.
        var overlay = txState.getIndexOverlay();
        if (overlay != null) {
          overlay.recordClassDropped(className);
        }
      } else {
        for (var id : cls.getCollectionIds()) {
          if (id != -1) {
            deleteCollection(session, id);
          }
        }

        dropClassIndexes(session, cls);
      }

      classes.remove(className);

      removeCollectionClassMap(cls);

      // WAKE UP DB LIFECYCLE LISTENER
      for (var it = YouTrackDBEnginesManager.instance()
          .getDbLifecycleListeners();
          it.hasNext();) {
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

  @Override
  protected SchemaClassImpl createClassInstance(String name) {
    return new SchemaClassEmbedded(this, name);
  }

  @Override
  protected SchemaShared newInstanceForCopy() {
    return new SchemaEmbedded();
  }

  private static void dropClassIndexes(DatabaseSessionEmbedded session, final SchemaClassImpl cls) {
    final var indexManager = session.getSharedContext().getIndexManager();

    for (final var index : indexManager.getClassIndexes(session, cls.getName())) {
      indexManager.dropIndex(session, index.getName());
    }
  }

  private static void deleteCollection(final DatabaseSessionEmbedded session,
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

  private void removeCollectionClassMap(final SchemaClassImpl cls) {
    for (var collectionId : cls.getCollectionIds()) {
      // Remove provisional ids too so a class created-then-dropped within the same transaction
      // leaves no pending-real entry behind. Only the abstract-class marker is skipped.
      if (collectionId == ABSTRACT_COLLECTION_ID) {
        continue;
      }

      collectionsToClasses.remove(collectionId);
    }
  }

  @Override
  public void checkEmbedded(DatabaseSessionEmbedded session) {
  }

  void addCollectionForClass(
      DatabaseSessionEmbedded session, final int collectionId, final SchemaClassImpl cls) {
    acquireSchemaWriteLock(session);
    try {
      // A provisional id (<= -2) is registered like a real id in the in-memory reverse map; only the
      // abstract-class marker is skipped.
      if (collectionId == ABSTRACT_COLLECTION_ID) {
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

  void removeCollectionForClass(DatabaseSessionEmbedded session, int collectionId) {
    acquireSchemaWriteLock(session);
    try {
      // A provisional id (<= -2) is removed from the in-memory reverse map like a real id; only the
      // abstract-class marker is skipped.
      if (collectionId == ABSTRACT_COLLECTION_ID) {
        return;
      }

      checkEmbedded(session);

      collectionsToClasses.remove(collectionId);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }
}
