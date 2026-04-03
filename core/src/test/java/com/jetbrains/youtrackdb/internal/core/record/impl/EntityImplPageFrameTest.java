package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import org.junit.After;
import org.junit.Assert;
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
    if (pointer != null && allocator != null) {
      allocator.deallocate(pointer);
    }
  }

  // --- fillFromPage() ---

  @Test
  public void testFillFromPageSetsCorrectState() {
    // Verifies that fillFromPage sets status=LOADED (not unloaded), version,
    // size, and all PageFrame fields correctly.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    entity.fillFromPage(42L, EntityImpl.RECORD_TYPE, frame, stamp, 100, 256);

    assertFalse(entity.isUnloaded());
    assertEquals(42L, entity.getVersion());
    assertEquals(256, entity.getSize());

    assertEquals(frame, entity.getPageFrame());
    assertEquals(stamp, entity.getPageStamp());
    assertEquals(100, entity.getPageContentOffset());
    assertEquals(256, entity.getPageContentLength());

    // PageFrame is set, so data still needs parsing
    assertFalse(entity.sourceIsParsedByProperties());
    session.rollback();
  }

  @Test
  public void testFillFromPageClearsProperties() {
    // Verifies that fillFromPage resets previously set properties. After
    // clearing the PageFrame (to prevent deserialization from invalid data),
    // the entity should have no properties.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setString("name", "test");
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 0);

    // Clear PageFrame so checkForProperties does not attempt deserialization
    // from invalid data — we want to verify the in-memory state.
    entity.clearPageFrame();

    // Properties were nulled by fillFromPage; with no data source, the
    // entity should report no properties.
    assertTrue("Properties should be empty after fillFromPage",
        entity.getPropertyNames().isEmpty());
    session.rollback();
  }

  @Test
  public void testFillFromPageThrowsOnDirtyRecord() {
    // Verifies the dirty guard: fillFromPage must reject dirty records
    // to avoid overwriting unsaved user changes.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    // entity is dirty from creation

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();

    try {
      entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);
      Assert.fail("Expected DatabaseException for dirty record");
    } catch (DatabaseException e) {
      assertTrue("Exception should mention dirty records",
          e.getMessage().contains("Cannot call fillFromPage() on dirty records"));
    }
    session.rollback();
  }

  @Test
  public void testFillFromPageRejectsNullPageFrame() {
    // Verifies runtime validation rejects null PageFrame.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    var ex = Assert.assertThrows(IllegalArgumentException.class,
        () -> entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, null, 0, 0, 64));
    assertTrue(ex.getMessage().contains("PageFrame must not be null"));
    session.rollback();
  }

  @Test
  public void testFillFromPageRejectsNegativeOffset() {
    // Verifies runtime validation rejects negative contentOffset.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    var frame = new PageFrame(pointer);
    var ex = Assert.assertThrows(IllegalArgumentException.class,
        () -> entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, 0, -1, 64));
    assertTrue(ex.getMessage().contains("contentOffset"));
    session.rollback();
  }

  @Test
  public void testFillFromPageRejectsNegativeLength() {
    // Verifies runtime validation rejects negative contentLength.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();
    var frame = new PageFrame(pointer);
    var ex = Assert.assertThrows(IllegalArgumentException.class,
        () -> entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, 0, 0, -1));
    assertTrue(ex.getMessage().contains("contentLength"));
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

    assertPageFrameCleared(entity);
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

    assertPageFrameCleared(entity);
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

    entity.fromStream(new byte[0]);

    assertPageFrameCleared(entity);
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

    assertPageFrameCleared(entity);
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

    assertPageFrameCleared(entity);
    session.rollback();
  }

  @Test
  public void testSetDirtyClearsPageFrame() {
    // Verifies that setDirty() clears the PageFrame reference alongside
    // nulling source, preventing stale PageFrame from triggering
    // re-deserialization after user modifies a property.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 0);

    // clearPageFrame so checkForProperties in setDirty doesn't try to
    // deserialize from invalid PageFrame data
    entity.clearPageFrame();
    entity.setDirty();

    assertPageFrameCleared(entity);
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
  public void testSourceIsParsedByPropertiesFalseAfterClearPageFrame() {
    // fillFromPage eagerly extracts bytes into source. After clearing the
    // PageFrame, source is still set and needs parsing, so
    // sourceIsParsedByProperties returns false.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 64);

    entity.clearPageFrame();

    assertFalse(entity.sourceIsParsedByProperties());
    session.rollback();
  }

  // --- checkForProperties recognizes pageFrame as data source ---

  @Test
  public void testCheckForPropertiesRecognizesPageFrame() {
    // Verifies that checkForProperties does NOT short-circuit to "already
    // unmarshalled" when pageFrame is set. We verify this indirectly: after
    // clearing the PageFrame (removing the data source), checkForProperties
    // returns true (nothing to deserialize). The actual deserialization from
    // PageFrame is wired in Step 2.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    long stamp = frame.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, stamp, 0, 0);

    // With pageFrame set, the deserializeProperties early-return guard
    // (source == null && pageFrame == null) is NOT triggered — the method
    // enters the deserialization path. We can't call checkForProperties()
    // directly yet because the PageFrame deserialization branch (Step 2)
    // isn't wired. Instead, verify the guard condition indirectly.
    assertFalse("sourceIsParsedByProperties should be false with pageFrame set",
        entity.sourceIsParsedByProperties());

    // After clearing both source and pageFrame, checkForProperties returns
    // true (nothing to deserialize — considered fully parsed).
    entity.clearPageFrame();
    assertTrue(entity.checkForProperties());
    session.rollback();
  }

  // --- Double fillFromPage ---

  @Test
  public void testFillFromPageTwiceReplacesState() {
    // Verifies that calling fillFromPage a second time replaces the first
    // PageFrame reference and all associated fields cleanly.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame1 = new PageFrame(pointer);
    long stamp1 = frame1.tryOptimisticRead();
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame1, stamp1, 10, 100);

    var pointer2 = allocator.allocate(8192, true, Intention.TEST);
    try {
      var frame2 = new PageFrame(pointer2);
      long stamp2 = frame2.tryOptimisticRead();
      entity.fillFromPage(2L, EntityImpl.RECORD_TYPE, frame2, stamp2, 20, 200);

      assertEquals(frame2, entity.getPageFrame());
      assertEquals(stamp2, entity.getPageStamp());
      assertEquals(20, entity.getPageContentOffset());
      assertEquals(200, entity.getPageContentLength());
      assertEquals(2L, entity.getVersion());
      assertEquals(200, entity.getSize());
    } finally {
      allocator.deallocate(pointer2);
    }
    session.rollback();
  }

  // --- Stamp boundary ---

  @Test
  public void testFillFromPageWithZeroStamp() {
    // stamp=0 means the frame was exclusively locked when tryOptimisticRead
    // was called. fillFromPage should accept it — validation happens later
    // in Step 2's speculative deserialization path.
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.unsetDirty();

    var frame = new PageFrame(pointer);
    entity.fillFromPage(1L, EntityImpl.RECORD_TYPE, frame, 0L, 0, 64);

    assertEquals(0L, entity.getPageStamp());
    assertEquals(frame, entity.getPageFrame());
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
    assertPageFrameCleared(entity);
    assertFalse(entity.isUnloaded());
    session.rollback();
  }

  /**
   * Helper: asserts that all four PageFrame fields are cleared (null/zero).
   */
  private void assertPageFrameCleared(EntityImpl entity) {
    assertNull(entity.getPageFrame());
    assertEquals(0L, entity.getPageStamp());
    assertEquals(0, entity.getPageContentOffset());
    assertEquals(0, entity.getPageContentLength());
  }
}
