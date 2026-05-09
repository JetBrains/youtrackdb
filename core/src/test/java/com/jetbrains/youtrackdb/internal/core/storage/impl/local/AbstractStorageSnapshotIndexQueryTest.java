package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the public-API snapshot-query helpers on {@link AbstractStorage} —
 * specifically {@code hasActiveIndexSnapshotEntries} (name-resolving variant)
 * and {@code hasActiveIndexSnapshotEntriesById} (lock-free, pre-resolved-id
 * variant). The negative-name paths are covered by the B-tree tombstone GC
 * test; this class focuses on the <i>positive</i> paths (entries present and
 * detected) plus the LWM filtering and {@code $null}-suffix handling against a
 * real, registered index.
 *
 * <p>Mirrors the IndexesSnapshotClearTest topology: a real
 * {@link DatabaseSessionEmbedded}, an INSERT + UPDATE round to populate the
 * snapshot, then direct queries against the storage helpers.
 */
public class AbstractStorageSnapshotIndexQueryTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name) collisions when
  // these tests run in parallel under surefire fork-per-class.
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

  // ---- hasActiveIndexSnapshotEntries — positive path ----

  @Test
  public void hasActiveIndexSnapshotEntries_returnsTrue_whenSnapshotIsPopulated()
      throws Exception {
    // Build a UNIQUE index, populate one record then update it so the snapshot
    // index sees a (TombstoneRID, RecordId-guard) pair. The version of those
    // entries is the committing transaction's id, so any LWM strictly less
    // than that version must observe the entries as still active.
    SchemaClass cls = db.createVertexClass("PersonAQ");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonAQ_name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    db.begin();
    var v = db.newVertex("PersonAQ");
    v.setProperty("name", "Alice");
    db.commit();

    db.begin();
    var tx = db.getActiveTransaction();
    v = tx.load(v.getIdentity());
    v.setProperty("name", "Bob");
    db.commit();

    var storage = (AbstractStorage) db.getStorage();
    var keyPrefix = new CompositeKey("Alice");

    assertThat(storage.hasActiveIndexSnapshotEntries(
        "PersonAQ_name", keyPrefix, 0L))
        .as("Snapshot must report active entries for the populated key with LWM=0")
        .isTrue();
  }

  @Test
  public void hasActiveIndexSnapshotEntries_returnsFalse_whenLwmAboveAllVersions()
      throws Exception {
    // The same scenario as above, but with an LWM strictly greater than every
    // committed version. headMap-based queries must report no active entries.
    SchemaClass cls = db.createVertexClass("PersonAQ2");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("PersonAQ2_tag", SchemaClass.INDEX_TYPE.UNIQUE, "tag");

    db.begin();
    var v = db.newVertex("PersonAQ2");
    v.setProperty("tag", "X");
    db.commit();

    db.begin();
    var tx = db.getActiveTransaction();
    v = tx.load(v.getIdentity());
    v.setProperty("tag", "Y");
    db.commit();

    var storage = (AbstractStorage) db.getStorage();
    var keyPrefix = new CompositeKey("X");
    var farFutureLwm = Long.MAX_VALUE - 1L;

    assertThat(storage.hasActiveIndexSnapshotEntries(
        "PersonAQ2_tag", keyPrefix, farFutureLwm))
        .as("Snapshot must report no active entries when LWM >= all versions")
        .isFalse();
  }

  // ---- hasActiveIndexSnapshotEntriesById — lock-free path ----

  @Test
  public void hasActiveIndexSnapshotEntriesById_returnsTrue_whenSnapshotIsPopulated()
      throws Exception {
    // The pre-resolved-id variant takes the boolean useNullSnapshot flag and
    // a positive index id. With useNullSnapshot=false it queries the same
    // sharedIndexesSnapshot as the name-resolving variant. We round-trip the
    // index id from the index manager so the test stays robust if the id
    // assignment scheme changes.
    SchemaClass cls = db.createVertexClass("PersonAQ3");
    cls.createProperty("v", PropertyType.STRING);
    cls.createIndex("PersonAQ3_v", SchemaClass.INDEX_TYPE.UNIQUE, "v");

    db.begin();
    var v = db.newVertex("PersonAQ3");
    v.setProperty("v", "P");
    db.commit();

    db.begin();
    var tx = db.getActiveTransaction();
    v = tx.load(v.getIdentity());
    v.setProperty("v", "Q");
    db.commit();

    var storage = (AbstractStorage) db.getStorage();
    long internalEngineId = resolveInternalEngineId("PersonAQ3_v");

    assertThat(storage.hasActiveIndexSnapshotEntriesById(
        internalEngineId, false, new CompositeKey("P"), 0L))
        .as("Pre-resolved-id query must report active entries with LWM=0")
        .isTrue();
  }

  @Test
  public void hasActiveIndexSnapshotEntriesById_returnsFalse_forIsolatedKeyPrefix()
      throws Exception {
    // Asks the lock-free variant about a key that was never touched. The
    // (lower, upper) bound walks the empty subMap and returns false.
    SchemaClass cls = db.createVertexClass("PersonAQ4");
    cls.createProperty("v", PropertyType.STRING);
    cls.createIndex("PersonAQ4_v", SchemaClass.INDEX_TYPE.UNIQUE, "v");

    db.begin();
    var v = db.newVertex("PersonAQ4");
    v.setProperty("v", "TouchedKey");
    db.commit();

    db.begin();
    var tx = db.getActiveTransaction();
    v = tx.load(v.getIdentity());
    v.setProperty("v", "OtherKey");
    db.commit();

    var storage = (AbstractStorage) db.getStorage();
    long internalEngineId = resolveInternalEngineId("PersonAQ4_v");

    assertThat(storage.hasActiveIndexSnapshotEntriesById(
        internalEngineId, false, new CompositeKey("UntouchedPrefix"), 0L))
        .as("A key that was never touched must report no active snapshot entries")
        .isFalse();
  }

  @Test
  public void hasActiveIndexSnapshotEntriesById_handlesNullSnapshotMapSelector()
      throws Exception {
    // Smoke pin for the useNullSnapshot=true branch: even if the null-key
    // snapshot is empty (no null-keyed index activity), the lock-free helper
    // must return false without throwing. This exercises the alternative
    // map selector branch.
    SchemaClass cls = db.createVertexClass("PersonAQ5");
    cls.createProperty("v", PropertyType.STRING);
    cls.createIndex("PersonAQ5_v", SchemaClass.INDEX_TYPE.UNIQUE, "v");

    var storage = (AbstractStorage) db.getStorage();
    long internalEngineId = resolveInternalEngineId("PersonAQ5_v");

    assertThat(storage.hasActiveIndexSnapshotEntriesById(
        internalEngineId, true, new CompositeKey(new Object[] {null}), 0L))
        .as("Null-snapshot variant must return false when the null map is empty")
        .isFalse();
  }

  // ---- hasActiveIndexSnapshotEntries — $null-suffix engine name routing ----

  @Test
  public void hasActiveIndexSnapshotEntries_routesNullSuffixEngineToNullMap() {
    // The "$null" suffix triggers a different code path: the helper strips the suffix,
    // resolves the bare name in indexEngineNameMap, and queries sharedNullIndexesSnapshot.
    // Register an actual engine name first so the routing branch reaches the null-snapshot
    // map lookup, not just the early engine-name failed lookup. We then assert two things:
    // (a) the registered-engine $null query returns false (snapshot is empty under this
    // empty-tx setup), and (b) the lock-free "by id" variant with useNullSnapshot=true
    // also returns false against the same engine id — both paths must agree on the
    // null-snapshot map's content.
    SchemaClass cls = db.createVertexClass("PersonAQNull");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonAQNull_name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    var storage = (AbstractStorage) db.getStorage();

    // Registered engine name + $null suffix — the routing strips the suffix, resolves
    // "PersonAQNull_name" in indexEngineNameMap, and queries sharedNullIndexesSnapshot.
    // Without any null-key updates the result is false, but the routing branch IS
    // exercised: a regression that swapped the snapshot-map binding before the engine
    // lookup would still return false on this assertion alone, so we additionally verify
    // that the null-snapshot factory at the same engine id is reachable below.
    assertThat(storage.hasActiveIndexSnapshotEntries(
        "PersonAQNull_name" + AbstractStorage.NULL_TREE_SUFFIX,
        new CompositeKey(new Object[] {null}),
        0L))
        .as("Null-suffix routing on a registered engine must reach the null-snapshot "
            + "map and return false on an empty snapshot")
        .isFalse();

    // Cross-check the same routing via the lock-free "by id" variant: useNullSnapshot=true
    // must reach the same map. The factory must produce a non-null IndexesSnapshot whose
    // backing entries collection is empty (the null-snapshot map is empty under this setup).
    long internalEngineId;
    try {
      internalEngineId = resolveInternalEngineId("PersonAQNull_name");
    } catch (Exception e) {
      throw new AssertionError("failed to resolve engine id for null-snapshot routing test", e);
    }
    var nullSnapshot = storage.subNullIndexSnapshot(internalEngineId);
    assertThat(nullSnapshot)
        .as("subNullIndexSnapshot must produce a non-null view for the registered engine")
        .isNotNull();
    assertThat(nullSnapshot.allEntries())
        .as("the null-snapshot map for a fresh registered engine must be empty")
        .isEmpty();

    // Negative case: unresolved engine name (post-suffix-strip) must return false. Pinned
    // alongside the registered case so a regression that confuses the two paths is caught.
    assertThat(storage.hasActiveIndexSnapshotEntries(
        "doesNotExist" + AbstractStorage.NULL_TREE_SUFFIX,
        new CompositeKey(new Object[] {null}),
        0L))
        .as("Null-suffix routing on an unresolved engine must return false")
        .isFalse();
  }

  // ---- subIndexSnapshot / subNullIndexSnapshot ----

  @Test
  public void subIndexSnapshot_yieldsScopedSnapshot_forKnownIndex() throws Exception {
    // Smoke pin for the public sub-snapshot factory: building a sub-snapshot
    // for a registered index id must produce a non-null IndexesSnapshot whose
    // entries collection can be queried without throwing.
    SchemaClass cls = db.createVertexClass("PersonAQ6");
    cls.createProperty("v", PropertyType.STRING);
    cls.createIndex("PersonAQ6_v", SchemaClass.INDEX_TYPE.UNIQUE, "v");

    var storage = (AbstractStorage) db.getStorage();
    long internalEngineId = resolveInternalEngineId("PersonAQ6_v");

    var snapshot = storage.subIndexSnapshot(internalEngineId);
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.allEntries()).isEmpty();

    var nullSnapshot = storage.subNullIndexSnapshot(internalEngineId);
    assertThat(nullSnapshot).isNotNull();
    assertThat(nullSnapshot.allEntries()).isEmpty();
  }

  // ---- Snapshot helpers — getter shape ----

  @Test
  public void publicSnapshotMaps_areReachable_andNonNull() {
    // Pure getter shape pins for the snapshot/visibility map accessors. They
    // are the single integration point between AbstractStorage's snapshot
    // bookkeeping and outside test/diagnostic code.
    var storage = (AbstractStorage) db.getStorage();
    assertThat(storage.getSharedSnapshotIndex()).isNotNull();
    assertThat(storage.getVisibilityIndex()).isNotNull();
    assertThat(storage.getSnapshotIndexSize()).isNotNull();
    assertThat(storage.getSharedEdgeSnapshotIndex()).isNotNull();
    assertThat(storage.getEdgeVisibilityIndex()).isNotNull();
    assertThat(storage.getEdgeSnapshotIndexSize()).isNotNull();
    assertThat(storage.getIndexesSnapshotEntriesCount()).isNotNull();
    // The size counters are >= 0 invariants (atomic LWM-driven accounting).
    assertThat(storage.getSnapshotIndexSize().get()).isGreaterThanOrEqualTo(0L);
    assertThat(storage.getEdgeSnapshotIndexSize().get()).isGreaterThanOrEqualTo(0L);
    assertThat(storage.getIndexesSnapshotEntriesCount().get()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  public void computeGlobalLowWaterMark_returnsMonotonicValue_onIdleStorage() {
    // The instance-level helper falls back to idGen.getLastId() when no
    // transactions are active, so on an idle session the result is positive
    // and stable. This covers the "no active transaction" branch of the
    // instance-level computeGlobalLowWaterMark wrapper.
    var storage = (AbstractStorage) db.getStorage();
    long firstRead = storage.computeGlobalLowWaterMark();
    long secondRead = storage.computeGlobalLowWaterMark();
    assertThat(firstRead).isGreaterThanOrEqualTo(0L);
    assertThat(secondRead).isGreaterThanOrEqualTo(firstRead);
  }

  // ---- helpers ----

  /**
   * Resolves the internal engine id for an index by name. The internal id is
   * the integer that {@code subIndexSnapshot} / {@code hasActiveIndexSnapshotEntriesById}
   * expect (NOT the external id with the engine version bits set).
   */
  private long resolveInternalEngineId(String indexName)
      throws InvalidIndexEngineIdException {
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    return db.getStorage().getIndexEngine(index.getIndexId()).getId();
  }

}
