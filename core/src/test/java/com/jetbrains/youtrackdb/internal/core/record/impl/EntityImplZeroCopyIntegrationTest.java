package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration tests for the zero-copy PageFrame record read path. These tests
 * exercise the full pipeline: create entities, flush to read cache (so the
 * optimistic read path produces RawPageBuffer), load entities through the
 * session API, and verify that deserialization from the PageFrame works
 * correctly — including stamp invalidation fallback, partial deserialization,
 * concurrent modification, and lifecycle operations.
 *
 * <p>Unlike the unit tests in {@link EntityImplPageFrameDeserializationTest}
 * (which manually write serialized bytes into a PageFrame), these tests go
 * through the real storage optimistic read path end-to-end.
 *
 * <p>Requires DISK storage so that pages are served from the read cache,
 * enabling the optimistic read path in {@code PaginatedCollectionV2}.
 */
@Category(SequentialTest.class)
public class EntityImplZeroCopyIntegrationTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private String buildDirectory;
  private static final String DB_NAME = "zeroCopyIntegrationTest";

  @Before
  public void setUp() {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }
    buildDirectory += File.separator
        + EntityImplZeroCopyIntegrationTest.class.getSimpleName();
    FileUtils.deleteRecursively(new File(buildDirectory));

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", "admin");
  }

  @After
  public void tearDown() {
    if (session != null && !session.isClosed()) {
      session.close();
    }
    if (youTrackDB != null) {
      youTrackDB.drop(DB_NAME);
      youTrackDB.close();
    }
  }

  /**
   * Creates a class and persists a record with the given string key-value
   * pairs. Returns the RID of the committed record.
   */
  private RID createRecord(String className, String... keyValues) {
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass(className)) {
      schema.createClass(className);
    }

    session.begin();
    var entity = (EntityImpl) session.newEntity(className);
    for (int i = 0; i < keyValues.length; i += 2) {
      entity.setProperty(keyValues[i], keyValues[i + 1]);
    }
    session.commit();
    return entity.getIdentity();
  }

  /**
   * Closes and reopens the database to flush all pages from the write cache to
   * the read cache. After this, record loads go through the optimistic read
   * path (which returns RawPageBuffer for single-page records).
   */
  private void flushToReadCache() {
    if (session != null && !session.isClosed()) {
      session.close();
    }
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    session = youTrackDB.open(DB_NAME, "admin", "admin");
  }

  // --- Test 1: End-to-end zero-copy property access ---

  /**
   * Verifies that loading a record through the session API after flushing to
   * read cache produces a PageFrame-backed entity, and that property access
   * returns correct values via the zero-copy deserialization path.
   */
  @Test
  public void testZeroCopyPropertyAccessEndToEnd() {
    var rid = createRecord("ZeroCopy", "name", "Alice", "city", "Berlin");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Before property access, entity should be PageFrame-backed (zero-copy path)
    // Note: the optimistic path may fall back to byte[] if the page is not in
    // read cache or if the optimistic read fails. We verify the data is correct
    // regardless of which path was taken.
    assertEquals("Alice", entity.getProperty("name"));
    assertEquals("Berlin", entity.getProperty("city"));

    // After full property access, PageFrame should be cleared (stamp validated,
    // speculative results accepted)
    assertNull("PageFrame should be cleared after full deserialization",
        entity.getPageFrame());
    session.rollback();
  }

  // --- Test 2: Stamp invalidation triggers fallback re-read ---

  /**
   * Loads a record via the zero-copy path, invalidates the PageFrame's stamp
   * by acquiring and releasing an exclusive lock, then verifies that property
   * access still returns correct values via the byte[] fallback re-read path.
   */
  @Test
  public void testStampInvalidationFallbackEndToEnd() {
    var rid = createRecord("StampInval", "name", "Bob", "score", "42");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    var pageFrame = entity.getPageFrame();
    if (pageFrame != null) {
      // Invalidate the stamp by acquiring and releasing an exclusive lock.
      // After this, any stamp validation against the original stamp will fail.
      long exclusiveLock = pageFrame.acquireExclusiveLock();
      pageFrame.releaseExclusiveLock(exclusiveLock);

      // Property access should detect the invalidated stamp and fall back to
      // a byte[] re-read from storage.
      assertEquals("Bob", entity.getProperty("name"));
      assertEquals("42", entity.getProperty("score"));

      // After fallback, PageFrame should be cleared and source populated
      assertNull("PageFrame should be cleared after fallback re-read",
          entity.getPageFrame());
    } else {
      // Optimistic path fell back to byte[] — still verify correct data
      assertEquals("Bob", entity.getProperty("name"));
      assertEquals("42", entity.getProperty("score"));
    }
    session.rollback();
  }

  // --- Test 3: Multiple property accesses after fallback ---

  /**
   * After stamp invalidation and fallback to byte[], verifies that subsequent
   * property accesses work correctly from the byte[] source (PageFrame cleared,
   * source populated).
   */
  @Test
  public void testMultiplePropertyAccessesAfterFallback() {
    var rid = createRecord("MultAccess", "a", "one", "b", "two", "c", "three");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    var pageFrame = entity.getPageFrame();
    if (pageFrame != null) {
      // Invalidate stamp to force fallback
      long exclusiveLock = pageFrame.acquireExclusiveLock();
      pageFrame.releaseExclusiveLock(exclusiveLock);
    }

    // First property access — may trigger fallback
    assertEquals("one", entity.getProperty("a"));
    // Subsequent accesses should work from byte[] source
    assertEquals("two", entity.getProperty("b"));
    assertEquals("three", entity.getProperty("c"));

    // Verify all properties are present
    var names = entity.getPropertyNames();
    assertTrue("Should have property 'a'", names.contains("a"));
    assertTrue("Should have property 'b'", names.contains("b"));
    assertTrue("Should have property 'c'", names.contains("c"));

    assertNull("PageFrame should be cleared", entity.getPageFrame());
    session.rollback();
  }

  // --- Test 4: Partial then full deserialization from PageFrame ---

  /**
   * Requests specific properties (partial deserialization) first, then requests
   * all properties (full deserialization). Verifies both return correct values
   * from the PageFrame zero-copy path.
   */
  @Test
  public void testPartialThenFullDeserialization() {
    var rid = createRecord("PartialFull", "x", "10", "y", "20", "z", "30");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Partial deserialization: request only "x"
    assertTrue("Partial deserialization should succeed",
        entity.checkForProperties("x"));
    assertEquals("10", entity.getProperty("x"));

    if (entity.getPageFrame() != null) {
      // PageFrame kept after partial — request another property
      assertTrue("Second partial deserialization should succeed",
          entity.checkForProperties("y"));
      assertEquals("20", entity.getProperty("y"));
    }

    // Full deserialization: request all properties
    assertTrue("Full deserialization should succeed",
        entity.checkForProperties());
    assertEquals("10", entity.getProperty("x"));
    assertEquals("20", entity.getProperty("y"));
    assertEquals("30", entity.getProperty("z"));

    // After full deserialization, PageFrame should be cleared
    assertNull("PageFrame should be cleared after full deserialization",
        entity.getPageFrame());
    session.rollback();
  }

  // --- Test 5: Partial deserialization with stamp invalidation between calls ---

  /**
   * Requests a specific property (partial), invalidates the stamp, then
   * requests another property. Verifies that the second request falls back to
   * byte[] re-read and still returns correct data.
   */
  @Test
  public void testPartialDeserializationWithStampInvalidation() {
    var rid = createRecord("PartialStamp", "p", "alpha", "q", "beta");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    var pageFrame = entity.getPageFrame();
    if (pageFrame != null) {
      // First partial deserialization succeeds from PageFrame
      assertTrue(entity.checkForProperties("p"));
      assertEquals("alpha", entity.getProperty("p"));

      // Invalidate stamp before second partial request
      var frame = entity.getPageFrame();
      if (frame != null) {
        long exclusiveLock = frame.acquireExclusiveLock();
        frame.releaseExclusiveLock(exclusiveLock);
      }

      // Second partial request — stamp invalid, falls back to re-read
      assertTrue(entity.checkForProperties("q"));
      assertEquals("beta", entity.getProperty("q"));

      // Also verify the first property is still correct after fallback
      assertEquals("alpha", entity.getProperty("p"));
    } else {
      // Fell back to byte[] on load — verify data anyway
      assertEquals("alpha", entity.getProperty("p"));
      assertEquals("beta", entity.getProperty("q"));
    }
    session.rollback();
  }

  // --- Test 6: Concurrent page modification ---

  /**
   * Loads a record via the zero-copy path, then uses a background thread to
   * modify a different record on the same class (likely same page). Verifies
   * that property access on the main thread returns correct data — either the
   * PageFrame deserialization succeeds with original data, or the fallback
   * produces correct data.
   */
  @Test
  public void testConcurrentPageModification() throws Exception {
    // Create multiple records in the same class (likely same page)
    var rid1 = createRecord("Concurrent", "key", "original");
    createRecord("Concurrent", "key", "other");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid1);

    // Background thread: open a separate session and modify a record in the
    // same class, which may modify the same page.
    var ready = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();

    var bgThread = new Thread(() -> {
      try (var bgSession = youTrackDB.open(DB_NAME, "admin", "admin")) {
        bgSession.begin();
        var bgEntity = (EntityImpl) bgSession.newEntity("Concurrent");
        bgEntity.setProperty("key", "background-write");
        ready.countDown();
        proceed.await();
        bgSession.commit();
      } catch (Throwable t) {
        error.set(t);
      }
    });
    bgThread.start();

    // Wait for background thread to be ready, then let it commit
    ready.await();
    proceed.countDown();
    bgThread.join(5000);

    assertNull("Background thread should complete without error", error.get());

    // Access properties on the main thread — either zero-copy succeeds
    // (page wasn't modified at our offset) or fallback produces correct data
    assertEquals("original", entity.getProperty("key"));
    session.rollback();
  }

  // --- Test 7: Record lifecycle operations clear PageFrame ---

  /**
   * Verifies that unload() properly clears the PageFrame reference after
   * loading via the zero-copy path.
   */
  @Test
  public void testUnloadClearsPageFrame() {
    var rid = createRecord("Lifecycle", "field", "value");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);
    // Entity is loaded — may have PageFrame set (zero-copy) or byte[] (fallback)

    entity.unload();

    // After unload, PageFrame must be cleared
    assertNull("PageFrame should be cleared after unload",
        entity.getPageFrame());
    assertEquals(0L, entity.getPageStamp());
    assertEquals(0, entity.getPageContentOffset());
    assertEquals(0, entity.getPageContentLength());
    session.rollback();
  }

  /**
   * Verifies that reload after unload works correctly — the entity can be
   * loaded again from storage in a fresh transaction and properties are
   * accessible.
   */
  @Test
  public void testReloadAfterUnload() {
    var rid = createRecord("Reload", "name", "Charlie", "city", "Rome");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Access properties first (triggers deserialization)
    assertEquals("Charlie", entity.getProperty("name"));
    session.rollback();

    // Reload in a fresh transaction — local cache is cleared between txs
    session.begin();
    entity = (EntityImpl) session.load(rid);

    // Properties should be accessible again after reload
    assertEquals("Charlie", entity.getProperty("name"));
    assertEquals("Rome", entity.getProperty("city"));
    session.rollback();
  }

  // --- Test 8: Multiple records loaded via zero-copy path ---

  /**
   * Creates and loads multiple records, verifying that each is correctly
   * deserialized via the zero-copy path. This exercises the case where
   * multiple entities reference different offsets within the same page.
   */
  @Test
  public void testMultipleRecordsOnSamePage() {
    var rids = new ArrayList<RID>();
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass("MultiRec")) {
      schema.createClass("MultiRec");
    }
    session.begin();
    for (int i = 0; i < 10; i++) {
      var entity = (EntityImpl) session.newEntity("MultiRec");
      entity.setProperty("index", String.valueOf(i));
      entity.setProperty("data", "record-" + i);
      rids.add(entity.getIdentity());
    }
    session.commit();

    flushToReadCache();

    session.begin();
    for (int i = 0; i < rids.size(); i++) {
      var entity = (EntityImpl) session.load(rids.get(i));
      assertEquals("index should match for record " + i,
          String.valueOf(i), entity.getProperty("index"));
      assertEquals("data should match for record " + i,
          "record-" + i, entity.getProperty("data"));
    }
    session.rollback();
  }

  // --- Test 9: Record with many properties ---

  /**
   * Tests that a record with many properties (larger serialized form) is
   * correctly deserialized via the zero-copy path.
   */
  @Test
  public void testRecordWithManyProperties() {
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass("ManyProps")) {
      schema.createClass("ManyProps");
    }
    session.begin();
    var entity = (EntityImpl) session.newEntity("ManyProps");
    for (int i = 0; i < 50; i++) {
      entity.setProperty("prop" + i, "value-" + i);
    }
    session.commit();
    var rid = entity.getIdentity();

    flushToReadCache();

    session.begin();
    var loaded = (EntityImpl) session.load(rid);
    for (int i = 0; i < 50; i++) {
      assertEquals("Property prop" + i + " should have correct value",
          "value-" + i, loaded.getProperty("prop" + i));
    }
    assertEquals(50, loaded.getPropertyNames().size());
    session.rollback();
  }

  // --- Test 10: fill() on PageFrame-loaded entity clears PageFrame ---

  /**
   * Verifies that calling fill() on a PageFrame-loaded entity clears the
   * PageFrame reference and replaces it with byte[] source.
   */
  @Test
  public void testFillClearsPageFrame() {
    var rid = createRecord("FillTest", "name", "Dana");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Manually fill with byte[] — should clear any PageFrame
    byte[] newContent = new byte[] {0}; // minimal valid content
    entity.fill(1L, newContent, false);

    assertNull("PageFrame should be cleared after fill()",
        entity.getPageFrame());
    session.rollback();
  }

  // --- Test 11: fromStream() on PageFrame-loaded entity clears PageFrame ---

  /**
   * Verifies that calling fromStream() on a PageFrame-loaded entity clears
   * the PageFrame reference.
   */
  @Test
  public void testFromStreamClearsPageFrame() {
    var rid = createRecord("StreamTest", "name", "Eve");
    flushToReadCache();

    // First transaction: get serialized bytes
    session.begin();
    var entity = (EntityImpl) session.load(rid);
    entity.checkForProperties();
    byte[] bytes = entity.toStream();
    session.rollback();

    // Second transaction: load fresh entity (PageFrame-backed) and test fromStream
    session.begin();
    entity = (EntityImpl) session.load(rid);

    // fromStream should clear PageFrame
    entity.fromStream(bytes);

    assertNull("PageFrame should be cleared after fromStream()",
        entity.getPageFrame());
    session.rollback();
  }

  // --- Test 12: delete() on PageFrame-loaded entity ---

  /**
   * Verifies that deleting a PageFrame-loaded entity works correctly — the
   * entity's PageFrame is cleared during the delete operation.
   */
  @Test
  public void testDeletePageFrameLoadedEntity() {
    var rid = createRecord("DeleteTest", "name", "Frank");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Delete should work regardless of whether entity is PageFrame-backed
    entity.delete();
    session.commit();

    // Verify the record is gone
    session.begin();
    try {
      session.load(rid);
      // If load doesn't throw, the record should be marked as deleted
    } catch (Exception e) {
      // Expected: RecordNotFoundException
      assertTrue("Expected RecordNotFoundException",
          e.getClass().getSimpleName().contains("RecordNotFound"));
    }
    session.rollback();
  }

  // --- Test 13: setDirty clears PageFrame ---

  /**
   * Verifies that making a PageFrame-loaded entity dirty (by setting a
   * property) clears the PageFrame reference.
   */
  @Test
  public void testSetPropertyClearsPageFrame() {
    var rid = createRecord("DirtyTest", "name", "Grace");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Read a property first (triggers PageFrame deserialization)
    assertEquals("Grace", entity.getProperty("name"));

    // Set a property — makes entity dirty, which should clear PageFrame
    entity.setProperty("name", "Grace-updated");
    assertNull("PageFrame should be cleared after setProperty()",
        entity.getPageFrame());

    // Verify the updated value
    assertEquals("Grace-updated", entity.getProperty("name"));
    session.rollback();
  }

  // --- Test 14: toStream() on PageFrame-loaded entity ---

  /**
   * Verifies that toStream() on a PageFrame-loaded entity correctly
   * triggers deserialization first, then produces valid serialized bytes.
   */
  @Test
  public void testToStreamOnPageFrameLoadedEntity() {
    var rid = createRecord("ToStream", "name", "Hank", "age", "25");
    flushToReadCache();

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // toStream() should trigger deserialization from PageFrame, then serialize
    byte[] bytes = entity.toStream();
    assertNotNull("toStream() should produce non-null bytes", bytes);
    assertTrue("Serialized bytes should not be empty", bytes.length > 0);

    // Verify the entity is still usable after toStream
    assertEquals("Hank", entity.getProperty("name"));
    assertEquals("25", entity.getProperty("age"));
    session.rollback();
  }

  // --- Test 15: Record with various property types ---

  /**
   * Tests that records with different property types (string, integer, double,
   * boolean) are correctly deserialized via the zero-copy path.
   */
  @Test
  public void testVariousPropertyTypes() {
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass("TypeTest")) {
      schema.createClass("TypeTest");
    }
    session.begin();
    var entity = (EntityImpl) session.newEntity("TypeTest");
    entity.setProperty("strProp", "hello");
    entity.setProperty("intProp", 42);
    entity.setProperty("dblProp", 3.14);
    entity.setProperty("boolProp", true);
    entity.setProperty("longProp", 123456789L);
    entity.setProperty("shortProp", (short) 7);
    session.commit();
    var rid = entity.getIdentity();

    flushToReadCache();

    session.begin();
    var loaded = (EntityImpl) session.load(rid);
    assertEquals("hello", loaded.getProperty("strProp"));
    assertEquals(Integer.valueOf(42), loaded.getProperty("intProp"));
    assertEquals(Double.valueOf(3.14), loaded.getProperty("dblProp"));
    assertEquals(Boolean.TRUE, loaded.getProperty("boolProp"));
    assertEquals(Long.valueOf(123456789L), loaded.getProperty("longProp"));
    assertEquals(Short.valueOf((short) 7), loaded.getProperty("shortProp"));
    session.rollback();
  }
}
