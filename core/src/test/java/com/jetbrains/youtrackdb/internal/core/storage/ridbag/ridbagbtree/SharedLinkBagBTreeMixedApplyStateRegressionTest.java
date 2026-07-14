package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationTestBridge;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end deterministic regression test for YTDB-1178 against the real
 * {@link SharedLinkBagBTree} on the disk engine.
 *
 * <p><b>The race (CI signature — LinksConsistencyException):</b> a committing
 * transaction whose leaf split moved keys to a new right sibling applies its changed
 * pages to the shared read cache one page at a time, in hash order. Per-page StampedLock
 * stamps are a purely temporal check, so a concurrent optimistic reader overlapping that
 * apply window can traverse a <em>mixed</em> state with every stamp valid: the shrunk
 * source leaf and the new sibling already applied, but the parent (and entry point)
 * still stale. Descending through the stale parent lands on the shrunk leaf; the moved
 * target key is past its end; the one-hop right-sibling check only inspects sibling
 * index 0 — so any moved key that landed at sibling index &gt; 0 produces a false
 * "absent" result. Upstream, {@code LinkBag.remove} surfaces that as
 * LinksConsistencyException, and {@code LinkBag.add} silently overwrites the counter
 * (AR-4).
 *
 * <p><b>The fix under test:</b> the per-storage {@code ApplyPhaseEpoch} bracket around
 * the commit-time apply loop. A reader whose window overlaps any apply phase fails
 * {@code OptimisticReadScope.validateOrThrow()} and falls back to the pinned path, which
 * blocks on the component shared lock until the writer commits — returning the correct
 * entry.
 *
 * <p><b>Determinism:</b> the {@code PageApplyHook} test seam pins the adversarial apply
 * order (changed leaves and new sibling pages first, then a barrier, then entry point
 * and parent/root) and pauses the writer mid-apply — inside the epoch bracket, component
 * exclusive lock held — while a reader thread performs the lookups. Epoch counters
 * (baseline-relative), a hook latch, and a reader-blocked check guard against the test
 * silently degenerating into a non-overlapping schedule.
 */
@Category(SequentialTest.class)
public class SharedLinkBagBTreeMixedApplyStateRegressionTest {

  private static final String DB_NAME = "mixedApplyStateRegressionTest";
  private static final String DIR_NAME = "/mixedApplyStateRegressionTest";

  // Well-known page indexes of SharedLinkBagBTree (private constants ENTRY_POINT_INDEX
  // and ROOT_INDEX in production). The hook cross-checks at runtime that both appear
  // among the changed pages of the split commit, so a drift of these constants fails
  // loudly instead of silently reordering the wrong pages.
  private static final long ENTRY_POINT_PAGE = 0;
  private static final long ROOT_PAGE = 1;

  private static final long RID_BAG_ID = 7L;
  private static final int TARGET_COLLECTION = 3;
  // Large enough that the pre-built tree has a root plus several leaves (the harness
  // tests show a few hundred entries span multiple leaf pages), small enough to stay a
  // two-level tree so the split leaf's parent is the root.
  private static final int ENTRY_COUNT = 512;
  // Counter value used for the writer's new keys, distinguishable from pre-built ones.
  private static final int NEW_KEY_COUNTER = 7777;

  private static YouTrackDBImpl youTrackDB;
  private static AbstractStorage storage;
  private static AtomicOperationsManager atomicOperationsManager;
  private static String buildDirectory;

  private SharedLinkBagBTree bTree;

  /** Pre-built committed content: targetPosition → expected counter. */
  private final NavigableMap<Long, Integer> reference = new TreeMap<>();

  @BeforeClass
  public static void beforeClass() {
    buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null) {
      buildDirectory = "./target" + DIR_NAME;
    } else {
      buildDirectory += DIR_NAME;
    }

