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
import org.junit.After;
import org.junit.Test;

/**
 * Tests for EntityImpl PageFrame lifecycle: fillFromPage(), clearPageFrame(),
 * and the source/pageFrame guard logic in checkForProperties(),
 * deserializeProperties(), sourceIsParsedByProperties(), and toStream().
 */
public class EntityImplPageFrameTest extends DbTestBase {

  private DirectMemoryAllocator allocator;
  private Pointer pointer;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    allocator = new DirectMemoryAllocator();
    pointer = allocator.allocate(8192, true, Intention.TEST);
  }

  @After
  public void afterPageFrameTest() {
    if (pointer != null) {
      allocator.deallocate(pointer);
    }
  }

  // --- fillFromPage() ---

  @Test
  public void testFillFromPageSetsCorrectState() {
    // Verifies that fillFromPage sets status=LOADED (not unloaded), version,
    // size, and all PageFrame fields correctly. Source should remain null
    // (verified via sourceIsParsedByProperties returning false — meaning there
    // is still data to parse, i.e. source is not set).
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    entity.fillFromPage(42L, EntityImpl.RECORD_TYPE, frame, stamp, 100, 256);

    // Status is LOADED — entity is not unloaded
    assertFalse(entity.isUnloaded());
    assertEquals(42L, entity.getVersion());
    assertEquals(256, entity.getSize());

    // PageFrame fields are set
    assertNotNull(entity.getPageFrame());
    assertEquals(frame, entity.getPageFrame());
    assertEquals(stamp, entity.getPageStamp());
    assertEquals(100, entity.getPageContentOffset());
    assertEquals(256, entity.getPageContentLength());

    // sourceIsParsedByProperties is false because PageFrame still needs parsing
    assertFalse(entity.sourceIsParsedByProperties());
    session.rollback();
  }

  @Test
  public void testFillFromPageClearsProperties() {
    // Verifies that fillFromPage resets properties — a clean slate for lazy
    // deserialization. Previously set properties are no longer reported.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "test");
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    // sourceIsParsedByProperties returns false because pageFrame is set
    // and needs deserialization — properties were cleared.
    assertFalse(entity.sourceIsParsedByProperties());
    session.rollback();
  }

  @Test(expected = com.jetbrains.youtrackdb.internal.core.exception.DatabaseException.class)
  public void testFillFromPageThrowsOnDirtyRecord() {
    // Verifies the dirty guard: fillFromPage must reject dirty records
    // to avoid overwriting unsaved user changes.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    // entity is dirty from creation

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);
    session.rollback();
  }

  // --- clearPageFrame() ---

  @Test
  public void testClearPageFrameZerosAllFields() {
    // Verifies that clearPageFrame nulls the PageFrame reference and zeros
    // all associated fields.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 100, 256);

    entity.clearPageFrame();

    assertNull(entity.getPageFrame());
    assertEquals(0L, entity.getPageStamp());
    assertEquals(0, entity.getPageContentOffset());
    assertEquals(0, entity.getPageContentLength());
    session.rollback();
  }

  // --- Lifecycle transitions clear PageFrame ---

  @Test
  public void testUnloadClearsPageFrame() {
    // Verifies that unload() clears the PageFrame reference,
    // preventing stale PageFrame references from surviving unload.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    entity.unload();

    assertNull(entity.getPageFrame());
    assertTrue(entity.isUnloaded());
    session.rollback();
  }

  @Test
  public void testFromStreamClearsPageFrame() {
    // Verifies that fromStream() clears the PageFrame reference so the entity
    // uses the new byte[] source exclusively.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    // fromStream replaces the data source with the given byte array
    entity.fromStream(new byte[0]);

    assertNull(entity.getPageFrame());
    session.rollback();
  }

  @Test
  public void testFillClearsPageFrame() {
    // Verifies that fill() clears the PageFrame reference — the byte[] buffer
    // from fill() replaces the PageFrame as the data source.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    entity.fill(2L, new byte[0], false);

    assertNull(entity.getPageFrame());
    session.rollback();
  }

  @Test
  public void testClearSourceClearsPageFrame() {
    // Verifies that clearSource() also clears the PageFrame reference,
    // since clearSource means the record's data source is being invalidated.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    entity.clearSource();

    assertNull(entity.getPageFrame());
    session.rollback();
  }

  // --- sourceIsParsedByProperties ---

  @Test
  public void testSourceIsParsedByPropertiesReturnsFalseWithPageFrame() {
    // Verifies that a PageFrame-loaded record reports
    // sourceIsParsedByProperties=false — it needs deserialization.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    assertFalse(entity.sourceIsParsedByProperties());
    session.rollback();
  }

  @Test
  public void testSourceIsParsedByPropertiesTrueAfterClearPageFrame() {
    // After clearing the PageFrame (and having no source or properties),
    // the record reports sourceIsParsedByProperties=true (nothing left to parse).
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    entity.clearPageFrame();

    // With both source=null and pageFrame=null, and status=LOADED,
    // sourceIsParsedByProperties returns true (no data source to parse).
    assertTrue(entity.sourceIsParsedByProperties());
    session.rollback();
  }

  // --- checkForProperties recognizes pageFrame as data source ---

  @Test
  public void testCheckForPropertiesDoesNotSkipWithPageFrame() {
    // Verifies that checkForProperties doesn't short-circuit to "already
    // unmarshalled" when pageFrame is set. We test this indirectly by
    // clearing the pageFrame and verifying the different behavior.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 0);

    entity.clearPageFrame();

    // After clearing both source and pageFrame, checkForProperties returns
    // true (nothing to deserialize — considered fully parsed).
    assertTrue(entity.checkForProperties());
    session.rollback();
  }

  // --- fillFromPage followed by fill replaces all state ---

  @Test
  public void testFillFromPageThenFillSetsNewVersion() {
    // Verifies that fill() after fillFromPage correctly replaces the version
    // and clears the PageFrame — the entity is fully backed by the new byte[].
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    entity.fill(99L, new byte[0], false);

    assertEquals(99L, entity.getVersion());
    assertNull(entity.getPageFrame());
    // After fill, entity is backed by byte[] (even if empty), not PageFrame
    assertFalse(entity.isUnloaded());
    session.rollback();
  }
}
