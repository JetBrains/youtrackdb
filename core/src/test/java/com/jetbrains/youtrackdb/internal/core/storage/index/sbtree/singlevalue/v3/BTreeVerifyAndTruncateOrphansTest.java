package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

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
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
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
import org.mockito.invocation.InvocationOnMock;

/**
 * Unit tests for {@link BTree#verifyAndTruncateOrphans(AtomicOperation, ReadCache, WriteCache)}.
 *
 * <p>The helper reads the BTree's entry-point page (page 0) to obtain the persisted
 * {@code pagesSize} counter, computes the expected physical file size as
 * {@code max(pageSize, (pagesSize + 1) * pageSize)}, and dispatches to
 * {@link ReadCache#shrinkFile(long, long, WriteCache)} so the cache layer drops any
 * physical pages beyond the logical horizon. Tests below pin the four behavioural
 * scenarios the helper must satisfy:
 *
 * <ul>
 *   <li>Orphan-present — physical &gt; (pagesSize + 1) → shrink invoked at the
 *       logical-horizon target.</li>
 *   <li>Clean shape — physical == (pagesSize + 1) → shrink invoked but pre-flight
 *       no-ops at the cache layer (the dispatch shape is uniform).</li>
 *   <li>Boundary case — physical == target exactly → no off-by-one in the
 *       {@code (pagesSize + 1) * pageSize} arithmetic.</li>
 *   <li>Corruption signal — {@code pagesSize == 0 && physical &gt; 1 page} → shrink
 *       skipped (the EP's {@code init()} sets pagesSize = 1, so a 0 reading is
 *       structurally anomalous and WAL replay is supposed to rule it out).</li>
 * </ul>
 *
 * <p>Source anchors: {@code BTree.create()} at lines 170-226 (init sets
 * {@code pagesSize = 1}); {@code BTree.addPage()} at 2185-2188 (extend pattern:
 * {@code newIdx = getPagesSize() + 1; allocate; setPagesSize(newIdx)}).
 */
public class BTreeVerifyAndTruncateOrphansTest {

  private static final long FILE_ID = 1L;
  private static final long NULL_BUCKET_FILE_ID = 2L;
  private static final String TREE_NAME = "testTree";
  // Aligned with the production default (DISK_CACHE_PAGE_SIZE=8 KB).
  private static final int PAGE_SIZE_BYTES = 8 * 1024;

  private ByteBufferPool bufferPool;
  private ReadCache mockReadCache;
  private WriteCache mockWriteCache;
  private AbstractStorage mockStorage;
  private AtomicOperation atomicOperation;

  // pageCount tracks filledUpTo for the primary BTree file; pageMap stores backing
  // direct-memory buffers per (fileId, pageIndex) so reads/writes see the same buffer.
  private int pageCount;
  private final Map<Long, Map<Integer, CachePointer>> pagePointers = new HashMap<>();

  private BTree<String> tree;

  @Before
  public void setUp() throws IOException {
    bufferPool = ByteBufferPool.instance(null);
    mockReadCache = mock(ReadCache.class);
    mockWriteCache = mock(WriteCache.class);
    when(mockWriteCache.pageSize()).thenReturn(PAGE_SIZE_BYTES);
    mockStorage = mock(AbstractStorage.class);

    var mockAtomicOperationsManager = mock(AtomicOperationsManager.class);
    // BTree.create() routes through executeInsideComponentOperation; the production
    // body acquires component locks and then invokes the consumer. The mock manager
    // doesn't auto-invoke — install an Answer that runs the consumer directly so
    // create()'s lambda actually executes (and the BTree.fileId field gets assigned).
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

    // create() is plumbed through the mocked AtomicOperation: addFile returns the
    // FILE_ID stub, allocate-for-write returns real direct-memory-backed CacheEntries.
    // After create() the EP page (pageIndex 0) carries pagesSize = 1 and the root
    // bucket sits at pageIndex 1, so the primary file has pageCount == 2.
    tree = new BTree<>(TREE_NAME, ".sbt", ".nbt", mockStorage);
    tree.create(atomicOperation, UTF8Serializer.INSTANCE, null, 1);
  }

