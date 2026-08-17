package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StaleIndexEngineException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineReference;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.RecordBytes;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.StorageReadResult;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * White-box tests for the lock-free commit-window primitives extracted out of the public
 * structural methods on {@link AbstractStorage}: {@code getIndexEngineWithStateLock},
 * {@code doAddIndexEngine} / {@code publishIndexEngine}, {@code doDeleteIndexEngine}, and
 * the {@code doCreateCollection} / {@code registerCollection} create/publish split.
 *
 * <p>Two properties are pinned. First, the public {@code addIndexEngine} /
 * {@code deleteIndexEngine} / {@code addCollection} wrappers still create, register, and
 * drop structures exactly as before the extraction (behavior preservation). Second — the
 * load-bearing one — {@code getIndexEngineWithStateLock} resolves an engine by id while the calling
 * thread holds {@code stateLock.writeLock()}, the situation a schema-carrying commit is in
 * once it takes the write lock from the start. The public {@code getIndexEngine} would
 * busy-spin forever there because it re-acquires {@code stateLock.readLock()} on the
 * non-reentrant {@code ScalableRWLock}; the lock-free resolver must not.
 *
 * <p>The class also pins the lock-free commit-window <i>record-read</i> substrate: while a
 * schema-carrying commit holds {@code stateLock.writeLock()}, it serializes and re-parses the
 * schema by reading records through {@code session.load}, which routes back into this storage's
 * {@code getPhysicalCollectionNameById} (the security check) and {@code readRecordInternal} (a
 * record cache miss). Both re-acquire {@code stateLock.readLock()} on the normal path and so
 * would deadlock the non-reentrant {@code ScalableRWLock} under the held write lock. The commit
 * opens a per-thread commit window ({@code enterCommitWindow()} / {@code exitCommitWindow()})
 * that makes those two methods skip the read lock; the tests prove a read resolves under the held
 * write lock when the window is open, the normal path is unchanged when it is closed, and the
 * window's depth counter composes and closes balanced.
 *
 * <p>The test lives in the storage package so it can read {@code storage.stateLock} for
 * white-box lock-holding, and uses reflection only for the {@code private} members it
 * must reach directly: the {@code indexEngines} registry (to find an engine's internal id),
 * {@code getIndexEngineWithStateLock(int)}, and {@code isCommitWindowActive()} (the window predicate).
 */
public class AbstractStorageCommitPrimitivesTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name) collisions
  // when these tests run in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.MEMORY, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  // ---- Public-wrapper behavior preservation ----

  /** Reading an attached identifier never nests or releases the caller's storage state read lock. */
  @Test
  public void indexIdentifierAccessorDoesNotTouchStorageStateLock() {
    var cls = db.createVertexClass("PureIndexIdentifier");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "PureIndexIdentifier.name";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var index = db.getSharedContext().getIndexManager().getIndex(indexName);
    var storage = (AbstractStorage) db.getStorage();

    storage.stateLock.readLock().lock();
    try {
      assertThat(index.getIndexId()).isGreaterThanOrEqualTo(0);
      assertThat(storage.stateLock.isReadLockedByCurrentThread()).isTrue();
    } finally {
      storage.stateLock.readLock().unlock();
    }
  }

  /** Older engine constructors also allocate unique process-local generations through storage. */
  @Test
  public void olderEngineConstructorAllocatesUniqueGenerations() {
    var storage = (AbstractStorage) db.getStorage();

    var first = new BTreeSingleValueIndexEngine(7, 101, "legacy-first", storage, 4);
    var second = new BTreeSingleValueIndexEngine(7, 102, "legacy-second", storage, 4);

    assertThat(first.getEngineReference().slot()).isEqualTo(7);
    assertThat(second.getEngineReference().slot()).isEqualTo(7);
    assertThat(second.getEngineReference().generation())
        .isGreaterThan(first.getEngineReference().generation());
  }

  /** A transaction-created engine receives a fresh generation when it reuses a dropped slot. */
  @Test
  public void reusedIndexEngineSlotReceivesNewGeneration() throws Exception {
    var cls = db.createVertexClass("GenerationReuse");
    cls.createProperty("name", PropertyType.STRING);
    var firstName = "GenerationReuse.first";
    cls.createIndex(firstName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    var storage = (AbstractStorage) db.getStorage();
    var firstReference = findEngineByName(storage, firstName).getEngineReference();
    assertThat(firstReference).isNotNull();
    var firstIndex = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(firstName);
    var firstIndexId = firstIndex.getIndexId();

    var indexManager = db.getSharedContext().getIndexManager();
    db.executeInTx(tx -> indexManager.dropIndex(db, firstName));

    var secondName = "GenerationReuse.second";
    db.begin();
    db.getMetadata().getSchema().getClass("GenerationReuse")
        .createIndex(secondName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    db.commit();

    var secondReference = findEngineByName(storage, secondName).getEngineReference();
    assertThat(secondReference).isNotNull();
    assertThat(secondReference.slot()).isEqualTo(firstReference.slot());
    assertThat(secondReference.generation()).isGreaterThan(firstReference.generation());
    var futureReference = new IndexEngineReference(
        secondReference.slot(), secondReference.apiVersion(), secondReference.generation() + 1);
    assertThatThrownBy(() -> storage.attachIndexEngineOwner(
        firstIndexId, firstIndex.getIdentity(), futureReference))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-monotonically");
  }

  // The public addIndexEngine wrapper (now allocate-id + doAddIndexEngine + publish) must
  // still register the engine so that getIndexEngine resolves it by id and the registry
  // carries it by name. Creating a UNIQUE index drives addIndexEngine internally.
  @Test
  public void addIndexEngineWrapperRegistersEngineResolvableByGetIndexEngine()
      throws Exception {
    SchemaClass cls = db.createVertexClass("PersonAddIdx");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonAddIdx_name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    var storage = (AbstractStorage) db.getStorage();
    var engine = findEngineByName(storage, "PersonAddIdx_name");

    assertThat(engine)
        .as("addIndexEngine wrapper must publish the created engine into indexEngines")
        .isNotNull();

    // The public getIndexEngine must resolve the same engine by the engine's id. The id in
    // indexEngines is the internal id; getIndexEngine takes the external (API-tagged) id, so
    // re-tag it the same way the storage does on the way out of addIndexEngine.
    int externalId = externalIdOf(engine);
    assertThat(storage.getIndexEngine(externalId))
        .as("getIndexEngine must resolve the engine the wrapper registered")
        .isSameAs(engine);
  }

  // The public deleteIndexEngine wrapper (now doDeleteIndexEngine + deferred map mutation)
  // must still unregister the engine: after the drop, the registry no longer carries it.
  @Test
  public void deleteIndexEngineWrapperUnregistersEngine() throws Exception {
    SchemaClass cls = db.createVertexClass("PersonDelIdx");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("PersonDelIdx_tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    var storage = (AbstractStorage) db.getStorage();
    assertThat(findEngineByName(storage, "PersonDelIdx_tag"))
        .as("precondition: the index engine is registered before the drop")
        .isNotNull();

    db.command("DROP INDEX PersonDelIdx_tag");

    assertThat(findEngineByName(storage, "PersonDelIdx_tag"))
        .as("deleteIndexEngine wrapper must remove the engine from the name registry")
        .isNull();
  }

  // The public addCollection wrapper (now doCreateCollection + registerCollection) must
  // still publish the collection into the in-memory registry: a freshly created class's
  // collections appear in the name registry and resolve to real ids. Collection names are
  // counter-only (c_<counter>, no class-name component), so the class's collections are
  // resolved through its collection ids.
  @Test
  public void addCollectionWrapperPublishesCollectionIntoRegistry() {
    var cls = db.createVertexClass("CollPublishProbe");

    var storage = (AbstractStorage) db.getStorage();

    var published =
        java.util.Arrays.stream(cls.getCollectionIds())
            .mapToObj(db::getCollectionNameById)
            .toList();
    assertThat(published)
        .as("addCollection wrapper must publish the class collections into the name registry")
        .isNotEmpty();
    for (var collectionName : published) {
      assertThat(storage.getCollectionIdByName(collectionName))
          .as("each published collection must resolve to a non-negative real id")
          .isGreaterThanOrEqualTo(0);
    }
  }

  // ---- The load-bearing property: getIndexEngineWithStateLock resolves under a held write lock ----

  // getIndexEngineWithStateLock must resolve an engine by internal id without taking stateLock, so a
  // schema-carrying commit holding stateLock.writeLock() can reach engines during the
  // index-apply path without the non-reentrant self-deadlock the public getIndexEngine
  // would cause. The test holds the write lock on the calling thread, then resolves.
  //
  // A regression that re-took stateLock.readLock() here would busy-spin forever on the
  // non-reentrant ScalableRWLock (it loops Thread.yield while the write lock is held, with no
  // same-thread relief) rather than throwing — and core surefire sets no fork timeout, so the
  // hang would wedge the build instead of failing red. The bound converts that hang into a
  // clean TestTimedOutException naming this method, matching the ScalableRWLockTest convention
  // in this package.
  @Test(timeout = 30_000)
  public void heldStateLockResolverWorksForReadLockAndCommitWindow() throws Exception {
    db.activateOnCurrentThread();

    SchemaClass cls = db.createVertexClass("PersonLockFree");
    cls.createProperty("email", PropertyType.STRING);
    cls.createIndex("PersonLockFree_email", SchemaClass.INDEX_TYPE.UNIQUE, "email");

    var storage = (AbstractStorage) db.getStorage();
    var engine = findEngineByName(storage, "PersonLockFree_email");
    assertThat(engine).as("precondition: the engine is registered").isNotNull();
    int externalId = externalIdOf(engine);

    storage.stateLock.readLock().lock();
    try {
      assertThat(storage.getIndexEngineWithStateLock(externalId)).isSameAs(engine);
    } finally {
      storage.stateLock.readLock().unlock();
    }

    storage.stateLock.writeLock().lock();
    try {
      storage.enterCommitWindow();
      try {
        assertThat(storage.getIndexEngineWithStateLock(externalId)).isSameAs(engine);
      } finally {
        storage.exitCommitWindow();
      }
    } finally {
      storage.stateLock.writeLock().unlock();
    }
  }

  /** The lock-free resolver rejects callers that provide neither supported state-lock proof. */
  @Test
  public void heldStateLockResolverRejectsUnlockedCaller() throws Exception {
    SchemaClass cls = db.createVertexClass("ResolverPrecondition");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("ResolverPrecondition_value", SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var engine = findEngineByName(storage, "ResolverPrecondition_value");

    assertThatThrownBy(() -> storage.getIndexEngineWithStateLock(externalIdOf(engine)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("state lock")
        .hasMessageContaining("commit window");
  }

  /** Indexed data commits must retain their outer state read lock through engine resolution. */
  @Test(timeout = 30_000)
  public void indexLockResolutionDoesNotDropOuterStateReadLock() throws Exception {
    db.activateOnCurrentThread();
    SchemaClass cls = db.createVertexClass("IndexedCommitLock");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("IndexedCommitLock_value", SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = db.getSharedContext().getIndexManager().getIndex("IndexedCommitLock_value");
    db.begin();
    var operation = db.getActiveTransaction().getAtomicOperation();

    storage.stateLock.readLock().lock();
    try {
      index.acquireAtomicExclusiveLock(operation);
      try (var executor = Executors.newSingleThreadExecutor()) {
        var writerEntered =
            executor.submit(
                () -> {
                  if (!storage.stateLock.writeLock().tryLock()) {
                    return false;
                  }
                  try {
                    return true;
                  } finally {
                    storage.stateLock.writeLock().unlock();
                  }
                });
        assertThat(writerEntered.get(10, TimeUnit.SECONDS))
            .as("engine resolution must not release the commit's outer state read lock")
            .isFalse();
      }
    } finally {
      storage.stateLock.readLock().unlock();
      db.rollback();
    }
  }

  /** A stale engine identifier reloads without re-entering either held state-lock mode. */
  @Test(timeout = 30_000)
  public void staleIndexLockResolutionRetainsCallerStateLock() throws Exception {
    db.activateOnCurrentThread();
    SchemaClass cls = db.createVertexClass("StaleIndexedCommitLock");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("StaleIndexedCommitLock_value", SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager()
        .getIndex("StaleIndexedCommitLock_value");
    db.begin();
    var operation = db.getActiveTransaction().getAtomicOperation();

    storage.stateLock.readLock().lock();
    try {
      index.setEngineIdentifierForTest(Integer.MAX_VALUE);
      index.acquireAtomicExclusiveLock(operation);
      try (var executor = Executors.newSingleThreadExecutor()) {
        var writerEntered =
            executor.submit(
                () -> {
                  if (!storage.stateLock.writeLock().tryLock()) {
                    return false;
                  }
                  try {
                    return true;
                  } finally {
                    storage.stateLock.writeLock().unlock();
                  }
                });
        assertThat(writerEntered.get(10, TimeUnit.SECONDS))
            .as("stale-id reload must retain the outer state read lock")
            .isFalse();
      }
    } finally {
      storage.stateLock.readLock().unlock();
    }

    storage.stateLock.writeLock().lock();
    try {
      storage.enterCommitWindow();
      try {
        index.setEngineIdentifierForTest(Integer.MAX_VALUE);
        index.acquireAtomicExclusiveLock(operation);
      } finally {
        storage.exitCommitWindow();
      }
    } finally {
      storage.stateLock.writeLock().unlock();
      db.rollback();
    }
  }

  /** Caller-owned create and update operations persist versions without nested transactions. */
  @Test
  public void callerOwnedRecordCreateAndUpdateUseExpectedVersion() throws Exception {
    var cls = db.createVertexClass("PrimitiveRecords");
    int collectionId = cls.getCollectionIds()[0];
    var storage = (AbstractStorage) db.getStorage();
    var rid = new ChangeableRecordId(collectionId, RecordIdInternal.COLLECTION_POS_INVALID);
    byte[] initialContent = new byte[] {1, 2, 3};

    long initialVersion;
    storage.stateLock.readLock().lock();
    try {
      initialVersion =
          storage
              .getAtomicOperationsManager()
              .calculateInsideAtomicOperation(
                  operation -> storage.createRecordInsideAtomicOperation(
                      operation, rid, initialContent, RecordBytes.RECORD_TYPE));
    } finally {
      storage.stateLock.readLock().unlock();
    }

    assertThat(rid.isPersistent()).isTrue();
    assertThat(initialVersion).isGreaterThanOrEqualTo(0);

    byte[] updatedContent = new byte[] {4, 5, 6};
    long updatedVersion;
    storage.stateLock.readLock().lock();
    try {
      updatedVersion =
          storage
              .getAtomicOperationsManager()
              .calculateInsideAtomicOperation(
                  operation -> storage.updateRecordInsideAtomicOperation(
                      operation,
                      rid,
                      updatedContent,
                      initialVersion,
                      RecordBytes.RECORD_TYPE));
    } finally {
      storage.stateLock.readLock().unlock();
    }

    assertThat(updatedVersion).isGreaterThan(initialVersion);
    var stored =
        storage
            .getAtomicOperationsManager()
            .calculateInsideAtomicOperation(operation -> storage.readRecord(rid, operation));
    assertThat(((RawBuffer) stored).buffer()).isEqualTo(updatedContent);
  }

  /** A stale caller-owned update rolls back and preserves the last durable record bytes. */
  @Test
  public void callerOwnedRecordUpdateRejectsStaleExpectedVersion() throws Exception {
    var cls = db.createVertexClass("PrimitiveVersionConflict");
    var storage = (AbstractStorage) db.getStorage();
    var rid =
        new ChangeableRecordId(
            cls.getCollectionIds()[0], RecordIdInternal.COLLECTION_POS_INVALID);
    byte[] durableContent = new byte[] {7, 8};

    storage.stateLock.readLock().lock();
    try {
      storage
          .getAtomicOperationsManager()
          .calculateInsideAtomicOperation(
              operation -> storage.createRecordInsideAtomicOperation(
                  operation, rid, durableContent, RecordBytes.RECORD_TYPE));
    } finally {
      storage.stateLock.readLock().unlock();
    }

    storage.stateLock.readLock().lock();
    try {
      assertThatThrownBy(
          () -> storage
              .getAtomicOperationsManager()
              .calculateInsideAtomicOperation(
                  operation -> storage.updateRecordInsideAtomicOperation(
                      operation,
                      new RecordId(rid),
                      new byte[] {9},
                      Long.MIN_VALUE,
                      RecordBytes.RECORD_TYPE)))
          .isInstanceOf(ConcurrentModificationException.class);
    } finally {
      storage.stateLock.readLock().unlock();
    }

    var stored =
        storage
            .getAtomicOperationsManager()
            .calculateInsideAtomicOperation(operation -> storage.readRecord(rid, operation));
    assertThat(((RawBuffer) stored).buffer()).isEqualTo(durableContent);
  }

  /** Caller-owned record primitives fail before taking a second collection lock. */
  @Test
  public void callerOwnedRecordWritesRejectSecondCollection() {
    var firstClass = db.createVertexClass("PrimitiveFirstCollection");
    var secondClass = db.createVertexClass("PrimitiveSecondCollection");
    var firstRid =
        new ChangeableRecordId(
            firstClass.getCollectionIds()[0], RecordIdInternal.COLLECTION_POS_INVALID);
    var secondRid =
        new ChangeableRecordId(
            secondClass.getCollectionIds()[0], RecordIdInternal.COLLECTION_POS_INVALID);
    var storage = (AbstractStorage) db.getStorage();

    storage.stateLock.readLock().lock();
    try {
      assertThatThrownBy(
          () -> storage
              .getAtomicOperationsManager()
              .executeInsideAtomicOperation(
                  operation -> {
                    storage.createRecordInsideAtomicOperation(
                        operation, firstRid, new byte[] {1}, RecordBytes.RECORD_TYPE);
                    storage.createRecordInsideAtomicOperation(
                        operation, secondRid, new byte[] {2}, RecordBytes.RECORD_TYPE);
                  }))
          .isInstanceOf(com.jetbrains.youtrackdb.internal.core.exception.StorageException.class)
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .rootCause()
          .hasMessageContaining("one collection")
          .hasMessageContaining("ascending identifier order");
    } finally {
      storage.stateLock.readLock().unlock();
    }
  }

  // ---- The load-bearing property: record reads resolve lock-free in the commit window ----

  // getPhysicalCollectionNameById is the security-check leg of the commit-window record read
  // (session.executeReadRecord -> session.getCollectionNameById -> storage). With the commit
  // window open on a thread that holds stateLock.writeLock(), it must resolve the collection
  // name without re-taking stateLock.readLock(); re-taking it would busy-spin forever on the
  // non-reentrant ScalableRWLock. The 30 s bound converts that hang into a clean
  // TestTimedOutException naming this method (core surefire sets no fork timeout).
  @Test(timeout = 30_000)
  public void getPhysicalCollectionNameByIdResolvesLockFreeWhileWriteLockHeld() throws Exception {
    db.activateOnCurrentThread();

    var nameLookupCls = db.createVertexClass("NameLookupProbe");
    var storage = (AbstractStorage) db.getStorage();

    // Pick one real collection id of the class (names are counter-only, so the class's own id
    // list is the link) and its expected name from the normal (read-lock) path, captured before
    // we take the write lock.
    int collectionId = nameLookupCls.getCollectionIds()[0];
    var collectionName = db.getCollectionNameById(collectionId);
    assertThat(collectionId).as("precondition: a real collection id").isGreaterThanOrEqualTo(0);

    // Hold the write lock exactly as a schema-carrying commit does, then open the commit window
    // and resolve the name lock-free. Without the window this call would deadlock.
    storage.stateLock.writeLock().lock();
    try {
      storage.enterCommitWindow();
      try {
        assertThat(storage.getPhysicalCollectionNameById(collectionId))
            .as("getPhysicalCollectionNameById must resolve lock-free under the held write lock")
            .isEqualTo(collectionName);
      } finally {
        storage.exitCommitWindow();
      }
    } finally {
      storage.stateLock.writeLock().unlock();
    }
  }

  // readRecordInternal is the record-cache-miss leg of the commit-window record read
  // (session.executeReadRecord -> storage.readRecord -> readRecordInternal). With the commit
  // window open under the held write lock, reading a persistent record must resolve its raw
  // buffer lock-free rather than deadlocking on the read-lock re-acquire. The atomic operation
  // the read needs is started under segmentLock, which is disjoint from stateLock, so it does
  // not interact with the held write lock.
  @Test(timeout = 30_000)
  public void readRecordResolvesLockFreeWhileWriteLockHeld() throws Exception {
    db.activateOnCurrentThread();

    db.createVertexClass("RecordReadProbe");

    db.begin();
    var v = db.newVertex("RecordReadProbe");
    v.setProperty("k", "v");
    db.commit();
    var rid = (RecordIdInternal) v.getIdentity();
    assertThat(rid.isPersistent()).as("precondition: the saved record has a persistent rid")
        .isTrue();

    var storage = (AbstractStorage) db.getStorage();

    storage.stateLock.writeLock().lock();
    try {
      storage.enterCommitWindow();
      try {
        StorageReadResult result =
            storage.getAtomicOperationsManager()
                .calculateInsideAtomicOperation(op -> storage.readRecord(rid, op));
        assertThat(result)
            .as("readRecord must resolve a persistent record lock-free under the held write lock")
            .isInstanceOf(RawBuffer.class);
        assertThat(((RawBuffer) result).buffer())
            .as("the resolved raw buffer must be non-empty")
            .isNotEmpty();
      } finally {
        storage.exitCommitWindow();
      }
    } finally {
      storage.stateLock.writeLock().unlock();
    }
  }

  // The commit window is a depth counter, so a nested enter/exit pair leaves the window open
  // until the outermost exit; a leaked window would make later reads on a pooled thread skip the
  // read lock unsafely. This pins the compose-and-close-balanced contract via the private
  // isCommitWindowActive() predicate, and confirms the normal record-read path reverts to taking
  // the read lock once the window closes (the pure-data fast path is unaffected).
  @Test
  public void commitWindowDepthComposesAndClosesBalanced() throws Exception {
    var storage = (AbstractStorage) db.getStorage();

    Method active = AbstractStorage.class.getDeclaredMethod("isCommitWindowActive");
    active.setAccessible(true);

    assertThat((boolean) active.invoke(storage))
        .as("window is closed before any enter").isFalse();

    storage.enterCommitWindow();
    assertThat((boolean) active.invoke(storage))
        .as("window opens on the first enter").isTrue();

    storage.enterCommitWindow();
    storage.exitCommitWindow();
    assertThat((boolean) active.invoke(storage))
        .as("a nested enter/exit pair leaves the window open at depth 1").isTrue();

    storage.exitCommitWindow();
    assertThat((boolean) active.invoke(storage))
        .as("the outermost exit closes the window").isFalse();

    // Negative control: with the window closed, the predicate must report inactive, so the
    // record-read path below takes the read-lock branch rather than running lock-free. This is
    // the branch-decision assertion the value-equality check alone cannot make — name equality
    // is identical under either branch in a single-threaded test, so a regression that left the
    // predicate stuck-true (the worst-case leak) would pass the equality check silently. Pinning
    // isCommitWindowActive() false here catches that regression.
    assertThat((boolean) active.invoke(storage))
        .as("outside the window the predicate is inactive — the read takes stateLock.readLock()")
        .isFalse();

    // After the window closes, the normal record-read path resolves with the read lock again.
    // (The class's collection is resolved via its id — counter-only names carry no class name.)
    var postWindowCls = db.createVertexClass("PostWindowProbe");
    var collectionName = db.getCollectionNameById(postWindowCls.getCollectionIds()[0]);
    assertThat(storage.getPhysicalCollectionNameById(storage.getCollectionIdByName(collectionName)))
        .as("the normal read-lock path still resolves once the window is closed")
        .isEqualTo(collectionName);
  }

  // The substrate's most dangerous failure mode is a window left open on a thread: a single
  // unbalanced enterCommitWindow() (a missing finally, or a commit that throws between enter and
  // exit) leaves the depth positive, so a later, unrelated read on the same thread silently skips
  // stateLock.readLock() and races a concurrent registrar on the plain collections list. This pins
  // that a leaked (unbalanced) enter leaves the window active rather than self-healing — the
  // predicate the production code relies on must reflect the leak so the hazard is observable. The
  // matching exit in the finally cleans up so the worker thread is not poisoned for sibling tests.
  @Test
  public void leakedWindowStaysActiveOnTheSameThread() throws Exception {
    var storage = (AbstractStorage) db.getStorage();
    Method active = AbstractStorage.class.getDeclaredMethod("isCommitWindowActive");
    active.setAccessible(true);

    storage.enterCommitWindow(); // deliberately NOT balanced inside the try
    try {
      assertThat((boolean) active.invoke(storage))
          .as("a leaked (unbalanced) enter leaves the window active — the pooled-thread hazard")
          .isTrue();
    } finally {
      storage.exitCommitWindow(); // clean up so this thread is not poisoned for sibling tests
    }
  }

  // The pooled-thread-reuse hazard is the leaked window's real-world shape: the storage runs on
  // pooled threads, so a window left open (or stale state from a prior task) survives task
  // boundaries on a reused worker. exitCommitWindow() remove()s the ThreadLocal once the depth
  // returns to zero precisely so a subsequent unrelated task on the same thread starts from a fresh
  // closed window. This runs a balanced enter/exit on a single-thread executor, then re-uses that
  // exact thread for a second task and asserts the window reads closed there — proving the depth
  // does not leak across tasks and the predicate self-heals after a balanced close. Running both
  // tasks on a single-thread ExecutorService guarantees thread reuse (a @Test(timeout) watchdog
  // thread is not the production pool, so it would not exercise the reuse path).
  @Test(timeout = 30_000)
  public void balancedWindowDoesNotLeakAcrossTasksOnAReusedPooledThread() throws Exception {
    var storage = (AbstractStorage) db.getStorage();
    Method active = AbstractStorage.class.getDeclaredMethod("isCommitWindowActive");
    active.setAccessible(true);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      // Task 1: a balanced enter/exit pair. After it returns the depth must be zero and, thanks to
      // the remove()-at-zero hardening, the ThreadLocal cell is cleared on this worker thread.
      Boolean activeInsideTask1 =
          pool.submit(
              (Callable<Boolean>) () -> {
                storage.enterCommitWindow();
                try {
                  return (boolean) active.invoke(storage);
                } finally {
                  storage.exitCommitWindow();
                }
              })
              .get(10, TimeUnit.SECONDS);
      assertThat(activeInsideTask1)
          .as("the window is open inside the balanced task while the depth is positive")
          .isTrue();

      // Task 2: a different, unrelated task on the SAME pooled thread. It must observe a closed
      // window — no leftover depth from task 1. A regression that failed to reset the depth at zero
      // would leak the window here and make this read lock-free unsafely.
      Boolean activeInTask2 =
          pool.submit((Callable<Boolean>) () -> (boolean) active.invoke(storage))
              .get(10, TimeUnit.SECONDS);
      assertThat(activeInTask2)
          .as("a later unrelated task on the reused pooled thread sees a fresh closed window")
          .isFalse();
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
          .as("the single-thread executor terminates cleanly").isTrue();
    }
  }

  // An over-exit (one stray exitCommitWindow without a matching enter, e.g. a mis-placed finally)
  // must not corrupt the per-thread depth so that a later legitimate window silently reads closed.
  // In production (asserts disabled) the over-exit is absorbed by the clamp in exitCommitWindow,
  // which leaves the depth at zero rather than driving it negative; a negative depth would make the
  // next enterCommitWindow leave the counter at or below zero, so isCommitWindowActive() reads
  // false while the commit holds the write lock and the next lock-free-intended read re-acquires
  // stateLock.readLock() and re-introduces the busy-spin deadlock the substrate removes.
  //
  // Under -ea (this test module) the assert in exitCommitWindow fires first on the unbalanced call,
  // before the clamp runs, so the over-exit is caught loudly — and because the decrement sits after
  // the assert the depth is never written, leaving the cell at zero exactly as the production clamp
  // would. This test pins both halves of that contract: the unbalanced exit is detected under -ea,
  // and the depth is left at zero so a subsequent legitimate window opens normally.
  @Test
  public void overExitIsDetectedUnderAssertionsAndLeavesDepthClampedAtZero() throws Exception {
    var storage = (AbstractStorage) db.getStorage();
    Method active = AbstractStorage.class.getDeclaredMethod("isCommitWindowActive");
    active.setAccessible(true);

    assertThat((boolean) active.invoke(storage))
        .as("precondition: the window starts closed on this thread").isFalse();

    // A stray over-exit on a closed window. Under -ea the assert in exitCommitWindow fires; the
    // decrement after it never runs, so the depth stays at zero rather than going negative.
    AssertionError caught = null;
    try {
      storage.exitCommitWindow();
    } catch (AssertionError e) {
      caught = e;
    }
    assertThat(caught)
        .as("under -ea an unbalanced exit is detected loudly rather than silently corrupting depth")
        .isNotNull();

    assertThat((boolean) active.invoke(storage))
        .as("the over-exit left the depth clamped at zero, so the window reads closed")
        .isFalse();

    // A legitimate window opened after the over-exit must still report active: the depth was not
    // skewed negative, so this enter takes it cleanly to one.
    storage.enterCommitWindow();
    try {
      assertThat((boolean) active.invoke(storage))
          .as("a window opened after an over-exit still opens — the depth was not driven negative")
          .isTrue();
    } finally {
      storage.exitCommitWindow();
    }
  }

  /** The public resolver finds one owner in a non-zero slot and returns its packed identifier. */
  @Test
  public void ownerResolverReturnsPackedIdentifierForExactOwner() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionExact");
    cls.createProperty("first", PropertyType.STRING);
    cls.createProperty("second", PropertyType.STRING);
    cls.createIndex("OwnerResolutionExact.first", SchemaClass.INDEX_TYPE.UNIQUE, "first");
    var indexName = "OwnerResolutionExact.second";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "second");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var engine = findEngineByName(storage, indexName);

    assertThat(engine.getId()).isGreaterThan(0);
    assertThat(storage.resolveIndexEngineByOwner(index.getIdentity()).engineIdentifier())
        .isEqualTo(externalIdOf(engine));
  }

  /** A replacement object in the same slot remains resolvable after binding the same owner. */
  @Test
  public void ownerResolverFindsSameOwnerReplacementInSameSlot() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionReplacement");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionReplacement.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var engines = indexEngines(storage);
    var original = (BTreeSingleValueIndexEngine) findEngineByName(storage, indexName);
    var replacement = replacementEngine(storage, original, "same-owner");
    replacement.getEngineReference().bindOwner(index.getIdentity());

    engines.set(original.getId(), replacement);
    try {
      assertThat(storage.resolveIndexEngineByOwner(index.getIdentity()).engineIdentifier())
          .isEqualTo(externalIdOf(replacement));
    } finally {
      engines.set(original.getId(), original);
    }
  }

  /** A descriptor with no registered owner fails closed without poisoning the storage. */
  @Test
  public void ownerResolverRejectsMissingOwnerWithoutStoragePoisoning() {
    var storage = (AbstractStorage) db.getStorage();
    var missingOwner = new RecordId(91, 17);

    assertThatThrownBy(() -> storage.resolveIndexEngineByOwner(missingOwner))
        .isInstanceOf(StaleIndexEngineException.class)
        .hasMessageContaining(missingOwner.toString())
        .hasMessageContaining("no registered engine");
    assertThat(storage.getStatus().name()).isEqualTo("OPEN");
  }

  /** A same-slot engine bound to another descriptor cannot satisfy resolution for the old owner. */
  @Test
  public void ownerResolverRejectsForeignOwnerInSameSlot() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionForeign");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionForeign.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var engines = indexEngines(storage);
    var original = (BTreeSingleValueIndexEngine) findEngineByName(storage, indexName);
    var replacement = replacementEngine(storage, original, "foreign-owner");
    var foreignOwner = new RecordId(92, 18);
    replacement.getEngineReference().bindOwner(foreignOwner);

    engines.set(original.getId(), replacement);
    try {
      assertThatThrownBy(() -> storage.resolveIndexEngineByOwner(index.getIdentity()))
          .isInstanceOf(StaleIndexEngineException.class)
          .hasMessageContaining(index.getIdentity().toString())
          .hasMessageContaining("no registered engine");
    } finally {
      engines.set(original.getId(), original);
    }
  }

  /** Two registered engines carrying one owner violate the storage ownership invariant. */
  @Test
  public void ownerResolverRejectsDuplicateOwner() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionDuplicate");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionDuplicate.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var original = (BTreeSingleValueIndexEngine) findEngineByName(storage, indexName);
    var engines = indexEngines(storage);
    var duplicate = new BTreeSingleValueIndexEngine(
        engines.size(), original.getFileBaseId() + 10_000, "duplicate-owner", storage, 4);
    duplicate.getEngineReference().bindOwner(index.getIdentity());

    engines.add(duplicate);
    try {
      assertThatThrownBy(() -> storage.resolveIndexEngineByOwner(index.getIdentity()))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining(index.getIdentity().toString())
          .hasMessageContaining("Multiple registered index engines");
    } finally {
      engines.remove(engines.size() - 1);
    }
  }

  /** Null registry slots are ignored while the resolver checks every possible duplicate owner. */
  @Test
  public void ownerResolverSkipsNullEngineSlots() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionNullSlot");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionNullSlot.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var engine = findEngineByName(storage, indexName);
    var engines = indexEngines(storage);

    engines.add(null);
    try {
      assertThat(storage.resolveIndexEngineByOwner(index.getIdentity()).engineIdentifier())
          .isEqualTo(externalIdOf(engine));
    } finally {
      engines.remove(engines.size() - 1);
    }
  }

  /** A reference-less engine is skipped while another engine resolves for the requested owner. */
  @Test
  public void ownerResolverSkipsReferenceLessEngineAndFindsRequestedOwner() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionReferenceLessPeer");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionReferenceLessPeer.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var expectedEngine = findEngineByName(storage, indexName);
    var engines = indexEngines(storage);
    engines.add(Mockito.mock(BaseIndexEngine.class));
    try {
      assertThat(storage.resolveIndexEngineByOwner(index.getIdentity()).engineIdentifier())
          .isEqualTo(externalIdOf(expectedEngine));
    } finally {
      engines.remove(engines.size() - 1);
    }
  }

  /** A stale handle for a reference-less engine fails closed with the typed stale exception. */
  @Test
  public void referenceLessEngineHandleFailsClosedWithStaleException() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionReferenceLessHandle");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionReferenceLessHandle.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var engines = indexEngines(storage);
    var original = findEngineByName(storage, indexName);
    var referenceLessEngine = Mockito.mock(BaseIndexEngine.class);
    Mockito.when(referenceLessEngine.getId()).thenReturn(original.getId());
    Mockito.when(referenceLessEngine.getName()).thenReturn(indexName);
    engines.set(original.getId(), referenceLessEngine);
    setIndexIdentifier(index, index.getIndexId() | 1_000_000);
    try {
      assertThatThrownBy(() -> index.getStatistics(db))
          .isExactlyInstanceOf(StaleIndexEngineException.class)
          .hasMessageContaining(index.getIdentity().toString());
    } finally {
      engines.set(original.getId(), original);
    }
  }

  /** A restored reference-less engine cannot poison owner resolution for another index. */
  @Test
  public void restoredReferenceLessEngineDoesNotPoisonUnrelatedOwnerResolution() throws Exception {
    var cls = db.createVertexClass("OwnerResolutionAfterReferenceLessRestore");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionAfterReferenceLessRestore.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var unrelatedIndex =
        (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var unrelatedEngine = findEngineByName(storage, indexName);
    var engines = indexEngines(storage);
    var engine = Mockito.mock(BaseIndexEngine.class);
    var engineName = "restored-reference-less";
    var owner = new RecordId(95, 21);
    var slot = engines.size();
    Mockito.when(engine.getId()).thenReturn(slot);
    Mockito.when(engine.getName()).thenReturn(engineName);
    Mockito.when(engine.getEngineReference()).thenReturn(null);

    storage.bindOwnerAndPublishRestoredIndexEngine(slot, engine, owner);

    assertThat(engines.get(slot)).isSameAs(engine);
    assertThat(storage.loadIndexEngine(engineName)).isGreaterThanOrEqualTo(0);
    assertThat(storage.resolveIndexEngineByOwner(unrelatedIndex.getIdentity()).engineIdentifier())
        .isEqualTo(externalIdOf(unrelatedEngine));
  }

  /** The lock-held owner resolver rejects a caller without a state lock or commit window. */
  @Test
  public void ownerResolverWithStateLockRejectsUnlockedCaller() {
    var storage = (AbstractStorage) db.getStorage();

    assertThatThrownBy(
        () -> storage.resolveIndexEngineByOwnerWithStateLock(new RecordId(94, 20)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("state lock")
        .hasMessageContaining("commit window");
  }

  /** The public owner resolver self-routes under a commit-window write lock without deadlocking. */
  @Test(timeout = 30_000)
  public void publicOwnerResolverRoutesThroughCommitWindow() throws Exception {
    db.activateOnCurrentThread();
    var cls = db.createVertexClass("OwnerResolutionCommitWindow");
    cls.createProperty("value", PropertyType.STRING);
    var indexName = "OwnerResolutionCommitWindow.value";
    cls.createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "value");

    var storage = (AbstractStorage) db.getStorage();
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var engine = findEngineByName(storage, indexName);

    storage.stateLock.writeLock().lock();
    try {
      storage.enterCommitWindow();
      try {
        assertThat(storage.resolveIndexEngineByOwner(index.getIdentity()).engineIdentifier())
            .isEqualTo(externalIdOf(engine));
      } finally {
        storage.exitCommitWindow();
      }
    } finally {
      storage.stateLock.writeLock().unlock();
    }
  }

  // ---- Helpers ----

  /**
   * Reads the {@code private} {@code indexEngines} registry on the storage and returns the
   * engine registered under {@code name}, or {@code null} if no live engine carries it.
   */
  private static BTreeSingleValueIndexEngine replacementEngine(
      AbstractStorage storage, BTreeSingleValueIndexEngine original, String suffix) {
    return new BTreeSingleValueIndexEngine(
        original.getId(),
        original.getFileBaseId(),
        original.getName() + "-" + suffix,
        storage,
        4);
  }

  @SuppressWarnings("unchecked")
  private static List<BaseIndexEngine> indexEngines(AbstractStorage storage) throws Exception {
    var field = AbstractStorage.class.getDeclaredField("indexEngines");
    field.setAccessible(true);
    return (List<BaseIndexEngine>) field.get(storage);
  }

  private static void setIndexIdentifier(IndexAbstract index, int indexIdentifier) {
    index.setEngineIdentifierForTest(indexIdentifier);
  }

  private static BaseIndexEngine findEngineByName(AbstractStorage storage, String name)
      throws Exception {
    var engines = indexEngines(storage);
    for (var engine : engines) {
      if (engine != null && name.equals(engine.getName())) {
        return engine;
      }
    }
    return null;
  }

  /**
   * Re-tags an engine's internal id into the external, API-version-tagged id that the
   * public {@code getIndexEngine} expects, mirroring {@code AbstractStorage.generateIndexId}.
   */
  private static int externalIdOf(BaseIndexEngine engine) {
    return engine.getEngineAPIVersion() << ((Integer.BYTES << 3) - 5) | engine.getId();
  }
}
