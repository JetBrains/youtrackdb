package com.jetbrains.youtrackdb.internal.core.storage.index.versionmap;

import com.jetbrains.youtrackdb.internal.core.storage.cache.PageEntryFixture;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link PaginatedVersionStateV0} and {@link MapEntryPoint} page-level
 * accessors. Each test allocates a real direct-memory page via {@link PageEntryFixture},
 * constructs the page wrapper, sets a value, and reads it back — exercising the
 * {@code setIntValue}/{@code getIntValue} paths inherited from
 * {@link com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage}.
 * {@code MapEntryPoint} is package-private so it is tested here alongside
 * {@code PaginatedVersionStateV0} rather than in a separate file.
 */
public class PaginatedVersionStateV0Test {

  private PageEntryFixture pages;

  @Before
  public void setUp() {
    pages = new PageEntryFixture();
  }

  @After
  public void tearDown() {
    pages.close();
  }

  /**
   * The size field round-trips: a value written with {@link PaginatedVersionStateV0#setSize}
   * is read back identically by {@link PaginatedVersionStateV0#getSize}.
   */
  @Test
  public void sizeRoundTrip() {
    var entry = pages.acquireExclusive(0L, 0);
    var page = new PaginatedVersionStateV0(entry);

    page.setSize(77);
    Assert.assertEquals(77, page.getSize());
  }

  /**
   * The recordsSize field round-trips correctly.
   */
  @Test
  public void recordsSizeRoundTrip() {
    var entry = pages.acquireExclusive(0L, 0);
    var page = new PaginatedVersionStateV0(entry);

    page.setRecordsSize(1024);
    Assert.assertEquals(1024, page.getRecordsSize());
  }

  /**
   * The fileSize field round-trips correctly.
   */
  @Test
  public void fileSizeRoundTrip() {
    var entry = pages.acquireExclusive(0L, 0);
    var page = new PaginatedVersionStateV0(entry);

    page.setFileSize(512);
    Assert.assertEquals(512, page.getFileSize());
  }

  /**
   * The freeListPage field round-trips for a given index.
   */
  @Test
  public void freeListPageRoundTrip() {
    var entry = pages.acquireExclusive(0L, 0);
    var page = new PaginatedVersionStateV0(entry);

    page.setFreeListPage(0, 99);
    Assert.assertEquals(99, page.getFreeListPage(0));

    page.setFreeListPage(1, 200);
    Assert.assertEquals(200, page.getFreeListPage(1));
  }

  /**
   * Multiple fields coexist on the same page without corrupting each other —
   * the fixed-offset layout in {@link PaginatedVersionStateV0} is correct.
   */
  @Test
  public void multipleFieldsDoNotOverwrite() {
    var entry = pages.acquireExclusive(0L, 0);
    var page = new PaginatedVersionStateV0(entry);

    page.setSize(10);
    page.setRecordsSize(20);
    page.setFileSize(30);
    page.setFreeListPage(0, 40);

    Assert.assertEquals(10, page.getSize());
    Assert.assertEquals(20, page.getRecordsSize());
    Assert.assertEquals(30, page.getFileSize());
    Assert.assertEquals(40, page.getFreeListPage(0));
  }

  // --- MapEntryPoint (package-private; tested here to share the PageEntryFixture setup) ---

  /**
   * The fileSize field of {@link MapEntryPoint} round-trips: a value written with
   * {@link MapEntryPoint#setFileSize} is read back identically by
   * {@link MapEntryPoint#getFileSize}.
   */
  @Test
  public void mapEntryPointFileSizeRoundTrip() {
    var entry = pages.acquireExclusive(1L, 0);
    var mapEntryPoint = new MapEntryPoint(entry);

    mapEntryPoint.setFileSize(128);
    Assert.assertEquals(128, mapEntryPoint.getFileSize());
  }

  /**
   * {@link MapEntryPoint#setFileSize} with zero is readable back as zero.
   */
  @Test
  public void mapEntryPointFileSizeZero() {
    var entry = pages.acquireExclusive(2L, 0);
    var mapEntryPoint = new MapEntryPoint(entry);

    mapEntryPoint.setFileSize(0);
    Assert.assertEquals(0, mapEntryPoint.getFileSize());
  }
}