    FileUtils.deleteRecursively(new File(buildDirectory));

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK, "admin", "admin", "admin");

    var session = youTrackDB.open(DB_NAME, "admin", "admin");
    storage = session.getStorage();
    atomicOperationsManager = storage.getAtomicOperationsManager();
    session.close();
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.drop(DB_NAME);
    youTrackDB.close();
    FileUtils.deleteRecursively(new File(buildDirectory));
  }

  @Before
  public void setUp() throws Exception {
    bTree = new SharedLinkBagBTree(storage, "mixedStateBTree", ".sbc");
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.create(atomicOperation));

    // Pre-build and commit the tree in one transaction: EVEN targetPositions only, so
    // the writer transaction can later interleave new keys at the odd gap positions of
    // the rightmost leaf until it splits. Committing loads every page into the read
    // cache, enabling the optimistic read paths.
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var i = 0; i < ENTRY_COUNT; i++) {
        var position = 2L * i;
        bTree.put(atomicOperation,
            new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, position, 0L),
            new LinkBagValue(i + 1, 0, 0, false));
        reference.put(position, i + 1);
      }
    });
  }

  @After
  public void tearDown() throws Exception {
    reference.clear();
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> bTree.delete(atomicOperation));
  }

  /**
   * False-null variant (the LinksConsistencyException signature): while the writer is
   * paused mid-apply in the mixed state, a reader performs {@code findCurrentEntry} —
   * the prefix lookup used by {@code LinkBag.remove} — for ALL pre-built keys in
   * descending order. Descending order makes the very first (blocking) lookup target
   * the moved upper-half range; querying all keys guarantees covering moved keys that
   * landed at sibling index &gt; 0 without precomputing the split point. With the epoch
   * every lookup must return the correct entry.
   */
  @Test
  public void testFindCurrentEntryCorrectDuringMixedApplyState() throws Exception {
    runMixedStateScenario((operation, position, expectedCounter) -> {
      var pair = bTree.findCurrentEntry(
          operation, RID_BAG_ID, TARGET_COLLECTION, position);
      assertNotNull(
          "findCurrentEntry returned false-null for position " + position
              + " — the YTDB-1178 mixed-apply-state race leaked through",
          pair);
      assertEquals(position, pair.first().targetPosition);
      assertEquals(
          "wrong counter for position " + position,
          expectedCounter, pair.second().counter());
    });
  }

  /**
   * Silent add-counter-overwrite variant (AR-4): the first step of a concurrent
   * {@code LinkBag.add} counter increment is reading the current absolute value via the
   * visible-entry path ({@code findVisibleEntry}). A false-null there makes the add
   * treat the existing rid as absent and reset its counter. While the writer is paused
   * mid-apply, the reader resolves the visible entry for ALL pre-built rids (descending,
   * so the first lookup targets the moved range) and must always see the true committed
   * pre-split counter — never null, never reset.
   *
   * <p>Trade-off note: a full two-transaction concurrent add is disproportionate at
   * this harness level — the second writer's put would serialize on the component
   * exclusive lock before writing anything, so the only step of the add that can
   * actually race with the mid-apply state is this initial absolute-value read.
   * Asserting the read returns the true committed value during the mixed state is the
   * faithful equivalent of the counter-overwrite scenario.
   */
  @Test
  public void testFindVisibleEntryCounterNotResetDuringMixedApplyState() throws Exception {
    runMixedStateScenario((operation, position, expectedCounter) -> {
      var pair = bTree.findVisibleEntry(
          operation, RID_BAG_ID, TARGET_COLLECTION, position);
      assertNotNull(
          "findVisibleEntry returned false-null for position " + position
              + " — a concurrent add would have reset the counter (AR-4)",
          pair);
      assertEquals(
          "counter for position " + position + " must be the true committed value",
          expectedCounter, pair.second().counter());
      assertFalse("entry must not be a tombstone", pair.second().tombstone());
    });
  }

  /**
   * Per-key lookup assertion executed by the reader thread inside its atomic operation.
   */
  @FunctionalInterface
  private interface LookupAssertion {

    void check(AtomicOperation operation, long position, int expectedCounter)
        throws Exception;
  }

  /**
   * Shared choreography for both variants:
   *
   * <ol>
   *   <li>Writer thread: inserts odd-position keys descending from the top gap until
   *       the rightmost leaf splits (detected via {@code filledUpTo} growth — exactly
   *       one page is allocated by the split), installs the page-apply hook, and
   *       returns; the commit then applies changed leaves + new sibling first and pauses
   *       at the barrier before the entry point and parent/root.
   *   <li>Main thread: waits for the pause, asserts the epoch is mid-bracket
   *       (baseline-relative), then starts the reader.
   *   <li>Reader thread: runs the per-key lookups descending. The first lookup's
   *       optimistic attempt overlaps the mixed state, fails epoch validation, and its
   *       pinned fallback blocks on the component shared lock (verified by polling the
   *       thread state; completing while paused fails the test as degeneration).
   *   <li>Main thread: releases the barrier; writer finishes; reader unblocks and all
   *       its assertions must pass; final full verification on a fresh operation.
   * </ol>
   */
  private void runMixedStateScenario(LookupAssertion lookup) throws Exception {
    final var epoch = AtomicOperationTestBridge.applyPhaseEpoch(atomicOperationsManager);
    // Baseline is captured while no operation is running (setUp's operations have
    // committed). Never assert absolute epoch values — every operation (including
    // read-only ones) brackets its own apply phase at commit.
    final long baseEnter = epoch.enterSeq();
    final long baseExit = epoch.exitSeq();
    assertEquals("epoch must be quiescent at baseline", baseEnter, baseExit);

    final var writerPaused = new CountDownLatch(1);
    final var releaseWriter = new CountDownLatch(1);
    final var barrierArmed = new AtomicBoolean(false);
    final var hookError = new AtomicReference<String>();
    final var writerError = new AtomicReference<Throwable>();
    final var insertedCount = new AtomicInteger();

    final var writer = new Thread(() -> {
      try {
        atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
          final long btreeFileId = bTree.getFileId();
          final long preFilled = atomicOperation.filledUpTo(btreeFileId);

          // Insert odd-position keys descending from the top gap. They interleave
          // with the pre-built even keys of the rightmost leaf, so when the leaf
          // overflows, the moved upper half is a mix of new and PRE-EXISTING keys —
          // guaranteeing pre-existing keys land in the new sibling at indexes > 0.
          // Stop at the first page allocation: the split has happened.
          var position = 2L * ENTRY_COUNT - 1;
          while (atomicOperation.filledUpTo(btreeFileId) == preFilled) {
            if (position < 1) {
              throw new IllegalStateException(
                  "Exhausted all odd gap positions without triggering a leaf split"
                      + " — leaf capacity larger than the pre-built key count");
            }
            bTree.put(atomicOperation,
                new EdgeKey(RID_BAG_ID, TARGET_COLLECTION, position, 0L),
                new LinkBagValue(NEW_KEY_COUNTER, 0, 0, false));
            position -= 2;
            insertedCount.incrementAndGet();
          }

          // Install the adversarial apply order: changed pre-existing leaves first,
          // then all new pages (the sibling), then barrier, then entry point + root.
          AtomicOperationTestBridge.installPageApplyHook(atomicOperation,
              new AtomicOperationTestBridge.TestPageApplyHook() {
                @Override
                public long[] orderPageApplications(
                    final long fileId, final long[] pageIndexes) {
                  if (fileId != btreeFileId) {
                    return null;
                  }
                  var hasEntryPoint = false;
                  var hasRoot = false;
                  final var preExistingLeaves = new ArrayList<Long>();
                  final var newPages = new ArrayList<Long>();
                  for (final var pageIndex : pageIndexes) {
                    if (pageIndex == ENTRY_POINT_PAGE) {
                      hasEntryPoint = true;
                    } else if (pageIndex == ROOT_PAGE) {
                      hasRoot = true;
                    } else if (pageIndex < preFilled) {
                      preExistingLeaves.add(pageIndex);
                    } else {
                      newPages.add(pageIndex);
                    }
                  }
                  // Degeneration guards: a leaf split must change the entry point
                  // (pagesSize), the root (separator), at least one pre-existing leaf
                  // (the shrunk source), and allocate at least one new page (the
                  // sibling). Record the violation and keep the default order — the
                  // barrier stays unarmed so the commit completes and the main thread
                  // fails on hookError instead of hanging.
                  if (!hasEntryPoint || !hasRoot
                      || preExistingLeaves.isEmpty() || newPages.isEmpty()) {
                    hookError.compareAndSet(null,
                        "Unexpected changed-page classification: pages="
                            + Arrays.toString(pageIndexes)
                            + " preFilled=" + preFilled);
                    return null;
                  }
                  preExistingLeaves.sort(null);
                  newPages.sort(null);
                  final var order = new long[pageIndexes.length];
                  var i = 0;
                  for (final var pageIndex : preExistingLeaves) {
                    order[i++] = pageIndex;
                  }
                  for (final var pageIndex : newPages) {
                    order[i++] = pageIndex;
                  }
                  order[i++] = ENTRY_POINT_PAGE;
                  order[i] = ROOT_PAGE;
                  barrierArmed.set(true);
                  return order;
                }

                @Override
                public void beforePageApply(final long fileId, final long pageIndex) {
                  // The entry point is the first deferred page: pausing here leaves
                  // the shrunk leaf and the new sibling applied but the parent/root
                  // and entry point stale — the CI mixed state.
                  if (fileId == btreeFileId && pageIndex == ENTRY_POINT_PAGE
                      && barrierArmed.get()) {
                    writerPaused.countDown();
                    try {
                      if (!releaseWriter.await(120, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                            "Timed out waiting for the release signal");
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(e);
                    }
                  }
                }
              });
        });
      } catch (Throwable t) {
        writerError.set(t);
      }
    });

    final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    try {
      writer.start();

      if (!writerPaused.await(60, TimeUnit.SECONDS)) {
        writer.join(TimeUnit.SECONDS.toMillis(10));
        fail("Writer never paused at the mid-apply barrier; hookError="
            + hookError.get() + " writerError=" + writerError.get());
      }
      assertNull("hook classification failed: " + hookError.get(), hookError.get());
      assertTrue("writer must have inserted at least one key", insertedCount.get() > 0);

      // Mixed-state proof #1: the writer is inside the epoch bracket (one unmatched
      // enter relative to the baseline). Only the paused writer and this thread are
      // active, so the relative values are deterministic.
      assertEquals(baseEnter + 1, epoch.enterSeq());
      assertEquals(baseExit, epoch.exitSeq());

      // Reader: query ALL pre-built keys in DESCENDING order so the first (blocking)
      // lookup targets the moved upper-half range.
      final var readerThreadRef = new AtomicReference<Thread>();
      final var firstLookupStarted = new CountDownLatch(1);
      final Future<Void> readerFuture = readerExecutor.submit((Callable<Void>) () -> {
        readerThreadRef.set(Thread.currentThread());
        atomicOperationsManager.executeInsideAtomicOperation(operation -> {
          // Signal right before the first lookup: from here on, the only park point
          // on the lookup path is the component shared lock in the pinned fallback,
          // so the main thread's state poll cannot mistake an unrelated wait (class
          // loading, executor hand-off, operation start) for the lock block.
          firstLookupStarted.countDown();
          for (final var entry : reference.descendingMap().entrySet()) {
            lookup.check(operation, entry.getKey(), entry.getValue());
          }
        });
        return null;
      });

      assertTrue("reader never reached its first lookup",
          firstLookupStarted.await(30, TimeUnit.SECONDS));
      awaitReaderBlocked(readerThreadRef.get(), readerFuture);

      // Mixed-state proof #2: the reader began its lookups during the pause (it is
      // blocked on the component shared lock) and the apply phase is still open — the
      // reader's window genuinely overlapped the apply phase.
      assertEquals(baseEnter + 1, epoch.enterSeq());
      assertEquals(baseExit, epoch.exitSeq());
      assertFalse("reader must still be blocked while the writer is paused",
          readerFuture.isDone());

      releaseWriter.countDown();

      // The reader's per-key assertions run inside the task; get() propagates them.
      readerFuture.get(120, TimeUnit.SECONDS);

      writer.join(TimeUnit.SECONDS.toMillis(60));
      assertFalse("writer thread did not finish", writer.isAlive());
      assertNull("writer failed: " + writerError.get(), writerError.get());

      // The bracket is balanced again (writer's apply plus the reader's own read-only
      // commit both exited); relative lower bound only — never absolute.
      assertEquals(epoch.enterSeq(), epoch.exitSeq());
      assertTrue(epoch.enterSeq() >= baseEnter + 2);

      // Final verification on a fresh operation: the fully applied tree serves every
      // pre-built key and the writer's first inserted key correctly.
      atomicOperationsManager.executeInsideAtomicOperation(operation -> {
        for (final var entry : reference.entrySet()) {
          final var pair = bTree.findCurrentEntry(
              operation, RID_BAG_ID, TARGET_COLLECTION, entry.getKey());
          assertNotNull("post-commit lookup failed for position " + entry.getKey(),
              pair);
          assertEquals((int) entry.getValue(), pair.second().counter());
        }
        final var firstInserted = bTree.findCurrentEntry(
            operation, RID_BAG_ID, TARGET_COLLECTION, 2L * ENTRY_COUNT - 1);
        assertNotNull("writer's first inserted key must be present", firstInserted);
        assertEquals(NEW_KEY_COUNTER, firstInserted.second().counter());
      });
    } finally {
      // Never leave the writer parked on the barrier (assertion failures above would
      // otherwise leak a paused thread holding the component exclusive lock).
      releaseWriter.countDown();
      readerExecutor.shutdownNow();
      writer.join(TimeUnit.SECONDS.toMillis(60));
    }
  }

  /**
   * Waits until the reader thread blocks on the component shared lock (its pinned
   * fallback parks after the optimistic attempt failed epoch validation). Completing
   * while the writer is still paused would mean the reader never contended with the
   * commit — the race would not have been exercised — so that fails the test.
   */
  private static void awaitReaderBlocked(Thread readerThread, Future<Void> readerFuture)
      throws Exception {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (true) {
      if (readerFuture.isDone()) {
        readerFuture.get(); // propagate the real failure, if any
        fail("Reader completed while the writer was paused mid-apply — the schedule"
            + " degenerated and the race was not exercised");
      }
      final var state = readerThread.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
          || state == Thread.State.BLOCKED) {
        return;
      }
      if (System.nanoTime() > deadline) {
        fail("Timed out waiting for the reader to block on the component shared lock;"
            + " reader state=" + state);
      }
      //noinspection BusyWait — polling is the only way to observe lock-park state
      Thread.sleep(1);
    }
  }
}
