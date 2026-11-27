package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.resource.SharedResourceAbstract;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.IndexesSnapshot;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.IndexMultiValuKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BTreeHistogramReadTest {

  @Rule
  public final TemporaryFolder directory = new TemporaryFolder();

  private static final String DATABASE = "histogramRetirement";
  private YouTrackDB database;
  private BTree<CompositeKey> tree;
  private AtomicOperationsManager operations;

  @Before
  public void createTree() throws Exception {
    database = YourTracks.instance(directory.getRoot().getAbsolutePath());
    database.create(DATABASE, DatabaseType.DISK, "admin", "admin", "admin");
    try (var session = ((YouTrackDBImpl) database).open(DATABASE, "admin", "admin")) {
      var storage = session.getStorage();
      tree = new BTree<>("histogramReadSeam", ".sbt", ".nbt", storage);
      operations = storage.getAtomicOperationsManager();
      operations.executeInsideAtomicOperation(op -> tree.create(op,
          new IndexMultiValuKeySerializer(),
          new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER, PropertyTypeInternal.LONG},
          2));
    }
  }

  @After
  public void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @Test
  public void guardRefusesEmptyFirstProbeVisibleLookupAndFirstFetchBeforeOperationAccess()
      throws Exception {
    var op = mock(AtomicOperation.class);
    var refusal = new IllegalStateException("test read refusal");
    Runnable guard = () -> {
      assertThat(tree.isSharedOwner()).isTrue();
      throw refusal;
    };
    var nullKey = CompositeKey.asCompositeKey(null);
    assertThatThrownBy(() -> tree.firstKeyForHistogram(op, guard)).isSameAs(refusal);
    assertThat(tree.isSharedOwner()).isFalse();
    assertThatThrownBy(() -> tree.getVisibleForHistogram(nullKey, snapshot(), op, guard))
        .isSameAs(refusal);
    assertThat(tree.isSharedOwner()).isFalse();
    try (var stream = tree.iterateEntriesMajorForHistogram(new CompositeKey(0, 0L), true, op,
        guard)) {
      var cursor = stream.spliterator();
      assertThatThrownBy(() -> cursor.tryAdvance(row -> {
        throw new AssertionError("The first fetch returned a row");
      })).isSameAs(refusal);
    }
    assertThat(tree.isSharedOwner()).isFalse();
    verifyNoInteractions(op);
  }

  @Test
  public void guardAllowsPinnedFirstKeyAndVisibleNullLookupAndRunsOnAnEmptyTree()
      throws Exception {
    var calls = new AtomicInteger();
    Runnable guard = () -> {
      assertThat(tree.isSharedOwner()).isTrue();
      calls.incrementAndGet();
    };
    operations.executeInsideAtomicOperation(op -> {
      assertThat(tree.firstKeyForHistogram(op, guard)).isNull();
      assertThat(calls.get()).isEqualTo(1);
    });
    var versionedNull = CompositeKey.asCompositeKey(null);
    versionedNull.addKey(0L);
    var rid = new RecordId(10, 1);
    operations.executeInsideAtomicOperation(op -> tree.put(op, versionedNull, rid));
    operations.executeInsideAtomicOperation(op -> {
      assertThat(tree.firstKeyForHistogram(op, guard)).isEqualTo(versionedNull);
      assertThat(tree.getVisibleForHistogram(CompositeKey.asCompositeKey(null), snapshot(), op,
          guard)).isEqualTo(rid);
      assertThat(calls.get()).isEqualTo(3);
      assertThat(tree.isSharedOwner()).isFalse();
    });
  }

  @Test
  public void emptyRootFirstKeyReturnsNullWithoutReleasingTheGuardHoldEarly() throws Exception {
    operations.executeInsideAtomicOperation(op -> {
      var trace = new GuardedReadTrace(op, 1);
      var result = trace.readTree.firstKeyForHistogram(trace.reader, trace::check);
      trace.assertContinuousHold(1);
      assertThat(trace.pages()).as("one load of the empty root bucket").containsExactly(1L);
      assertThat(result).as("first key of an empty tree").isNull();
    });
  }

  @Test
  public void guardRunsAgainUnderTheLockAtRefillAndDoesNotGuardBufferedEntries() throws Exception {
    var prefetch = GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
    operations.executeInsideAtomicOperation(op -> {
      for (int key = 0; key <= prefetch; key++) {
        tree.put(op, new CompositeKey(key, 0L), new RecordId(10, key));
      }
    });
    var refusal = new IllegalStateException("test refill refusal");
    var calls = new AtomicInteger();
    operations.executeInsideAtomicOperation(op -> {
      try (var stream = tree.iterateEntriesMajorForHistogram(new CompositeKey(0, 0L), true, op,
          () -> {
            assertThat(tree.isSharedOwner()).isTrue();
            if (calls.incrementAndGet() > 1) {
              throw refusal;
            }
          })) {
        var cursor = stream.spliterator();
        assertThat(calls.get()).isZero();
        for (int key = 0; key < prefetch; key++) {
          var expectedKey = new CompositeKey(key, 0L);
          assertThat(cursor.tryAdvance(row -> {
            assertThat(tree.isSharedOwner()).isFalse();
            assertThat(row.first()).isEqualTo(expectedKey);
          })).isTrue();
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> cursor.tryAdvance(row -> {
          throw new AssertionError("The refused refill returned a row");
        })).isSameAs(refusal);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(tree.isSharedOwner()).isFalse();
      }
    });
  }

  @Test
  public void guardedCursorReturnsTheOneRowShortRefillBeforeEnding() throws Exception {
    var prefetch = GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
    assertThat(prefetch).isGreaterThan(1);
    insertNonNullEntries(0, prefetch);
    operations.executeInsideAtomicOperation(op -> {
      var checks = new AtomicInteger();
      try (var stream = tree.iterateEntriesMajorForHistogram(
          new CompositeKey(0, 0L), true, op, countingGuard(checks))) {
        var cursor = stream.spliterator();
        assertThat(checks.get()).isZero();
        for (int key = 0; key < prefetch; key++) {
          var expected = row(key);
          assertThat(cursor.tryAdvance(actual -> assertThat(actual).isEqualTo(expected)))
              .as("first batch contains row %s", key).isTrue();
          assertThat(checks.get()).as("one check for the first batch").isEqualTo(1);
        }
        assertThat(cursor.tryAdvance(actual -> assertThat(actual).isEqualTo(row(prefetch))))
            .as("short refill delivers its one remaining row").isTrue();
        assertThat(checks.get()).as("one more check for the short refill").isEqualTo(2);

        var extraRows = new ArrayList<RawPair<CompositeKey, RID>>();
        assertThat(cursor.tryAdvance(extraRows::add)).as("cursor ends after the short refill")
            .isFalse();
        assertThat(checks.get()).as("one more check for the final empty fetch").isEqualTo(3);
        assertThat(cursor.tryAdvance(extraRows::add)).isFalse();
        assertThat(extraRows).isEmpty();
        assertThat(checks.get()).as("no check after exhaustion").isEqualTo(3);
      }
    });
  }

  @Test
  public void guardedFirstKeyWrapsPageFailureAfterCheckAndReleasesLock() throws Exception {
    var cause = new IOException("first key page load");
    operations.executeInsideAtomicOperation(op -> {
      var queryReader = spy(op);
      doThrow(cause).when(queryReader).loadPageForRead(eq(tree.getFileId()), anyLong());
      var queryFailure = assertReadFailure(() -> tree.firstKey(queryReader), cause);
      assertThat(queryFailure)
          .hasMessageStartingWith(
              "Error during finding first key in sbtree [" + tree.getName() + "]");

      var checks = new AtomicInteger();
      var guardedReader = spy(op);
      failPageLoadAfterCheck(guardedReader, cause, checks, 1);
      var guardedFailure = assertReadFailure(
          () -> tree.firstKeyForHistogram(guardedReader, countingGuard(checks)), cause);
      assertThat(guardedFailure).hasMessage(queryFailure.getMessage());
    });
  }

  @Test
  public void guardedVisibleLookupWrapsPageFailureAfterCheckAndReleasesLock() throws Exception {
    var cause = new IOException("visible lookup page load");
    var nullKey = CompositeKey.asCompositeKey(null);
    var snapshot = snapshot();
    operations.executeInsideAtomicOperation(op -> {
      var queryReader = spy(op);
      // Force the query to use its pinned fallback, where the page load can throw.
      doReturn(true).when(queryReader).hasChangesForPage(eq(tree.getFileId()), anyLong());
      doThrow(cause).when(queryReader).loadPageForRead(eq(tree.getFileId()), anyLong());
      var queryFailure =
          assertReadFailure(() -> tree.getVisible(nullKey, snapshot, queryReader), cause);
      assertThat(queryFailure)
          .hasMessageStartingWith("Error during getVisible of sbtree with name " + tree.getName());

      var checks = new AtomicInteger();
      var guardedReader = spy(op);
      failPageLoadAfterCheck(guardedReader, cause, checks, 1);
      var guardedFailure = assertReadFailure(
          () -> tree.getVisibleForHistogram(nullKey, snapshot, guardedReader,
              countingGuard(checks)),
          cause);
      assertThat(guardedFailure).hasMessage(queryFailure.getMessage());
    });
  }

  @Test
  public void guardedAndQueryRefillsWrapPageFailureAndReleaseLock() throws Exception {
    var prefetch = GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
    operations.executeInsideAtomicOperation(op -> {
      for (int key = 0; key <= prefetch; key++) {
        tree.put(op, new CompositeKey(key, 0L), new RecordId(10, key));
      }
    });

    var cause = new IOException("forward refill page load");
    var start = new CompositeKey(0, 0L);
    operations.executeInsideAtomicOperation(op -> {
      var checks = new AtomicInteger();
      var queryReader = spy(op);
      var guardedReader = spy(op);
      try (var queryStream = tree.iterateEntriesMajor(start, true, true, queryReader);
          var guardedStream = tree.iterateEntriesMajorForHistogram(start, true, guardedReader,
              countingGuard(checks))) {
        var queryCursor = queryStream.spliterator();
        var guardedCursor = guardedStream.spliterator();
        // Drain both buffers before injecting the fault so the next advance is a refill.
        for (int key = 0; key < prefetch; key++) {
          var expectedKey = new CompositeKey(key, 0L);
          assertThat(queryCursor.tryAdvance(row -> assertThat(row.first()).isEqualTo(expectedKey)))
              .isTrue();
          assertThat(
              guardedCursor.tryAdvance(row -> assertThat(row.first()).isEqualTo(expectedKey)))
              .isTrue();
        }
        assertThat(checks.get()).isEqualTo(1);

        doThrow(cause).when(queryReader).loadPageForRead(eq(tree.getFileId()), anyLong());
        failPageLoadAfterCheck(guardedReader, cause, checks, 2);
        var queryFailure = assertReadFailure(() -> queryCursor.tryAdvance(row -> {
          throw new AssertionError("The failed query refill returned a row");
        }), cause);
        assertThat(queryFailure).hasMessageStartingWith("Error during entity iteration");
        var guardedFailure = assertReadFailure(() -> guardedCursor.tryAdvance(row -> {
          throw new AssertionError("The failed guarded refill returned a row");
        }), cause);
        assertThat(guardedFailure).hasMessage(queryFailure.getMessage());
      }
    });
  }

  @Test
  public void guardedVisibleLookupChecksBeforeReturningQueryResultForShortKey() {
    var shortKey = new CompositeKey();
    var snapshot = snapshot();
    var op = mock(AtomicOperation.class);
    var checks = new AtomicInteger();

    var queryResult = tree.getVisible(shortKey, snapshot, op);
    assertThat(queryResult).isNull();
    assertThat(tree.getVisibleForHistogram(shortKey, snapshot, op, countingGuard(checks)))
        .isEqualTo(queryResult);
    assertThat(checks.get()).isEqualTo(1);
    assertThat(tree.isSharedOwner()).isFalse();
    verifyNoInteractions(op);
  }

  @Test
  public void guardedVisibleScanReturnsNullWhenTheEmptyLeafChainEnds() throws Exception {
    var nullKey = CompositeKey.asCompositeKey(null);
    var snapshot = snapshot();
    operations.executeInsideAtomicOperation(op -> {
      var trace = new GuardedReadTrace(op, 1);
      var result = trace.readTree.getVisibleForHistogram(nullKey, snapshot, trace.reader,
          trace::check);
      trace.assertContinuousHold();
      // The empty root is the only leaf. Descent and scan each load it once.
      assertThat(trace.pages()).containsExactly(1L, 1L);
      assertThat(result).as("visible result after leaf chain exhaustion").isNull();
      assertThat(result).isEqualTo(tree.getVisible(nullKey, snapshot, op));
    });
  }

  @Test
  public void guardedNullMissDoesNotLoadAnUnrelatedLeaf() throws Exception {
    var entryCount = 1024;
    insertNonNullEntries(1, entryCount);
    operations.executeInsideAtomicOperation(op -> {
      // All fixture keys are positive. The null prefix sorts before every separator.
      var expectedPages = leftmostDescent(op);
      assertThat(expectedPages).hasSizeGreaterThan(1);
      expectedPages.add(expectedPages.getLast());

      var nullKey = CompositeKey.asCompositeKey(null);
      var snapshot = snapshot();
      var guardedPages = new ArrayList<Long>();
      var reader = trackPageLoads(op, guardedPages, new HashSet<>());
      var guardedResult = tree.getVisibleForHistogram(nullKey, snapshot, reader, () -> {
      });
      assertThat(guardedResult).isNull();
      assertThat(guardedResult).isEqualTo(tree.getVisible(nullKey, snapshot, op));
      assertThat(guardedPages).containsExactlyElementsOf(expectedPages);
    });
  }

  private List<Long> leftmostDescent(AtomicOperation op) throws IOException {
    var pages = new ArrayList<Long>();
    long page = 1; // Root bucket, not the tree entry point.
    while (true) {
      pages.add(page);
      try (var entry = op.loadPageForRead(tree.getFileId(), page)) {
        var bucket = new CellBTreeSingleValueBucketV3<CompositeKey>(entry);
        if (bucket.isLeaf()) {
          assertThat(bucket.size()).isPositive();
          return pages;
        }
        page = bucket.getLeft(0);
      }
    }
  }

  @Test
  public void guardedNullLookupContinuesToTheVisibleVersionOnAnotherLeaf() throws Exception {
    var visible = insertNullVersions();
    operations.executeInsideAtomicOperation(op -> {
      var leaves = new HashSet<Long>();
      var reader = trackPageLoads(op, new ArrayList<>(), leaves);
      var nullKey = CompositeKey.asCompositeKey(null);
      var snapshot = snapshot();
      var queryResult = tree.getVisible(nullKey, snapshot, op);
      assertThat(queryResult).isEqualTo(visible);
      assertThat(tree.getVisibleForHistogram(nullKey, snapshot, reader, () -> {
      })).isEqualTo(queryResult);
      assertThat(leaves).hasSizeGreaterThan(1);
    });
  }

  private RecordId insertNullVersions() throws Exception {
    var visible = new RecordId(10, 2);
    operations.executeInsideAtomicOperation(op -> {
      var tombstone = new TombstoneRID(new RecordId(10, 1));
      for (long version = -1024; version < 0; version++) {
        var key = CompositeKey.asCompositeKey(null);
        key.addKey(version);
        tree.put(op, key, tombstone);
      }
      var key = CompositeKey.asCompositeKey(null);
      key.addKey(0L);
      tree.put(op, key, visible);
    });
    return visible;
  }

  private AtomicOperation trackPageLoads(AtomicOperation op, List<Long> pages, Set<Long> leaves)
      throws IOException {
    var reader = spy(op);
    doAnswer(call -> {
      long pageIndex = call.getArgument(1);
      pages.add(pageIndex);
      var entry = op.loadPageForRead(tree.getFileId(), pageIndex);
      if (new CellBTreeSingleValueBucketV3<CompositeKey>(entry).isLeaf()) {
        leaves.add(pageIndex);
      }
      return entry;
    }).when(reader).loadPageForRead(eq(tree.getFileId()), anyLong());
    return reader;
  }

  @Test
  public void firstKeyKeepsTheGuardHoldThroughPageReads() throws Exception {
    assertSplitFirstKeyKeepsGuardHold(false);
  }

  @Test
  public void balancedNestedHoldDoesNotEndTheGuardHold() throws Exception {
    assertSplitFirstKeyKeepsGuardHold(true);
  }

  private void assertSplitFirstKeyKeepsGuardHold(boolean nested) throws Exception {
    insertNonNullEntries(1, 1024);
    operations.executeInsideAtomicOperation(op -> {
      var expectedPages = leftmostDescent(op);
      assertThat(expectedPages).hasSizeGreaterThan(1);
      expectedPages.add(expectedPages.getLast());
      var trace = new GuardedReadTrace(op, 1);
      var first = trace.readTree.firstKeyForHistogram(trace.reader, () -> {
        trace.check();
        if (nested) {
          invokeSharedLockMethod(trace.readTree, "acquireSharedLock");
          invokeSharedLockMethod(trace.readTree, "releaseSharedLock");
        }
      });
      trace.assertContinuousHold();
      assertThat(first).isEqualTo(new CompositeKey(1, 0L));
      assertThat(trace.pages()).containsExactlyElementsOf(expectedPages);
    });
  }

  @Test
  public void visibleLookupKeepsTheGuardHoldThroughPageReads() throws Exception {
    var visible = insertNullVersions();
    operations.executeInsideAtomicOperation(op -> {
      var descent = leftmostDescent(op);
      var expectedScan = forwardLeaves(op).stream().map(Leaf::page).toList();
      assertThat(expectedScan).hasSizeGreaterThan(1);
      var trace = new GuardedReadTrace(op, 1);
      var result = trace.readTree.getVisibleForHistogram(CompositeKey.asCompositeKey(null),
          snapshot(), trace.reader, trace::check);
      trace.assertContinuousHold();
      assertThat(result).isEqualTo(visible);

      var pages = trace.pages();
      assertThat(pages).startsWith(descent.toArray(Long[]::new));
      assertThat(pages.subList(descent.size(), pages.size()))
          .containsExactlyElementsOf(expectedScan);
    });
  }

  @Test
  public void firstForwardFetchKeepsTheGuardHoldThroughPageReads() throws Exception {
    assertForwardFetchKeepsGuardHold(1);
  }

  @Test
  public void forwardRefillKeepsTheGuardHoldThroughPageReads() throws Exception {
    assertForwardFetchKeepsGuardHold(2);
  }

  private void insertNonNullEntries(int first, int last) throws Exception {
    operations.executeInsideAtomicOperation(op -> {
      for (int key = first; key <= last; key++) {
        tree.put(op, new CompositeKey(key, 0L), new RecordId(10, key));
      }
    });
  }

  private void assertForwardFetchKeepsGuardHold(int guardedCheck) throws Exception {
    var prefetch = GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger();
    assertThat(prefetch).isGreaterThan(1);
    var entries = 1024 + 2 * prefetch;
    insertNonNullEntries(0, entries - 1);
    operations.executeInsideAtomicOperation(op -> {
      var leaves = forwardLeaves(op);
      // Start the selected batch on the last key of a leaf. For a refill, leave a
      // full buffer of earlier rows to drain before selecting the next check.
      var earlierRows = (guardedCheck - 1) * prefetch;
      var left = leaves.stream()
          .filter(leaf -> leaf.end() - leaf.start() >= 2)
          .filter(leaf -> leaf.end() > earlierRows && leaf.end() - 1 + prefetch <= entries)
          .findFirst().orElseThrow();
      var batchStart = left.end() - 1;
      var expectedCopyPages = leaves.stream()
          .filter(leaf -> leaf.end() > batchStart && leaf.start() < batchStart + prefetch)
          .map(Leaf::page).toList();
      assertThat(expectedCopyPages).hasSizeGreaterThan(1);
      var expectedBatch = IntStream.range(batchStart, batchStart + prefetch)
          .mapToObj(BTreeHistogramReadTest::row).toList();

      var trace = new GuardedReadTrace(op, guardedCheck);
      try (var stream = trace.readTree.iterateEntriesMajorForHistogram(
          new CompositeKey(batchStart - earlierRows, 0L), true, trace.reader, trace::check)) {
        var cursor = stream.spliterator();
        assertThat(cursor).isInstanceOf(SpliteratorForward.Guarded.class);
        assertCursorBatch(trace, (SpliteratorForward<?>) cursor, batchStart, earlierRows,
            expectedBatch);
      }
      var pages = trace.pages();
      if (guardedCheck == 1) {
        // The descent loads the first copy leaf once before the copy loop reloads it.
        assertThat(pages).hasSizeGreaterThan(expectedCopyPages.size());
        assertThat(pages.get(pages.size() - expectedCopyPages.size() - 1)).isEqualTo(left.page());
      } else {
        assertThat(pages).hasSameSizeAs(expectedCopyPages);
      }
      assertThat(pages).endsWith(expectedCopyPages.toArray(Long[]::new));
    });
  }

  private static void assertCursorBatch(GuardedReadTrace trace, SpliteratorForward<?> cursor,
      int batchStart, int earlierRows, List<RawPair<CompositeKey, RID>> expectedBatch) {
    trace.cursor = cursor;
    for (int key = batchStart - earlierRows; key < batchStart; key++) {
      var expected = row(key);
      assertThat(cursor.tryAdvance(actual -> assertThat(actual).isEqualTo(expected))).isTrue();
    }
    var actualBatch = new ArrayList<RawPair<?, RID>>();
    assertThat(cursor.tryAdvance(actualBatch::add)).isTrue();
    var terminalStep = trace.assertContinuousHold();
    assertThat(trace.buffersAtRelease.get(terminalStep))
        .as("cursor batch complete before terminal shared release")
        .isEqualTo(expectedBatch);
    for (int i = 1; i < expectedBatch.size(); i++) {
      assertThat(cursor.tryAdvance(actualBatch::add)).isTrue();
    }
    assertThat(trace.checks).isEqualTo(trace.selectedCheck);
    assertThat(actualBatch).containsExactlyElementsOf(expectedBatch);
  }

  private static RawPair<CompositeKey, RID> row(int key) {
    return new RawPair<>(new CompositeKey(key, 0L), new RecordId(10, key));
  }

  private record Leaf(long page, int start, int end) {
  }

  private List<Leaf> forwardLeaves(AtomicOperation op) throws IOException {
    var leaves = new ArrayList<Leaf>();
    long page = leftmostDescent(op).getLast();
    int start = 0;
    while (page >= 0) {
      try (var entry = op.loadPageForRead(tree.getFileId(), page)) {
        var bucket = new CellBTreeSingleValueBucketV3<CompositeKey>(entry);
        int end = start + bucket.size();
        leaves.add(new Leaf(page, start, end));
        start = end;
        page = bucket.getRightSibling();
      }
    }
    return leaves;
  }

  private static void invokeSharedLockMethod(BTree<?> receiver, String methodName) {
    try {
      // These inherited methods are protected. Invoke them on the real-method spy.
      var method = SharedResourceAbstract.class.getDeclaredMethod(methodName);
      method.setAccessible(true);
      method.invoke(receiver);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(methodName, e);
    }
  }

  private final class GuardedReadTrace {

    private final BTree<CompositeKey> readTree = spy(tree);
    private final AtomicOperation reader;
    private final int selectedCheck;
    private final List<ReadHandle> handles = new ArrayList<>();
    private final Map<Integer, List<?>> buffersAtRelease = new HashMap<>();
    private int checks;
    private int guardStep;
    private SpliteratorForward<?> cursor;

    private GuardedReadTrace(AtomicOperation op, int selectedCheck) throws IOException {
      this.selectedCheck = selectedCheck;
      assertThat(readTree.isSharedOwner()).isFalse();
      assertThat(readTree.isExclusiveOwner()).isFalse();
      var releaseStub = doAnswer(call -> {
        if (checks == selectedCheck && cursor != null) {
          // Mockito has recorded the release invocation, but the real lock is still held.
          buffersAtRelease.put(sharedLockSteps(readTree).length,
              List.copyOf(cursor.getDataCache()));
        }
        return call.callRealMethod();
      }).when(readTree);
      invokeSharedLockMethod(releaseStub, "releaseSharedLock");

      reader = spy(op);
      doAnswer(call -> {
        long page = call.getArgument(1);
        var entry = op.loadPageForRead(tree.getFileId(), page);
        if (checks != selectedCheck) {
          return entry;
        }
        var handle = new ReadHandle(page);
        handles.add(handle);
        // A distinct witness for every load, including repeated loads of the same page.
        // The real cache must receive the original entry, not a shallow spy copy.
        var witness = mock(CacheEntry.class, delegatesTo(entry));
        doAnswer(close -> {
          if (++handle.closes == 1) {
            entry.close();
            handle.closedAt = sharedLockSteps(readTree).length;
          }
          return null;
        }).when(witness).close();
        return witness;
      }).when(reader).loadPageForRead(eq(tree.getFileId()), anyLong());
    }

    private void check() {
      if (++checks == selectedCheck) {
        // Exclusive owners bypass the real shared lock in both inherited methods.
        assertThat(readTree.isSharedOwner()).as("shared ownership at guard %s", checks).isTrue();
        assertThat(readTree.isExclusiveOwner()).isFalse();
        guardStep = sharedLockSteps(readTree).length;
      }
    }

    private int assertContinuousHold() {
      return assertContinuousHold(2);
    }

    private int assertContinuousHold(int minimumHandles) {
      assertThat(checks).isEqualTo(selectedCheck);
      var steps = sharedLockSteps(readTree);
      assertThat(guardStep).isPositive();
      assertThat(IntStream.of(steps).limit(guardStep).sum()).isEqualTo(1);
      int depth = 0;
      for (int i = guardStep; i < steps.length - 1; i++) {
        depth += steps[i];
        // Nested holds may come and go, but the selected outer hold must survive.
        assertThat(depth).as("shared hold released before the end of guard %s read", checks)
            .isNotNegative();
      }
      assertThat(steps.length).isGreaterThan(guardStep);
      assertThat(depth + steps[steps.length - 1]).as("terminal shared release").isEqualTo(-1);
      assertThat(readTree.isSharedOwner()).isFalse();
      assertThat(readTree.isExclusiveOwner()).isFalse();

      assertThat(handles).hasSizeGreaterThanOrEqualTo(minimumHandles);
      for (var handle : handles) {
        assertThat(handle.closes).as("close count for page %s", handle.page).isEqualTo(1);
        assertThat(handle.closedAt).as("real close before terminal shared release for page %s",
            handle.page).isBetween(guardStep, steps.length - 1);
      }
      return steps.length;
    }

    private List<Long> pages() {
      return handles.stream().map(handle -> handle.page).toList();
    }
  }

  private static final class ReadHandle {

    private final long page;
    private int closes;
    private int closedAt = -1;

    private ReadHandle(long page) {
      this.page = page;
    }
  }

  private static int[] sharedLockSteps(BTree<?> readTree) {
    return mockingDetails(readTree).getInvocations().stream()
        .mapToInt(call -> switch (call.getMethod().getName()) {
          case "acquireSharedLock" -> 1;
          case "releaseSharedLock" -> -1;
          default -> 0;
        })
        .filter(step -> step != 0)
        .toArray();
  }

  private Runnable countingGuard(AtomicInteger checks) {
    return () -> {
      assertThat(tree.isSharedOwner()).isTrue();
      checks.incrementAndGet();
    };
  }

  private void failPageLoadAfterCheck(AtomicOperation reader, IOException cause,
      AtomicInteger checks, int expectedChecks) throws IOException {
    doAnswer(call -> {
      assertThat(checks.get()).isEqualTo(expectedChecks);
      assertThat(tree.isSharedOwner()).isTrue();
      throw cause;
    }).when(reader).loadPageForRead(eq(tree.getFileId()), anyLong());
  }

  private CellBTreeSingleValueV3Exception assertReadFailure(ThrowingCallable read,
      IOException cause) {
    var failure = assertThrows(CellBTreeSingleValueV3Exception.class, read::call);
    assertThat(tree.isSharedOwner()).isFalse();
    assertThat(failure.getCause()).isSameAs(cause);
    return failure;
  }

  private static IndexesSnapshot snapshot() {
    return new IndexesSnapshot(new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(AbstractStorage.INDEX_SNAPSHOT_VERSION_COMPARATOR),
        new AtomicLong(), 1L);
  }
}
