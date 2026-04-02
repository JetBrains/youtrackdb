package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
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
  public void afterTest() {
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
    assertEquals(true, entity.getBoolean("active"));

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
  public void testStampInvalidationFallsBackToReRead() {
    // Verifies that when the PageFrame stamp is invalidated, the entity
    // falls back to a byte[] re-read from storage.
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

    // Access properties — stamp validation fails, triggers re-read
    assertEquals("Carol", entity.getString("name"));
    assertEquals("Paris", entity.getString("city"));
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  // --- RuntimeException during speculative deserialization ---

  @Test
  public void testCorruptedDataFallsBackToReRead() {
    // Verifies that corrupted data in the PageFrame triggers a RuntimeException
    // during speculative deserialization, which is caught, and the entity
    // falls back to a byte[] re-read from storage.
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

    // Access property — corrupted data triggers exception, falls back to re-read
    assertEquals("valid-data", entity.getString("key"));
    assertNull(entity.getPageFrame());
    session.rollback();
  }

  // --- Multiple property accesses after fallback ---

  @Test
  public void testMultiplePropertyAccessesAfterFallback() {
    // After stamp invalidation and fallback to byte[], subsequent property
    // accesses should work correctly from the byte[] source.
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

    // Invalidate stamp
    long exStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(exStamp);

    // First access triggers fallback
    assertEquals("alpha", entity.getString("a"));
    assertNull(entity.getPageFrame());

    // Subsequent accesses use byte[] source
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

    // After full deserialization, PageFrame should be cleared
    assertNull(entity.getPageFrame());
    session.rollback();
  }
}
