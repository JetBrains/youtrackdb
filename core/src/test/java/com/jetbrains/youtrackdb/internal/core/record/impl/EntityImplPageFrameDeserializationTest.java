package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Tests for EntityImpl speculative deserialization from PageFrame with stamp
 * validation and fallback re-read. Verifies the zero-copy read path by writing
 * valid serialized record bytes into a PageFrame buffer, loading entities via
 * fillFromPage(), and checking that property access returns correct values.
 */
public class EntityImplPageFrameDeserializationTest extends DbTestBase {

  private DirectMemoryAllocator allocator;
  private Pointer pointer;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    allocator = new DirectMemoryAllocator();
    pointer = allocator.allocate(8192, true, Intention.TEST);
  }

  @After
  public void deallocatePageFrameMemory() {
    if (pointer != null && allocator != null) {
      allocator.deallocate(pointer);
    }
  }

  /**
   * Serializes an entity and writes the bytes into the PageFrame buffer at the
   * specified offset. Returns the byte length written.
   */
  private int writeSerializedRecord(EntityImpl source, PageFrame frame, int offset) {
    byte[] serialized = source.toStream();
    ByteBuffer buffer = frame.getBuffer();
    buffer.position(offset);
    buffer.put(serialized);
    return serialized.length;
  }

  /**
   * Creates a class outside of a transaction, then persists a record with the
   * given properties. Returns the RID of the saved record.
   */
  private RID saveRecord(String className, String... keyValues) {
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass(className)) {
      schema.createClass(className);
    }

    session.begin();
    var entity = (EntityImpl) session.newEntity(className);
    for (int i = 0; i < keyValues.length; i += 2) {
      entity.setString(keyValues[i], keyValues[i + 1]);
    }
    session.commit();
    return entity.getIdentity();
  }

  // --- Successful speculative deserialization ---

  @Test
  public void testDeserializeFromPageFrameFullPath() {
    // Verifies that a PageFrame-loaded entity correctly deserializes all
    // properties via the speculative PageFrame path. After full
    // deserialization (checkForProperties with no args), PageFrame is cleared.
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("name", "Alice");
    source.setInt("age", 30);
    source.setBoolean("active", true);

    var frame = new PageFrame(pointer);
    int contentLength = writeSerializedRecord(source, frame, 128);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 128, contentLength);

    // Full deserialization (no property names)
    assertTrue(entity.checkForProperties());

    // Verify properties were deserialized correctly
    assertEquals("Alice", entity.getString("name"));
    assertEquals(Integer.valueOf(30), entity.getInt("age"));
    assertTrue(entity.getBoolean("active"));

    // Verify exact property set — no phantom properties
    assertEquals(Set.of("name", "age", "active"),
        new HashSet<>(entity.getPropertyNames()));

    // After full deserialization with valid stamp, PageFrame should be cleared
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  @Test
  public void testDeserializeFromPageFramePartialPath() {
    // Verifies that partial deserialization (requesting specific properties)
    // reads correctly from PageFrame and keeps the PageFrame for subsequent calls.
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("name", "Bob");
    source.setInt("score", 42);
    source.setString("city", "Berlin");

    var frame = new PageFrame(pointer);
    int contentLength = writeSerializedRecord(source, frame, 64);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 64, contentLength);

    // Partial deserialization: request only "name"
    assertTrue(entity.checkForProperties("name"));
    assertEquals("Bob", entity.getString("name"));

    // After partial deserialization, PageFrame is kept for subsequent calls
    assertNotNull(entity.getPageFrame());

    // Request another property — should also work from PageFrame
    assertTrue(entity.checkForProperties("score"));
    assertEquals(Integer.valueOf(42), entity.getInt("score"));

    session.rollback();
  }

  @Test
  public void testDeserializeFromPageFrameAtOffsetZero() {
    // Verifies deserialization works when the record is at offset 0 in the
    // PageFrame buffer (boundary case).
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("key", "value");

    var frame = new PageFrame(pointer);
    int contentLength = writeSerializedRecord(source, frame, 0);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, contentLength);

    // Full deserialization
    assertTrue(entity.checkForProperties());
    assertEquals("value", entity.getString("key"));
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  @Test
  public void testDeserializeEmptyRecordFromPageFrame() {
    // Verifies that an entity with no properties deserializes correctly
    // from PageFrame.
    session.begin();

    var source = (EntityImpl) session.newEntity();
    byte[] serialized = source.toStream();

    var frame = new PageFrame(pointer);
    frame.getBuffer().position(300);
    frame.getBuffer().put(serialized);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp,
        300, serialized.length);

    // Full deserialization
    assertTrue(entity.checkForProperties());
    assertTrue(entity.getPropertyNames().isEmpty());
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  // --- Stamp invalidation fallback ---

  @Test
  public void testStampInvalidationIrrelevantWithEagerByteExtraction() {
    // fillFromPage eagerly extracts bytes into source, so deserialization
    // uses the byte[] path regardless of stamp validity. Stamp invalidation
    // does not affect property access. After partial deserialization,
    // pageFrame is retained (only cleared after full deserialization).
    var rid = saveRecord("StampTest", "name", "Carol", "city", "Paris");

    session.begin();
    var loaded = (EntityImpl) session.getActiveTransaction().load(rid);
    byte[] serialized = loaded.toStream();

    var frame = new PageFrame(pointer);
    frame.getBuffer().position(200);
    frame.getBuffer().put(serialized);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.getActiveTransaction().load(rid);
    entity.unsetDirty();
    entity.fillFromPage(
        loaded.getVersion(), EntityImpl.RECORD_TYPE, frame, stamp,
        200, serialized.length);

    // Invalidate the stamp by acquiring and releasing an exclusive lock
    long exclusiveStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(exclusiveStamp);

    // Access properties — uses eagerly extracted byte[] source, stamp irrelevant
    assertEquals("Carol", entity.getString("name"));
    assertEquals("Paris", entity.getString("city"));
    // PageFrame is retained after partial deserialization (individual property access)
    assertNotNull(entity.getPageFrame());
    session.rollback();
  }

  // --- RuntimeException during speculative deserialization ---

  @Test
  public void testCorruptedDataInPageFrameThrowsOnDeserialize() {
    // fillFromPage eagerly extracts bytes into source. When the PageFrame
    // contains corrupted data, the extracted byte[] source is also corrupt.
    // Deserialization via the byte[] path throws an exception — there is no
    // fallback since the corrupt bytes are the entity's source.
    var rid = saveRecord("CorruptTest", "key", "valid-data");

    session.begin();
    var loaded = (EntityImpl) session.getActiveTransaction().load(rid);

    // Write garbage data into the PageFrame (valid version byte + garbage)
    var frame = new PageFrame(pointer);
    ByteBuffer buf = frame.getBuffer();
    buf.position(100);
    buf.put((byte) 0); // serializer version byte
    for (int i = 0; i < 50; i++) {
      buf.put((byte) 0xFF);
    }
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.getActiveTransaction().load(rid);
    entity.unsetDirty();
    entity.fillFromPage(
        loaded.getVersion(), EntityImpl.RECORD_TYPE, frame, stamp,
        100, 51);

    // Corrupt source bytes cause deserialization to throw
    try {
      entity.getString("key");
      org.junit.Assert.fail(
          "Expected exception from deserializing corrupt byte[] source");
    } catch (IllegalArgumentException e) {
      // Expected — corrupt data cannot be deserialized
      assertTrue(e.getMessage().contains("Variable length quantity"));
    }
    session.rollback();
  }

  // --- Multiple property accesses after fallback ---

  @Test
  public void testMultiplePropertyAccessesAfterStampInvalidation() {
    // fillFromPage eagerly extracts bytes into source, so all property
    // accesses use the byte[] path. Stamp invalidation does not affect
    // correctness. After individual (partial) property accesses, pageFrame
    // is retained.
    var rid = saveRecord("MultiTest", "a", "alpha", "b", "beta", "c", "gamma");

    session.begin();
    var loaded = (EntityImpl) session.getActiveTransaction().load(rid);
    byte[] serialized = loaded.toStream();

    var frame = new PageFrame(pointer);
    frame.getBuffer().position(0);
    frame.getBuffer().put(serialized);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.getActiveTransaction().load(rid);
    entity.unsetDirty();
    entity.fillFromPage(
        loaded.getVersion(), EntityImpl.RECORD_TYPE, frame, stamp,
        0, serialized.length);

    // Invalidate stamp — irrelevant since byte[] source is used
    long exStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(exStamp);

    // All accesses use eagerly extracted byte[] source
    assertEquals("alpha", entity.getString("a"));
    // PageFrame retained after partial deserialization
    assertNotNull(entity.getPageFrame());

    assertEquals("beta", entity.getString("b"));
    assertEquals("gamma", entity.getString("c"));
    session.rollback();
  }

  // --- Partial then full deserialization ---

  @Test
  public void testPartialThenFullDeserialization() {
    // Request specific properties first (partial), then request all properties
    // (full). Both should return correct values from PageFrame.
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("x", "ex");
    source.setString("y", "why");
    source.setInt("z", 99);

    var frame = new PageFrame(pointer);
    int contentLength = writeSerializedRecord(source, frame, 50);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 50, contentLength);

    // Partial: request "x" only
    assertTrue(entity.checkForProperties("x"));
    assertEquals("ex", entity.getString("x"));
    assertNotNull(entity.getPageFrame()); // kept for subsequent calls

    // Full: request all remaining properties
    assertTrue(entity.checkForProperties());
    assertEquals("why", entity.getString("y"));
    assertEquals(Integer.valueOf(99), entity.getInt("z"));
    // Partial-loaded property "x" survives full deserialization
    assertEquals("ex", entity.getString("x"));

    // After full deserialization, PageFrame should be cleared
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  // --- Snapshot restoration on partial deser + stamp invalidation (TC1) ---

  @Test
  public void testPartialDeserializationUnaffectedByStampInvalidation() {
    // fillFromPage eagerly extracts bytes into source. Both partial
    // deserialization calls use the byte[] path, so stamp invalidation
    // between them has no effect. PageFrame is retained after partial access.
    var rid = saveRecord("SnapshotTest", "a", "alpha", "b", "beta");

    session.begin();
    var loaded = (EntityImpl) session.getActiveTransaction().load(rid);
    byte[] serialized = loaded.toStream();

    var frame = new PageFrame(pointer);
    frame.getBuffer().position(64);
    frame.getBuffer().put(serialized);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.getActiveTransaction().load(rid);
    entity.unsetDirty();
    entity.fillFromPage(
        loaded.getVersion(), EntityImpl.RECORD_TYPE, frame, stamp,
        64, serialized.length);

    // First partial deserialization from byte[] source
    assertTrue(entity.checkForProperties("a"));
    assertEquals("alpha", entity.getString("a"));
    assertNotNull(entity.getPageFrame());

    // Invalidate stamp — irrelevant since byte[] source is used
    long exStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(exStamp);

    // Second partial also uses byte[] source — both properties correct
    assertTrue(entity.checkForProperties("b"));
    assertEquals("beta", entity.getString("b"));
    assertEquals("alpha", entity.getString("a"));
    // PageFrame retained after partial deserialization
    assertNotNull(entity.getPageFrame());
    session.rollback();
  }

  // --- Missing property returns false (TC2) ---

  @Test
  public void testPartialDeserializationReturnsFalseForMissingProperty() {
    // Verifies that requesting a property that doesn't exist in the record
    // returns false from the PageFrame deserialization path.
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("exists", "value");

    var frame = new PageFrame(pointer);
    int contentLength = writeSerializedRecord(source, frame, 0);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, contentLength);

    assertFalse(entity.deserializeProperties("nonExistent"));
    session.rollback();
  }

  // --- Record at buffer end boundary (TC4) ---

  @Test
  public void testDeserializeFromPageFrameAtBufferEnd() {
    // Verifies deserialization works when record content extends to the exact
    // end of the PageFrame buffer (offset + length == buffer capacity).
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("key", "boundary");
    byte[] serialized = source.toStream();

    var frame = new PageFrame(pointer);
    int offset = 8192 - serialized.length;
    frame.getBuffer().position(offset);
    frame.getBuffer().put(serialized);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(
        1L, EntityImpl.RECORD_TYPE, frame, stamp, offset, serialized.length);

    assertTrue(entity.checkForProperties());
    assertEquals("boundary", entity.getString("key"));
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  // --- Speculative PageFrame deserialization (lazy-extraction simulation) ---

  @Test
  public void testSpeculativeDeserializationFromPageFrameFullPath() {
    // Simulates lazy extraction: fillFromPage sets source, then
    // clearSourceKeepPageFrame() clears source while keeping PageFrame.
    // This forces deserializeProperties to use the speculative
    // deserializeFromPageFrame() path with stamp validation.
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("name", "speculative");
    source.setInt("count", 7);

    var frame = new PageFrame(pointer);
    int contentLength = writeSerializedRecord(source, frame, 100);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 100, contentLength);

    // Clear source while keeping PageFrame — forces speculative path
    entity.clearSourceKeepPageFrame();
    assertNotNull(entity.getPageFrame());

    // Full deserialization via the speculative PageFrame path
    assertTrue(entity.checkForProperties());
    assertEquals("speculative", entity.getString("name"));
    assertEquals(Integer.valueOf(7), entity.getInt("count"));

    // After successful speculative full deserialization, PageFrame is cleared
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  @Test
  public void testSpeculativeDeserializationPartialPath() {
    // Exercises the speculative PageFrame partial deserialization path.
    // After partial deserialization with valid stamp, PageFrame is kept.
    session.begin();

    var source = (EntityImpl) session.newEntity();
    source.setString("x", "ex");
    source.setString("y", "why");
    source.setInt("z", 42);

    var frame = new PageFrame(pointer);
    int contentLength = writeSerializedRecord(source, frame, 200);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 200, contentLength);

    // Clear source to activate speculative path
    entity.clearSourceKeepPageFrame();

    // Partial deserialization: request only "y"
    assertTrue(entity.checkForProperties("y"));
    assertEquals("why", entity.getString("y"));

    session.rollback();
  }

  @Test
  public void testSpeculativeDeserializationWithInvalidStampFallsBack() {
    // Exercises the fallback path when stamp becomes invalid after
    // speculative deserialization. The entity must re-read from storage.
    var rid = saveRecord("SpecFallback", "key", "fallback-value");

    session.begin();
    var loaded = (EntityImpl) session.getActiveTransaction().load(rid);
    byte[] serialized = loaded.toStream();

    var frame = new PageFrame(pointer);
    frame.getBuffer().position(64);
    frame.getBuffer().put(serialized);
    long stamp = frame.tryOptimisticRead();

    // Load a fresh entity with the same RID so the fallback re-read works
    var entity = (EntityImpl) session.getActiveTransaction().load(rid);
    entity.unsetDirty();
    entity.fillFromPage(
        loaded.getVersion(), EntityImpl.RECORD_TYPE, frame, stamp,
        64, serialized.length);

    // Clear source to force speculative path
    entity.clearSourceKeepPageFrame();

    // Invalidate stamp — this causes speculative deserialization to succeed
    // but stamp validation to fail, triggering the fallback re-read
    long exStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(exStamp);

    // Property access triggers speculative deser → stamp invalid → fallback re-read
    assertEquals("fallback-value", entity.getString("key"));
    // After fallback, PageFrame is cleared
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  @Test
  public void testSpeculativeDeserializationEmptyContentClearsPageFrame() {
    // When pageContentLength <= 0, deserializeFromPageFrame should just
    // clear PageFrame and return true without attempting deserialization.
    session.begin();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    // Fill with zero-length content
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 0);

    // Clear source to force speculative path with empty content
    entity.clearSourceKeepPageFrame();
    assertNotNull(entity.getPageFrame());

    // Should succeed immediately and clear PageFrame
    assertTrue(entity.checkForProperties());
    assertNull(entity.getPageFrame());
    session.rollback();
  }

}
