package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.StorageReadResult;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * White-box tests for the lock-free commit-window primitives extracted out of the public
 * structural methods on {@link AbstractStorage}: {@code doGetIndexEngine},
 * {@code doAddIndexEngine} / {@code publishIndexEngine}, {@code doDeleteIndexEngine}, and
 * the {@code doCreateCollection} / {@code registerCollection} create/publish split.
 *
 * <p>Two properties are pinned. First, the public {@code addIndexEngine} /
 * {@code deleteIndexEngine} / {@code addCollection} wrappers still create, register, and
 * drop structures exactly as before the extraction (behavior preservation). Second — the
 * load-bearing one — {@code doGetIndexEngine} resolves an engine by id while the calling
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
 * {@code doGetIndexEngine(int)}, and {@code isCommitWindowActive()} (the window predicate).
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
  // collections appear in the name registry and resolve to real ids. A vertex class names
  // its collections "<class>_<counter>" (lower-cased), so the assertion matches the prefix.
  @Test
  public void addCollectionWrapperPublishesCollectionIntoRegistry() {
    db.createVertexClass("CollPublishProbe");

    var storage = (AbstractStorage) db.getStorage();

    var published =
        storage.getCollectionNames().stream()
            .filter(n -> n.startsWith("collpublishprobe"))
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

  // ---- The load-bearing property: doGetIndexEngine resolves under a held write lock ----

  // doGetIndexEngine must resolve an engine by internal id without taking stateLock, so a
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
  public void doGetIndexEngineResolvesWhileWriteLockHeld() throws Exception {
    // @Test(timeout) runs this body on a JUnit watchdog thread; the session is thread-bound
    // (a ThreadLocal activation flag), so re-activate it on this thread before touching the db.
    db.activateOnCurrentThread();

    SchemaClass cls = db.createVertexClass("PersonLockFree");
    cls.createProperty("email", PropertyType.STRING);
    cls.createIndex("PersonLockFree_email", SchemaClass.INDEX_TYPE.UNIQUE, "email");

    var storage = (AbstractStorage) db.getStorage();
    var engine = findEngineByName(storage, "PersonLockFree_email");
    assertThat(engine).as("precondition: the engine is registered").isNotNull();
    int internalId = engine.getId();

    Method doGet =
        AbstractStorage.class.getDeclaredMethod("doGetIndexEngine", int.class);
    doGet.setAccessible(true);

    // Take the write lock on this thread, exactly as a schema-carrying commit does at entry.
    storage.stateLock.writeLock().lock();
    try {
      var resolved = (BaseIndexEngine) doGet.invoke(storage, internalId);
      assertThat(resolved)
          .as("doGetIndexEngine must resolve the engine under the held write lock")
          .isSameAs(engine);
    } finally {
      storage.stateLock.writeLock().unlock();
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

    db.createVertexClass("NameLookupProbe");
    var storage = (AbstractStorage) db.getStorage();

    // Pick one real collection id of the class and its expected name from the normal
    // (read-lock) path, captured before we take the write lock.
    var collectionName =
        storage.getCollectionNames().stream()
            .filter(n -> n.startsWith("namelookupprobe"))
            .findFirst()
            .orElseThrow();
    int collectionId = storage.getCollectionIdByName(collectionName);
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

    // After the window closes, the normal record-read path resolves with the read lock again.
    db.createVertexClass("PostWindowProbe");
    var collectionName =
        storage.getCollectionNames().stream()
            .filter(n -> n.startsWith("postwindowprobe"))
            .findFirst()
            .orElseThrow();
    assertThat(storage.getPhysicalCollectionNameById(storage.getCollectionIdByName(collectionName)))
        .as("the normal read-lock path still resolves once the window is closed")
        .isEqualTo(collectionName);
  }

  // ---- Helpers ----

  /**
   * Reads the {@code private} {@code indexEngines} registry on the storage and returns the
   * engine registered under {@code name}, or {@code null} if no live engine carries it.
   */
  @SuppressWarnings("unchecked")
  private static BaseIndexEngine findEngineByName(AbstractStorage storage, String name)
      throws Exception {
    var field = AbstractStorage.class.getDeclaredField("indexEngines");
    field.setAccessible(true);
    var engines = (List<BaseIndexEngine>) field.get(storage);
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
