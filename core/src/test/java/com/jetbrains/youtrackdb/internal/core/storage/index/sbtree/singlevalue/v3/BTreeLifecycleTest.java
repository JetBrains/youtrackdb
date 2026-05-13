package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.TooBigIndexKeyException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.util.UUID;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for {@link BTree} lifecycle and miscellaneous API methods that are not covered by the
 * existing optimistic-read, tombstone-GC, and read-methods test suites.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link BTree#setApproximateEntriesCount} and
 *       {@link BTree#addToApproximateEntriesCount} — write paths for the persisted approximate
 *       count stored in the entry-point page.</li>
 *   <li>{@link BTree#acquireAtomicExclusiveLock} — delegates to the AtomicOperationsManager.</li>
 *   <li>{@link BTree#validatedPut} with an IGNORE-returning validator — the ignored-insertion
 *       path that releases the bucket cache entry and returns -1.</li>
 *   <li>{@link BTree#put} with a key that exceeds the configured {@code maxKeySize} — triggers
 *       {@link TooBigIndexKeyException}.</li>
 *   <li>{@link BTree#remove} with a key that is not present in the tree — returns {@code null}.</li>
 *   <li>{@link BTree#remove} with a {@code null} key — exercises the null-bucket remove path.</li>
 *   <li>{@link BTree#getApproximateEntriesCount} — the optimistic + pinned read paths for the
 *       approximate count.</li>
 * </ul>
 *
 * <p>This class mutates the process-wide {@code GlobalConfiguration.BTREE_MAX_KEY_SIZE} in one
 * test and therefore carries {@link SequentialTest} to ensure it runs in the sequential surefire
 * execution (never concurrently with other test classes that read the same global config).
 */
@Category(SequentialTest.class)
public class BTreeLifecycleTest {

  private static final String DIR_SUFFIX = "BTreeLifecycleTest";

  private static YouTrackDBImpl youTrackDB;
  private static AbstractStorage storage;
  private static AtomicOperationsManager atomicOperationsManager;
  private static String buildDirectory;

  private BTree<String> tree;

  @BeforeClass
  public static void setUpClass() {
    buildDirectory =
        System.getProperty("buildDirectory", ".")
            + File.separator
            + DIR_SUFFIX;

    // Use a per-class database name to avoid OEngine collision with other test suites.
    var dbName = "btreeLifecycleTest-" + UUID.randomUUID();
    var dbDirectory = new File(buildDirectory, dbName);
    FileUtils.deleteRecursively(dbDirectory);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK, "admin", "admin", "admin");

    try (var session = youTrackDB.open(dbName, "admin", "admin")) {
      storage = session.getStorage();
    }
    atomicOperationsManager = storage.getAtomicOperationsManager();
  }

  @AfterClass
  public static void tearDownClass() {
    if (youTrackDB != null && youTrackDB.isOpen()) {
      youTrackDB.close();
    }
    FileUtils.deleteRecursively(new File(buildDirectory));
  }

  @Before
  public void setUp() throws Exception {
    // Each test gets a fresh BTree backed by the shared storage, with a unique name so
    // that tests do not interfere with each other via the write cache file registry.
    var treeName = "lifecycleTree-" + UUID.randomUUID();
    tree = new BTree<>(treeName, ".sbt", ".nbt", storage);
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.create(atomicOperation, UTF8Serializer.INSTANCE, null, 1));
  }

  @After
  public void tearDown() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.delete(atomicOperation));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // setApproximateEntriesCount / addToApproximateEntriesCount / getApproximateEntriesCount
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * After calling {@code setApproximateEntriesCount(n)}, {@code getApproximateEntriesCount()}
   * must return {@code n} (verified in the same atomic operation so no optimistic eviction occurs).
   */
  @Test
  public void setAndGetApproximateEntriesCount_roundTrips() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.setApproximateEntriesCount(atomicOperation, 42L);
      long read = tree.getApproximateEntriesCount(atomicOperation);
      assertThat(read).isEqualTo(42L);
    });
  }

  /**
   * {@code addToApproximateEntriesCount(delta)} atomically increments the persisted count.
   * Starting from a known value, adding a positive delta should yield {@code start + delta}.
   */
  @Test
  public void addToApproximateEntriesCount_incrementsPersistedCount() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.setApproximateEntriesCount(atomicOperation, 100L);
      tree.addToApproximateEntriesCount(atomicOperation, 15L);
      long count = tree.getApproximateEntriesCount(atomicOperation);
      assertThat(count).isEqualTo(115L);
    });
  }

  /**
   * {@code addToApproximateEntriesCount(-delta)} decrements the count. The result must be
   * the expected non-negative value.
   */
  @Test
  public void addToApproximateEntriesCount_decrement_yieldsCorrectValue() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.setApproximateEntriesCount(atomicOperation, 50L);
      tree.addToApproximateEntriesCount(atomicOperation, -20L);
      long count = tree.getApproximateEntriesCount(atomicOperation);
      assertThat(count).isEqualTo(30L);
    });
  }

  // ─────────────────────────────────────────────────────────────────────────
  // acquireAtomicExclusiveLock
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * {@code acquireAtomicExclusiveLock(atomicOperation)} must register the BTree's lock name
   * inside the atomic operation's locked-objects set. Asserting the registration is the
   * observable side effect of the delegation; a regression that silently turns the method
   * into a no-op (or fails to record the lock) would not be caught by a bare "does not
   * throw" check.
   *
   * <p>The lock name is the BTree's {@code getLockName()} (inherited from
   * {@code StorageComponent}) and is what {@code AtomicOperationsManager} stores in
   * {@code AtomicOperation.lockedObjects()} via {@code addLockedObject}.
   */
  @Test
  public void acquireAtomicExclusiveLock_registersLockNameInAtomicOperation() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var lockName = tree.getLockName();

      // Pre-condition: the lock name is not yet registered on a fresh atomic operation.
      assertThat(atomicOperation.containsInLockedObjects(lockName))
          .as("BTree lock name must not be registered before acquireAtomicExclusiveLock")
          .isFalse();

      tree.acquireAtomicExclusiveLock(atomicOperation);

      // Post-condition: the BTree is now recorded as an exclusive-lock holder for the
      // duration of the operation. The lock will be released when the atomic operation
      // commits or rolls back.
      assertThat(atomicOperation.containsInLockedObjects(lockName))
          .as("acquireAtomicExclusiveLock must register the BTree lock name in the "
              + "atomic operation's locked-objects set")
          .isTrue();
    });
  }

  // ─────────────────────────────────────────────────────────────────────────
  // validatedPut — IGNORE path
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * When the validator returns {@link IndexEngineValidator#IGNORE} for a key that is not
   * already in the tree, {@code validatedPut} must return {@code -1} and must NOT insert
   * the entry — the tree must remain empty.
   */
  @Test
  public void validatedPut_ignoredResult_doesNotInsertAndReturnsNegativeOne() throws Exception {
    // Validator that always says IGNORE
    IndexEngineValidator<String, RID> ignoreAll =
        (key, oldValue, newValue) -> IndexEngineValidator.IGNORE;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      int result = tree.validatedPut(
          atomicOperation, "ignored-key", new RecordId(1, 1), ignoreAll);
      assertThat(result)
          .as("validatedPut must return -1 when validator ignores the insertion")
          .isEqualTo(-1);
    });

    // Verify the key was not inserted
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      assertThat(tree.get("ignored-key", atomicOperation))
          .as("Ignored put must not insert the entry")
          .isNull();
    });
  }

  /**
   * When the validator returns a substituted (non-IGNORE) {@link RID} for a key, {@code
   * validatedPut} must store the substituted value, NOT the input new-value. This pins the
   * substitution branch where the validator's return is cast to {@code RID} and assigned
   * back to the {@code value} local before serialization — the path that lets a custom
   * validator transform the value being written.
   */
  @Test
  public void validatedPut_validatorReturnsSubstitutedRid_storesSubstitutedValue()
      throws Exception {
    var inputRid = new RecordId(1, 100);
    var substitutedRid = new RecordId(2, 200);

    // Validator that ignores the input value and substitutes a different RID.
    IndexEngineValidator<String, RID> substituteValidator =
        (key, oldValue, newValue) -> substitutedRid;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      int result = tree.validatedPut(
          atomicOperation, "sub-key", inputRid, substituteValidator);
      // 1 indicates a successful insert; the value stored is the validator's substituted RID.
      assertThat(result)
          .as("validatedPut must return 1 for a successful substituted insert")
          .isEqualTo(1);
    });

    // The stored value must equal the substituted RID, NOT the inputRid passed to validatedPut.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var stored = tree.get("sub-key", atomicOperation);
      assertThat(stored)
          .as("validatedPut must persist the validator's substituted RID, not the input")
          .isNotNull();
      assertThat(stored.getCollectionId()).isEqualTo(substitutedRid.getCollectionId());
      assertThat(stored.getCollectionPosition())
          .isEqualTo(substitutedRid.getCollectionPosition());
      // Sanity: the input RID was NOT used.
      assertThat(stored.getCollectionPosition())
          .as("input RID must NOT have been stored when the validator substitutes")
          .isNotEqualTo(inputRid.getCollectionPosition());
    });
  }

  /**
   * When the validator returns {@link IndexEngineValidator#IGNORE} for a key that already
   * exists in the tree, {@code validatedPut} must return {@code -1} and must NOT update the
   * existing value — the original value must be preserved.
   */
  @Test
  public void validatedPut_existingKey_ignoredResult_preservesOriginalValue() throws Exception {
    var originalRid = new RecordId(3, 777);
    var newRid = new RecordId(5, 999);

    // Insert original value
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.put(atomicOperation, "existing-key", originalRid));

    // Now try to update with IGNORE validator
    IndexEngineValidator<String, RID> ignoreAll =
        (key, oldValue, newValue) -> IndexEngineValidator.IGNORE;

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      int result = tree.validatedPut(
          atomicOperation, "existing-key", newRid, ignoreAll);
      assertThat(result)
          .as("validatedPut with existing key must return -1 when validator ignores")
          .isEqualTo(-1);
    });

    // Verify the original value is preserved
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var value = tree.get("existing-key", atomicOperation);
      assertThat(value).isNotNull();
      assertThat(value.getCollectionId()).isEqualTo(originalRid.getCollectionId());
      assertThat(value.getCollectionPosition()).isEqualTo(originalRid.getCollectionPosition());
    });
  }

  // ─────────────────────────────────────────────────────────────────────────
  // TooBigIndexKeyException
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * When the configured {@code maxKeySize} is positive and the serialized key is larger
   * than that limit, {@code put()} must throw {@link TooBigIndexKeyException}.
   *
   * <p>This test temporarily reduces {@code BTREE_MAX_KEY_SIZE} to 1 byte, creates a fresh tree
   * so that {@code maxKeySize} is set from the updated config, then restores the config after
   * the test. The tree used here is local (not the one from {@link #setUp()}) so that the
   * per-method setup tree is unaffected.
   */
  @Test
  public void put_keyExceedsMaxKeySize_throwsTooBigIndexKeyException() throws Exception {
    int originalMaxKeySize = GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
    // Set an impossibly small key size so that any real UTF-8 key exceeds it
    GlobalConfiguration.BTREE_MAX_KEY_SIZE.setValue(1);

    var tinyKeyTree = new BTree<String>(
        "tinyKeyTree-" + UUID.randomUUID(), ".sbt", ".nbt", storage);
    try {
      // create() reads BTREE_MAX_KEY_SIZE from GlobalConfiguration
      atomicOperationsManager.executeInsideAtomicOperation(
          atomicOperation -> tinyKeyTree.create(
              atomicOperation, UTF8Serializer.INSTANCE, null, 1));

      // A key of "hello" is well over 1 byte in UTF-8
      assertThatThrownBy(
          () -> atomicOperationsManager.executeInsideAtomicOperation(
              atomicOperation -> tinyKeyTree.put(atomicOperation, "hello", new RecordId(0, 1))))
          .isInstanceOf(TooBigIndexKeyException.class);

      // Post-rejection page-state consistency: the failed put must NOT leave a partial write
      // behind. The tree size must remain 0.
      atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
        assertThat(tinyKeyTree.size(atomicOperation))
            .as("oversized-key rejection must not leave a partial entry behind")
            .isEqualTo(0L);
      });
    } finally {
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.setValue(originalMaxKeySize);
      // Clean up: delete the temporary tree
      try {
        atomicOperationsManager.executeInsideAtomicOperation(
            atomicOperation -> tinyKeyTree.delete(atomicOperation));
      } catch (Exception ignored) {
        // Best-effort cleanup; tree may not have been fully created if create failed
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // remove — missing key + null key
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * {@code remove(key)} must return {@code null} when the key is not present in the tree.
   * This exercises the {@code else { return null; }} branch of {@code findBucketForRemove}.
   */
  @Test
  public void remove_nonExistentKey_returnsNull() throws Exception {
    // Populate the tree with some entries
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      tree.put(atomicOperation, "a", new RecordId(0, 1));
      tree.put(atomicOperation, "b", new RecordId(0, 2));
    });

    // Attempt to remove a key that was never inserted
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.remove(atomicOperation, "nonexistent");
      assertThat(result)
          .as("remove() must return null for a key not in the tree")
          .isNull();
    });
  }

  /**
   * {@code remove(null)} when no null key has been inserted must return {@code null}.
   * The null-bucket remove path: reads the null-bucket page, finds no value, returns null.
   */
  @Test
  public void remove_nullKey_whenNullBucketEmpty_returnsNull() throws Exception {
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var result = tree.remove(atomicOperation, (String) null);
      assertThat(result)
          .as("remove(null) on empty tree must return null")
          .isNull();
    });
  }

  /**
   * {@code remove(null)} when a null key has been inserted must return the previously stored RID
   * and reduce the tree size by one.
   */
  @Test
  public void remove_nullKey_whenNullBucketHasValue_returnsStoredRidAndDecreasesSize()
      throws Exception {
    var nullRid = new RecordId(2, 777);

    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.put(atomicOperation, null, nullRid));

    // Verify it was stored
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      assertThat(tree.size(atomicOperation)).isEqualTo(1L);
    });

    // Now remove the null key
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      var removed = tree.remove(atomicOperation, (String) null);
      assertThat(removed).isNotNull();
      assertThat(removed.getCollectionId()).isEqualTo(nullRid.getCollectionId());
      assertThat(removed.getCollectionPosition()).isEqualTo(nullRid.getCollectionPosition());
    });

    // Tree should now be empty
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      assertThat(tree.size(atomicOperation)).isEqualTo(0L);
      assertThat(tree.get(null, atomicOperation)).isNull();
    });
  }

  // ─────────────────────────────────────────────────────────────────────────
  // CellBTreeSingleValueV3Exception — constructor coverage
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * The public copy constructor of {@link CellBTreeSingleValueV3Exception} must wrap the
   * original exception so the seed message and cause survive the copy. This exercises the
   * copy-constructor path, which is otherwise unreachable from production call sites (the
   * constructor is marked {@code @SuppressWarnings("unused")} in the production code). The
   * test uses the real {@code tree} field (a concrete {@link BTree} instance) to satisfy the
   * {@code component.getName()} call inside
   * {@link com.jetbrains.youtrackdb.internal.core.exception.StorageComponentException}.
   *
   * <p>The copy constructor at {@code BaseException(BaseException)} re-uses the seed's
   * {@code getMessage()} and {@code getCause()}, so we assert both: the (assembled) message
   * from the seed must appear in the copy's message, and the causes must match (both null on
   * a freshly-constructed seed). The exact message format is built by
   * {@link com.jetbrains.youtrackdb.internal.core.exception.CoreException#getMessage}: the
   * seed string plus a Component Name suffix — we use {@code contains} so the test is robust
   * to formatting tweaks.
   */
  @Test
  public void cellBTreeExceptionCopyCtor_preservesSeedMessageAndCause() {
    var seed = new CellBTreeSingleValueV3Exception("seed message", tree);

    var copy = new CellBTreeSingleValueV3Exception(seed);

    assertThat(copy.getMessage())
        .as("copy ctor must propagate the seed's assembled message text")
        .contains("seed message");
    assertThat(copy.getCause())
        .as("copy ctor must mirror the seed's cause (null → null)")
        .isEqualTo(seed.getCause());
  }

  /**
   * The three-arg package-private constructor {@code (dbName, message, component)} of
   * {@link CellBTreeSingleValueV3Exception} routes both the dbName and the message through
   * the {@link com.jetbrains.youtrackdb.internal.core.exception.CoreException} chain, so the
   * assembled {@code getMessage()} must contain the constructor's message string and
   * {@code getDbName()} must return the dbName argument verbatim. {@link BTree} relies on
   * exactly this path when it wraps an {@link java.io.IOException}.
   */
  @Test
  public void cellBTreeExceptionThreeArgCtor_propagatesMessageAndDbName() {
    var ex = new CellBTreeSingleValueV3Exception("dbName", "error message", tree);

    assertThat(ex.getMessage())
        .as("three-arg ctor must propagate the message argument into the assembled message")
        .contains("error message");
    assertThat(ex.getDbName())
        .as("three-arg ctor must store the dbName argument verbatim")
        .isEqualTo("dbName");
  }
}
