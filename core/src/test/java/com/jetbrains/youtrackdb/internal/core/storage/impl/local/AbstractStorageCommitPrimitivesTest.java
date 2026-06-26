package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
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
 * <p>The test lives in the storage package so it can read {@code storage.stateLock} for
 * white-box lock-holding, and uses reflection only for the two {@code private} members it
 * must reach directly: the {@code indexEngines} registry (to find an engine's internal id)
 * and {@code doGetIndexEngine(int)} (the method under test).
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
  @Test
  public void doGetIndexEngineResolvesWhileWriteLockHeld() throws Exception {
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
