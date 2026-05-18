package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for
 * {@link SharedLinkBagBTree#verifyAndTruncateOrphans(AtomicOperation, ReadCache, WriteCache)}.
 *
 * <p>The helper reads the SLBB EntryPoint's {@code pagesSize} counter, computes the
 * expected physical file size as {@code max(pageSize, (pagesSize + 1) * pageSize)}, and
 * dispatches to {@link ReadCache#shrinkFile(long, long, WriteCache)}. The SLBB EP's
 * {@code init()} sets pagesSize = 1 (root + EP), so a healthy SLBB carries
 * {@code pagesSize >= 1} — making {@code pagesSize == 0} an anomalous corruption signal
 * (paired with {@code physical > 1 page}) that the helper logs and skips. Source
 * anchors: {@code SharedLinkBagBTree.create()} at 51-77; {@code addPage} pattern at
 * 935-937.
 */
public class SharedLinkBagBTreeVerifyAndTruncateOrphansTest {

  private static final long FILE_ID = 1L;
  private static final String TREE_NAME = "testRidBag";
  private static final String EXTENSION = ".srb";
  private static final int PAGE_SIZE_BYTES = 8 * 1024;

  private ByteBufferPool bufferPool;
  private ReadCache mockReadCache;
  private WriteCache mockWriteCache;
  private AbstractStorage mockStorage;
  private AtomicOperation atomicOperation;

  private int pageCount;
  private final Map<Integer, CachePointer> pagePointers = new HashMap<>();

  private SharedLinkBagBTree tree;

  @Before
  public void setUp() throws IOException {
    bufferPool = ByteBufferPool.instance(null);
    mockReadCache = mock(ReadCache.class);
    mockWriteCache = mock(WriteCache.class);
    when(mockWriteCache.pageSize()).thenReturn(PAGE_SIZE_BYTES);
    mockStorage = mock(AbstractStorage.class);

    var mockAtomicOperationsManager = mock(AtomicOperationsManager.class);
    // SharedLinkBagBTree.create() routes through executeInsideComponentOperation;
    // the mocked manager doesn't auto-invoke the consumer, so install an Answer that
    // runs it directly. Without this the create() body never executes and fileId
    // stays at its default 0 — the verifyAndTruncateOrphans helper would then read
    // an empty page at fileId=0 and the assertions would fail with mysterious 0L
    // mismatches.
    org.mockito.Mockito.doAnswer(inv -> {
      AtomicOperation op = inv.getArgument(0);
      TxConsumer consumer = inv.getArgument(2);
      consumer.accept(op);
      return null;
    }).when(mockAtomicOperationsManager).executeInsideComponentOperation(
        org.mockito.ArgumentMatchers.any(AtomicOperation.class),
        org.mockito.ArgumentMatchers.any(StorageComponent.class),
        org.mockito.ArgumentMatchers.any(TxConsumer.class));
    when(mockStorage.getReadCache()).thenReturn(mockReadCache);
    when(mockStorage.getWriteCache()).thenReturn(mockWriteCache);
    when(mockStorage.getAtomicOperationsManager()).thenReturn(mockAtomicOperationsManager);
    when(mockStorage.getName()).thenReturn("test-storage");
    var componentsFactory =
        new CurrentStorageComponentsFactory(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    when(mockStorage.getComponentsFactory()).thenReturn(componentsFactory);

    pageCount = 0;
    pagePointers.clear();

    atomicOperation = createAtomicOperation();
    tree = new SharedLinkBagBTree(mockStorage, TREE_NAME, EXTENSION);
    tree.create(atomicOperation);
  }

  @After
  public void tearDown() {
    for (var cp : pagePointers.values()) {
      cp.decrementReferrer();
    }
    pagePointers.clear();
  }

  // Sanity-check anchor: create() left pagesSize at 1 (root + EP) and physical at 2
  // pages. The fileId should be the FILE_ID stub. If this assert fires, the mock
  // setup is broken and downstream assertions will give misleading messages.
  @Test
  public void createLeavesTreeInExpectedShape() {
    assertThat(tree.getFileId()).isEqualTo(FILE_ID);
    assertThat(pageCount).isEqualTo(2);
  }

  // Orphan-present: physical > (pagesSize + 1). The helper must dispatch shrinkFile
  // at target = (pagesSize + 1) * pageSize.
  @Test
  public void verifyAndTruncateOrphansShrinksWhenPhysicalExceedsLogical() throws IOException {
    setPagesSizeOnEntryPoint(3);
    makePage(4);
    makePage(5);
    pageCount = 6;

    tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 4L * PAGE_SIZE_BYTES;
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // Clean-shape branch: physical == (pagesSize + 1). The helper still dispatches
  // shrinkFile at target=(pagesSize+1)*pageSize so the dispatch shape is uniform
  // — the cache layer's pre-flight handles the no-op semantics.
  @Test
  public void verifyAndTruncateOrphansNoOpOnCleanShape() throws IOException {
    // create() left pagesSize == 1, pageCount == 2.
    assertThat(pageCount).isEqualTo(2);

    tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 2L * PAGE_SIZE_BYTES; // (1 + 1) * pageSize
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // Boundary case: catches off-by-one in (pagesSize + 1) * pageSize. If the helper
  // computed pagesSize * pageSize by mistake, the target would be one page short.
  @Test
  public void verifyAndTruncateOrphansBoundaryExactTarget() throws IOException {
    setPagesSizeOnEntryPoint(5);
    makePage(2);
    makePage(3);
    makePage(4);
    makePage(5);
    pageCount = 6;

    tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 6L * PAGE_SIZE_BYTES; // (5 + 1) * pageSize
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // Corruption-signal branch: pagesSize == 0 AND physical > 1 page. The SLBB EP's
  // init() sets pagesSize = 1, so seeing 0 is structurally anomalous and indicates
  // a corruption shape WAL replay should have ruled out. The helper logs WARN and
  // skips the truncate.
  @Test
  public void verifyAndTruncateOrphansSkipsOnCorruptionSignal() throws IOException {
    setPagesSizeOnEntryPoint(0);
    makePage(2);
    pageCount = 3;

    tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    verify(mockReadCache, never()).shrinkFile(anyLong(), anyLong(), eq(mockWriteCache));
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private void setPagesSizeOnEntryPoint(int pagesSize) throws IOException {
    try (var entry = atomicOperation.loadPageForWrite(FILE_ID, 0, 1, false)) {
      new EntryPoint(entry).setPagesSize(pagesSize);
    }
  }

  private void makePage(int pageIndex) {
    pagePointers.computeIfAbsent(pageIndex, idx -> {
      var pointer = bufferPool.acquireDirect(true, Intention.TEST);
      var cp = new CachePointer(pointer, bufferPool, FILE_ID, idx);
      cp.incrementReferrer();
      return cp;
    });
  }

  private AtomicOperation createAtomicOperation() throws IOException {
    var op = mock(AtomicOperation.class);

    when(op.addFile(anyString())).thenReturn(FILE_ID);
    when(op.addFile(anyString(), anyBoolean())).thenReturn(FILE_ID);
    when(op.filledUpTo(FILE_ID)).thenAnswer(inv -> (long) pageCount);

    when(op.allocatePageForWrite(eq(FILE_ID), anyLong())).thenAnswer(inv -> {
      int pIdx = ((Long) inv.getArgument(1)).intValue();
      var entry = getOrCreatePage(pIdx);
      if (pIdx >= pageCount) {
        pageCount = pIdx + 1;
      }
      return entry;
    });

    when(op.loadPageForWrite(eq(FILE_ID), anyLong(), anyInt(), anyBoolean()))
        .thenAnswer(inv -> {
          int pIdx = ((Long) inv.getArgument(1)).intValue();
          return getOrCreatePage(pIdx);
        });

    when(op.loadPageForRead(eq(FILE_ID), anyLong())).thenAnswer(inv -> {
      int pIdx = ((Long) inv.getArgument(1)).intValue();
      return getOrCreatePage(pIdx);
    });

    return op;
  }

  private CacheEntry getOrCreatePage(int pageIndex) {
    var cachePointer = pagePointers.computeIfAbsent(pageIndex, idx -> {
      var pointer = bufferPool.acquireDirect(true, Intention.TEST);
      var cp = new CachePointer(pointer, bufferPool, FILE_ID, idx);
      cp.incrementReferrer();
      return cp;
    });
    return new CacheEntryImpl(FILE_ID, pageIndex, cachePointer, false, mockReadCache);
  }
}