  @After
  public void tearDown() {
    for (var perFile : pagePointers.values()) {
      for (var cp : perFile.values()) {
        cp.decrementReferrer();
      }
    }
    pagePointers.clear();
  }

  // ---------------------------------------------------------------------------
  // Orphan-present branch
  // ---------------------------------------------------------------------------

  // After create() pagesSize == 1 (root + EP), physical == 2. Bump pagesSize to 4
  // via a real setPagesSize on the EP page so the helper sees pagesSize=4, then
  // simulate two orphan pages tail-extending the file past the logical horizon.
  // Expected target = (4 + 1) * pageSize.
  @Test
  public void verifyAndTruncateOrphansShrinksWhenPhysicalExceedsLogical() throws IOException {
    // Pre-condition: tree.create() in setUp() should have assigned the BTree's
    // fileId to FILE_ID via the addFile stub. If this assert fires, the mock setup
    // is broken (the create() flow either didn't call op.addFile(name, false) or
    // the stub returned the wrong value).
    assertThat(tree.getFileId()).isEqualTo(FILE_ID);

    setPagesSizeOnEntryPoint(4);
    // physical == pagesSize + 1 + 2 orphans == 7 pages.
    makePage(FILE_ID, 5);
    makePage(FILE_ID, 6);
    pageCount = 7;

    tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 5L * PAGE_SIZE_BYTES; // (pagesSize=4 + 1) * pageSize
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // ---------------------------------------------------------------------------
  // Clean branch
  // ---------------------------------------------------------------------------

  // After create(), pagesSize=1, physical=2. The helper still dispatches shrinkFile
  // (the cache layer's pre-flight makes the actual shrink a no-op against the equal
  // physical/target — the helper does not skip the call).
  @Test
  public void verifyAndTruncateOrphansNoOpOnCleanShape() throws IOException {
    // No fabrication needed — setUp() left the tree in the clean post-create shape.
    assertThat(pageCount).isEqualTo(2);

    tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 2L * PAGE_SIZE_BYTES; // (pagesSize=1 + 1) * pageSize
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // ---------------------------------------------------------------------------
  // Boundary-exact-target branch
  // ---------------------------------------------------------------------------

  // Pin off-by-one in the (pagesSize + 1) * pageSize arithmetic: bump pagesSize to a
  // non-trivial value, set physical == (pagesSize + 1), and assert the helper
  // dispatches shrinkFile at exactly that target. A bug computing
  // pagesSize * pageSize (without the +1) would produce a smaller target.
  @Test
  public void verifyAndTruncateOrphansBoundaryExactTarget() throws IOException {
    setPagesSizeOnEntryPoint(5);
    makePage(FILE_ID, 2);
    makePage(FILE_ID, 3);
    makePage(FILE_ID, 4);
    makePage(FILE_ID, 5);
    pageCount = 6;

    tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 6L * PAGE_SIZE_BYTES; // (pagesSize=5 + 1) * pageSize
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // ---------------------------------------------------------------------------
  // Corruption-skip-with-WARN branch
  // ---------------------------------------------------------------------------

  // Force the structurally anomalous shape: pagesSize=0 (which v3 BTree.create()
  // structurally rules out) and physical > 1 page. The helper must skip the
  // dispatch — masking the inconsistency would re-introduce the partial-flush-orphan
  // path the pass exists to fix. Beyond the skip, the helper MUST emit a WARN log so
  // operators see the corruption signal; a regression that demotes the log to DEBUG,
  // silently drops it, or formats it with the wrong substring would hide the signal.
  //
  // This BTree suite carries the WARN-log capture sentinel for the four sibling
  // corruption-skip tests (BTree / SLBB / CPMV2 / PCV2). The corruption-skip code shape
  // is identical across all four components — pre-format String.format(...) on a
  // "Storage corruption signal:" message, then LogManager.instance().warn(this, msg) —
  // so pinning the WARN emission in one suite catches a regression that nukes the
  // logger entirely without paying for four near-identical capture rigs. See the
  // SLBB/CPMV2/PCV2 sibling tests for cross-references.
  //
  // Capture mechanism: the project's test runtime SLF4J binding is slf4j-jdk14, so the
  // SLF4JLogManager.log(...) call routes through java.util.logging. We install a JUL
  // Handler on the BTree class's logger (the name LogManager uses when 'this' is a
  // BTree instance), capture the LogRecord, and assert level == WARNING + message
  // contains the "Storage corruption signal" substring + the BTree component anchor.
  @Test
  public void verifyAndTruncateOrphansSkipsOnCorruptionSignal() throws IOException {
    setPagesSizeOnEntryPoint(0);
    // physical = 3 pages (EP + 2 phantoms), all > 1 page so the second clause of the
    // corruption guard is satisfied.
    makePage(FILE_ID, 2);
    pageCount = 3;

    // Attach a JUL handler scoped to the BTree class's logger. The SLF4JLogManager
    // routes through the logger named after the requester's class
    // (BTree.class.getName()), so this handler observes warn(this, msg) emissions from
    // the BTree under test without picking up unrelated logs from other components.
    final var btreeLogger = java.util.logging.Logger.getLogger(BTree.class.getName());
    final var capturedRecords = new java.util.concurrent.CopyOnWriteArrayList<
        java.util.logging.LogRecord>();
    final var handler = new java.util.logging.Handler() {
      @Override
      public void publish(final java.util.logging.LogRecord record) {
        capturedRecords.add(record);
      }

      @Override
      public void flush() {
        // No-op — the test's assertions read directly from capturedRecords.
      }

      @Override
      public void close() {
        // No-op — the JUL framework calls close() on shutdown; nothing to release here.
      }
    };
    handler.setLevel(java.util.logging.Level.ALL);
    // Force the logger to actually deliver WARNING-level records to handlers even if a
    // parent logger or LogManager configuration silenced them in the test environment.
    final var priorLevel = btreeLogger.getLevel();
    btreeLogger.setLevel(java.util.logging.Level.ALL);
    btreeLogger.addHandler(handler);
    try {
      tree.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);
    } finally {
      btreeLogger.removeHandler(handler);
      btreeLogger.setLevel(priorLevel);
    }

    verify(mockReadCache, never()).shrinkFile(anyLong(), anyLong(), eq(mockWriteCache));

    // Pin the WARN emission. The production helper builds the message via
    // String.format("Storage corruption signal: BTree '%s' EP reports pagesSize=0 but"
    //   + " physical file holds %d pages (> 1). ...", getName(), physicalPages);
    // so the assertions below match the format anchor and the component-specific
    // substring (BTree) without locking in the exact phrasing. A regression that
    // nukes the logger entirely (no record captured) or demotes the level (record
    // captured but not WARNING) fails the assertion.
    assertThat(capturedRecords)
        .as("the BTree corruption-skip branch must emit at least one WARN log so the"
            + " corruption signal is visible to operators")
        .isNotEmpty();
    final var warnRecord = capturedRecords.stream()
        .filter(r -> r.getLevel() == java.util.logging.Level.WARNING)
        .filter(r -> {
          final var msg = r.getMessage();
          return msg != null
              && msg.contains("Storage corruption signal")
              && msg.contains("BTree");
        })
        .findFirst()
        .orElse(null);
    assertThat(warnRecord)
        .as("a WARNING record matching the BTree corruption-signal format must be"
            + " present in the captured handler events (capturedRecords=%s)",
            capturedRecords)
        .isNotNull();
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private void setPagesSizeOnEntryPoint(int pagesSize) throws IOException {
    try (var entry = atomicOperation.loadPageForWrite(FILE_ID, 0, 1, false)) {
      new CellBTreeSingleValueEntryPointV3<String>(entry).setPagesSize(pagesSize);
    }
  }

  /**
   * Pre-creates a page at the given (fileId, pageIndex) in the simulated file's page
   * map without incrementing pageCount. Used to simulate pre-existing pages for
   * orphan-page scenarios.
   */
  private void makePage(long fileId, int pageIndex) {
    pagePointers
        .computeIfAbsent(fileId, fid -> new HashMap<>())
        .computeIfAbsent(pageIndex, idx -> {
          var pointer = bufferPool.acquireDirect(true, Intention.TEST);
          var cp = new CachePointer(pointer, bufferPool, fileId, idx);
          cp.incrementReferrer();
          return cp;
        });
  }

  /**
   * Creates a mock {@link AtomicOperation} that simulates two files (the primary BTree
   * file at FILE_ID and the null-bucket file at NULL_BUCKET_FILE_ID), each backed by
   * a per-file page-pointer map. The primary file's filledUpTo is tracked via
   * {@code pageCount}; the null-bucket file does not participate in the helper under
   * test (the helper operates only on the primary file).
   */
  private AtomicOperation createAtomicOperation() throws IOException {
    var op = mock(AtomicOperation.class);

    // The mocked AtomicOperation interface stubs every method (default methods
    // included) to return Mockito-default values. The 2-arg overload is the one
    // BTree.create() actually calls through StorageComponent.addFile; the 1-arg
    // stub is defensive in case a future refactor routes through it.
    when(op.addFile(anyString())).thenAnswer((InvocationOnMock inv) -> {
      String fname = inv.getArgument(0);
      return fname.endsWith(".nbt") ? NULL_BUCKET_FILE_ID : FILE_ID;
    });
    when(op.addFile(anyString(), anyBoolean())).thenAnswer((InvocationOnMock inv) -> {
      String fname = inv.getArgument(0);
      return fname.endsWith(".nbt") ? NULL_BUCKET_FILE_ID : FILE_ID;
    });

    when(op.filledUpTo(FILE_ID)).thenAnswer(inv -> (long) pageCount);
    when(op.filledUpTo(NULL_BUCKET_FILE_ID)).thenReturn(1L);

    when(op.allocatePageForWrite(anyLong(), anyLong())).thenAnswer(inv -> {
      long fid = inv.getArgument(0);
      int pIdx = ((Long) inv.getArgument(1)).intValue();
      var entry = getOrCreatePage(fid, pIdx);
      if (fid == FILE_ID && pIdx >= pageCount) {
        pageCount = pIdx + 1;
      }
      return entry;
    });

    when(op.loadPageForWrite(anyLong(), anyLong(), anyInt(), anyBoolean()))
        .thenAnswer(inv -> {
          long fid = inv.getArgument(0);
          int pIdx = ((Long) inv.getArgument(1)).intValue();
          return getOrCreatePage(fid, pIdx);
        });

    when(op.loadPageForRead(anyLong(), anyLong()))
        .thenAnswer(inv -> {
          long fid = inv.getArgument(0);
          int pIdx = ((Long) inv.getArgument(1)).intValue();
          return getOrCreatePage(fid, pIdx);
        });

    return op;
  }

  private CacheEntry getOrCreatePage(long fileId, int pageIndex) {
    var perFile = pagePointers.computeIfAbsent(fileId, fid -> new HashMap<>());
    var cachePointer = perFile.computeIfAbsent(pageIndex, idx -> {
      var pointer = bufferPool.acquireDirect(true, Intention.TEST);
      var cp = new CachePointer(pointer, bufferPool, fileId, idx);
      cp.incrementReferrer();
      return cp;
    });
    return new CacheEntryImpl(fileId, pageIndex, cachePointer, false, mockReadCache);
  }
}
