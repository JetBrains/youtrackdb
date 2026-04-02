package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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
    assert keyValues.length % 2 == 0 : "keyValues must be key-value pairs";

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
   * Closes and reopens the database to flush all pages from the write cache,
   * then warms up the read cache by loading the given records through the
   * pinned path. After warming, subsequent record loads in new transactions
   * go through the optimistic read path (returning RawPageBuffer for
   * single-page records).
   *
   * <p>The warmup step is necessary because the optimistic path requires
   * pages to already be present in the read cache. The first load after
   * reopen always goes through the pinned path; only subsequent loads can
   * use the optimistic path.
   */
  private void flushToReadCache(RID... warmupRids) {
    if (session != null && !session.isClosed()) {
      session.close();
    }
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    session = youTrackDB.open(DB_NAME, "admin", "admin");

    // Warm up the read cache: load records through the pinned path so their
    // pages are populated in the read cache for subsequent optimistic reads.
    if (warmupRids.length > 0) {
      session.begin();
      for (var rid : warmupRids) {
        session.load(rid);
      }
      session.rollback();
    }
  }

  // --- End-to-end zero-copy property access ---

  /**
   * Verifies that loading a record through the session API after flushing to
   * read cache produces a PageFrame-backed entity, and that property access
   * returns correct values via the zero-copy deserialization path.
   */
  @Test
  public void testZeroCopyPropertyAccessEndToEnd() {
    var rid = createRecord("ZeroCopy", "name", "Alice", "city", "Berlin");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Verify the zero-copy path was taken: PageFrame should be set before
    // any property access triggers deserialization.
    assertNotNull(
        "Entity should be PageFrame-backed after flush-to-read-cache load",
        entity.getPageFrame());

    // Individual getProperty() calls trigger partial deserialization, which
    // keeps the PageFrame for subsequent calls.
    assertEquals("Alice", entity.getProperty("name"));
    assertEquals("Berlin", entity.getProperty("city"));

    // PageFrame is still set after partial accesses
    assertNotNull("PageFrame kept after partial property accesses",
        entity.getPageFrame());

    // Full deserialization (no args) clears the PageFrame
    assertTrue("Full deserialization should succeed",
        entity.checkForProperties());

    assertNull("PageFrame should be cleared after full deserialization",
        entity.getPageFrame());
    session.rollback();
  }

  // --- Stamp invalidation triggers fallback re-read ---

  /**
   * Loads a record via the zero-copy path, invalidates the PageFrame's stamp
   * by acquiring and releasing an exclusive lock, then verifies that property
   * access still returns correct values via the byte[] fallback re-read path.
   */
  @Test
  public void testStampInvalidationFallbackEndToEnd() {
    var rid = createRecord("StampInval", "name", "Bob", "score", "42");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    var pageFrame = entity.getPageFrame();
    assertNotNull(
        "Entity should be PageFrame-backed after flush-to-read-cache load",
        pageFrame);

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
    session.rollback();
  }

  // --- Multiple property accesses after fallback ---

  /**
   * After stamp invalidation and fallback to byte[], verifies that subsequent
   * property accesses work correctly from the byte[] source (PageFrame cleared,
   * source populated).
   */
  @Test
  public void testMultiplePropertyAccessesAfterFallback() {
    var rid = createRecord("MultAccess", "a", "one", "b", "two", "c", "three");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    var pageFrame = entity.getPageFrame();
    assertNotNull(
        "Entity should be PageFrame-backed after flush-to-read-cache load",
        pageFrame);

    // Invalidate stamp to force fallback
    long exclusiveLock = pageFrame.acquireExclusiveLock();
    pageFrame.releaseExclusiveLock(exclusiveLock);

    // First property access — triggers fallback
    assertEquals("one", entity.getProperty("a"));
    // Subsequent accesses should work from byte[] source
    assertEquals("two", entity.getProperty("b"));
    assertEquals("three", entity.getProperty("c"));

    // Verify all properties are present with exact count
    var names = entity.getPropertyNames();
    assertEquals("Should have exactly 3 properties", 3, names.size());
    assertTrue("Should have property 'a'", names.contains("a"));
    assertTrue("Should have property 'b'", names.contains("b"));
    assertTrue("Should have property 'c'", names.contains("c"));

    assertNull("PageFrame should be cleared", entity.getPageFrame());
    session.rollback();
  }

  // --- Partial then full deserialization from PageFrame ---

  /**
   * Requests specific properties (partial deserialization) first, then requests
   * all properties (full deserialization). Verifies both return correct values
   * from the PageFrame zero-copy path.
   */
  @Test
  public void testPartialThenFullDeserialization() {
    var rid = createRecord("PartialFull", "x", "10", "y", "20", "z", "30");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    assertNotNull(
        "Entity should be PageFrame-backed after flush-to-read-cache load",
        entity.getPageFrame());

    // Partial deserialization: request only "x"
    assertTrue("Partial deserialization should succeed",
        entity.checkForProperties("x"));
    assertEquals("10", entity.getProperty("x"));

    // PageFrame kept after partial — request another property
    assertNotNull("PageFrame should be kept after partial deserialization",
        entity.getPageFrame());
    assertTrue("Second partial deserialization should succeed",
        entity.checkForProperties("y"));
    assertEquals("20", entity.getProperty("y"));

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

  // --- Partial deserialization with stamp invalidation between calls ---

  /**
   * Requests a specific property (partial), invalidates the stamp, then
   * requests another property. Verifies that the second request falls back to
   * byte[] re-read and still returns correct data.
   */
  @Test
  public void testPartialDeserializationWithStampInvalidation() {
    var rid = createRecord("PartialStamp", "p", "alpha", "q", "beta");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    var pageFrame = entity.getPageFrame();
    assertNotNull(
        "Entity should be PageFrame-backed after flush-to-read-cache load",
        pageFrame);

    // First partial deserialization succeeds from PageFrame
    assertTrue(entity.checkForProperties("p"));
    assertEquals("alpha", entity.getProperty("p"));

    // Invalidate stamp before second partial request
    var frame = entity.getPageFrame();
    assertNotNull("PageFrame should be kept after partial deserialization",
        frame);
    long exclusiveLock = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(exclusiveLock);

    // Second partial request — stamp invalid, falls back to re-read
    assertTrue(entity.checkForProperties("q"));
    assertEquals("beta", entity.getProperty("q"));

    // Also verify the first property is still correct after fallback
    assertEquals("alpha", entity.getProperty("p"));
    session.rollback();
  }

  // --- Concurrent page modification with temporal overlap ---

  /**
   * Loads records in a reader thread and modifies records in a writer thread,
   * using a CyclicBarrier to ensure temporal overlap between deserialization
   * and page writes. Verifies that the reader always sees correct data —
   * either via successful speculative deserialization or via the fallback path.
   */
  @Test
  public void testConcurrentPageModification() throws Exception {
    // Create enough records that they share a page
    var rids = new ArrayList<RID>();
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass("ConcMod")) {
      schema.createClass("ConcMod");
    }
    session.begin();
    for (int i = 0; i < 20; i++) {
      var entity = (EntityImpl) session.newEntity("ConcMod");
      entity.setProperty("key", "value-" + i);
      rids.add(entity.getIdentity());
    }
    session.commit();
    flushToReadCache(rids.toArray(new RID[0]));

    // Run multiple iterations to increase the chance of temporal overlap
    int iterations = 20;
    var errors = new AtomicReference<Throwable>();

    for (int iter = 0; iter < iterations && errors.get() == null; iter++) {
      var ridToRead = rids.get(0);
      var barrier = new CyclicBarrier(2);
      var readerDone = new CountDownLatch(1);
      var writerDone = new CountDownLatch(1);

      // Reader thread: loads entity (gets PageFrame), waits at barrier,
      // then accesses properties (triggers deserializeFromPageFrame)
      var readerThread = new Thread(() -> {
        try (var rSession = youTrackDB.open(DB_NAME, "admin", "admin")) {
          rSession.begin();
          var entity = (EntityImpl) rSession.load(ridToRead);
          barrier.await(5, TimeUnit.SECONDS);
          // Property access triggers speculative deserialization
          assertEquals("value-0", entity.getProperty("key"));
          rSession.rollback();
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        } finally {
          readerDone.countDown();
        }
      });

      // Writer thread: opens session, waits at barrier, then creates a
      // new record in the same class (likely same page)
      var writerThread = new Thread(() -> {
        try (var wSession = youTrackDB.open(DB_NAME, "admin", "admin")) {
          wSession.begin();
          var entity = (EntityImpl) wSession.newEntity("ConcMod");
          entity.setProperty("key", "bg-" + Thread.currentThread().getId());
          barrier.await(5, TimeUnit.SECONDS);
          wSession.commit();
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        } finally {
          writerDone.countDown();
        }
      });

      readerThread.start();
      writerThread.start();

      assertTrue("Reader should complete within timeout",
          readerDone.await(10, TimeUnit.SECONDS));
      assertTrue("Writer should complete within timeout",
          writerDone.await(10, TimeUnit.SECONDS));
    }

    assertNull("No thread should fail: "
        + (errors.get() != null ? errors.get().getMessage() : ""), errors.get());
  }

  // --- Concurrent readers on the same record ---

  /**
   * Multiple threads load the same record simultaneously through the zero-copy
   * path. Verifies that concurrent PageFrame deserialization (each thread gets
   * its own ByteBuffer slice) does not interfere across threads.
   */
  @Test
  public void testConcurrentReadersOnSameRecord() throws Exception {
    var rid = createRecord("ConcRead", "name", "shared", "city", "Tokyo");
    flushToReadCache(rid);

    int threadCount = 8;
    var barrier = new CyclicBarrier(threadCount);
    var errors = new AtomicReference<Throwable>();
    var latch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      new Thread(() -> {
        try (var s = youTrackDB.open(DB_NAME, "admin", "admin")) {
          s.begin();
          var entity = (EntityImpl) s.load(rid);
          barrier.await(5, TimeUnit.SECONDS);
          assertEquals("shared", entity.getProperty("name"));
          assertEquals("Tokyo", entity.getProperty("city"));
          s.rollback();
        } catch (Throwable e) {
          errors.compareAndSet(null, e);
        } finally {
          latch.countDown();
        }
      }).start();
    }

    assertTrue("All threads should complete",
        latch.await(15, TimeUnit.SECONDS));
    assertNull("No thread should fail: "
        + (errors.get() != null ? errors.get().getMessage() : ""), errors.get());
  }

  // --- Record lifecycle operations clear PageFrame ---

  /**
   * Verifies that unload() properly clears the PageFrame reference after
   * loading via the zero-copy path.
   */
  @Test
  public void testUnloadClearsPageFrame() {
    var rid = createRecord("Lifecycle", "field", "value");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

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
    flushToReadCache(rid);

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

  // --- Multiple records loaded via zero-copy path ---

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

    flushToReadCache(rids.toArray(new RID[0]));

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

  // --- Record with many properties ---

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

    flushToReadCache(rid);

    session.begin();
    var loaded = (EntityImpl) session.load(rid);
    for (int i = 0; i < 50; i++) {
      assertEquals("Property prop" + i + " should have correct value",
          "value-" + i, loaded.getProperty("prop" + i));
    }
    assertEquals(50, loaded.getPropertyNames().size());
    session.rollback();
  }

  // --- fill() on PageFrame-loaded entity clears PageFrame ---

  /**
   * Verifies that calling fill() on a PageFrame-loaded entity clears the
   * PageFrame reference and replaces it with byte[] source.
   */
  @Test
  public void testFillClearsPageFrame() {
    var rid = createRecord("FillTest", "name", "Dana");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Verify zero-copy path was taken before testing fill()
    assertNotNull("Entity should be PageFrame-backed before fill()",
        entity.getPageFrame());

    // Content doesn't need to be a valid serialized record — we only verify
    // that fill() clears the PageFrame reference.
    byte[] newContent = new byte[] {0};
    entity.fill(1L, newContent, false);

    assertNull("PageFrame should be cleared after fill()",
        entity.getPageFrame());
    session.rollback();
  }

  // --- fromStream() on PageFrame-loaded entity clears PageFrame ---

  /**
   * Verifies that calling fromStream() on a PageFrame-loaded entity clears
   * the PageFrame reference.
   */
  @Test
  public void testFromStreamClearsPageFrame() {
    var rid = createRecord("StreamTest", "name", "Eve");
    flushToReadCache(rid);

    // First transaction: get serialized bytes
    session.begin();
    var entity = (EntityImpl) session.load(rid);
    entity.checkForProperties();
    byte[] bytes = entity.toStream();
    session.rollback();

    // Second transaction: load fresh entity (PageFrame-backed) and test fromStream
    session.begin();
    entity = (EntityImpl) session.load(rid);

    // Verify zero-copy path was taken before testing fromStream()
    assertNotNull("Entity should be PageFrame-backed before fromStream()",
        entity.getPageFrame());

    // fromStream should clear PageFrame
    entity.fromStream(bytes);

    assertNull("PageFrame should be cleared after fromStream()",
        entity.getPageFrame());
    session.rollback();
  }

  // --- delete() on PageFrame-loaded entity ---

  /**
   * Verifies that deleting a PageFrame-loaded entity works correctly — the
   * entity's PageFrame is cleared during the delete operation.
   */
  @Test
  public void testDeletePageFrameLoadedEntity() {
    var rid = createRecord("DeleteTest", "name", "Frank");
    flushToReadCache(rid);

    session.begin();
    var entity = (EntityImpl) session.load(rid);

    // Delete should work regardless of whether entity is PageFrame-backed
    entity.delete();
    session.commit();

    // Verify the record is gone
    session.begin();
    try {
      session.load(rid);
      fail("Expected RecordNotFoundException when loading deleted record");
    } catch (RecordNotFoundException e) {
      // Expected: record was deleted
    }
    session.rollback();
  }

  // --- setDirty clears PageFrame ---

  /**
   * Verifies that making a PageFrame-loaded entity dirty (by setting a
   * property) clears the PageFrame reference.
   */
  @Test
  public void testSetPropertyClearsPageFrame() {
    var rid = createRecord("DirtyTest", "name", "Grace");
    flushToReadCache(rid);

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

  // --- toStream() on PageFrame-loaded entity ---

  /**
   * Verifies that toStream() on a PageFrame-loaded entity correctly
   * triggers deserialization first, then produces valid serialized bytes.
   */
  @Test
  public void testToStreamOnPageFrameLoadedEntity() {
    var rid = createRecord("ToStream", "name", "Hank", "age", "25");
    flushToReadCache(rid);

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

  // --- Record with various property types ---

  /**
   * Tests that records with different property types (string, integer, double,
   * boolean, long, short) are correctly deserialized via the zero-copy path.
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

    flushToReadCache(rid);

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

  // --- Embedded document through zero-copy path ---

  /**
   * Verifies that records with embedded documents (the most complex serialized
   * type, involving recursive nested entity serialization) deserialize
   * correctly via the zero-copy PageFrame path.
   */
  @Test
  public void testZeroCopyWithEmbeddedDocument() {
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass("EmbedTest")) {
      schema.createClass("EmbedTest");
    }
    session.begin();
    var entity = (EntityImpl) session.newEntity("EmbedTest");
    entity.setProperty("name", "parent");
    var embedded = session.newEmbeddedEntity();
    embedded.setProperty("street", "123 Main St");
    embedded.setProperty("zip", 12345);
    entity.setProperty("address", embedded);
    session.commit();
    var rid = entity.getIdentity();

    flushToReadCache(rid);

    session.begin();
    var loaded = (EntityImpl) session.load(rid);

    assertNotNull(
        "Entity should be PageFrame-backed after flush-to-read-cache load",
        loaded.getPageFrame());

    assertEquals("parent", loaded.getProperty("name"));
    var addr = loaded.getEmbeddedEntity("address");
    assertNotNull("Embedded entity should not be null", addr);
    assertEquals("123 Main St", addr.getProperty("street"));
    assertEquals(Integer.valueOf(12345), addr.getProperty("zip"));
    session.rollback();
  }

  // --- Link (RID reference) property through zero-copy path ---

  /**
   * Verifies that link (RID) properties deserialize correctly via the
   * zero-copy path. Links are serialized as fixed-width (clusterId,
   * clusterPosition) pairs — incorrect base offset in the ByteBuffer slice
   * would produce wrong RIDs silently.
   */
  @Test
  public void testZeroCopyWithLinkProperty() {
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass("LinkSource")) {
      schema.createClass("LinkSource");
    }
    if (!schema.existsClass("LinkTarget")) {
      schema.createClass("LinkTarget");
    }

    session.begin();
    var target = (EntityImpl) session.newEntity("LinkTarget");
    target.setProperty("label", "target");
    session.commit();
    var targetRid = target.getIdentity();

    session.begin();
    var source = (EntityImpl) session.newEntity("LinkSource");
    source.setProperty("name", "source");
    source.setProperty("ref", session.load(targetRid));
    session.commit();
    var sourceRid = source.getIdentity();

    flushToReadCache(sourceRid, targetRid);

    session.begin();
    var loaded = (EntityImpl) session.load(sourceRid);

    assertNotNull(
        "Entity should be PageFrame-backed after flush-to-read-cache load",
        loaded.getPageFrame());

    assertEquals("source", loaded.getProperty("name"));
    var linkedEntity = loaded.getEntity("ref");
    assertNotNull("Linked entity should not be null", linkedEntity);
    assertEquals(targetRid, linkedEntity.getIdentity());
    assertEquals("target", linkedEntity.getProperty("label"));
    session.rollback();
  }
}
