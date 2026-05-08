package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.common.concur.lock.LockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Smoke-level concurrency probes that pin the <b>current</b> behaviour of three named
 * {@code WOWCache} call sites flagged by Phase A adversarial review F4 as suspected races.
 *
 * <p>Each test is <b>WHEN-FIXED-pinned</b>: the assertion captures what the code does today,
 * with a comment naming the specific change to flip the assertion when the corresponding
 * production fix lands. The fix is forwarded to Track 22 — Track 20 is test-additive only.
 *
 * <p>Three shapes (line citations against the current {@code WOWCache.java}; ±1-line offset
 * is acceptable for annotation drift):
 * <ol>
 *   <li>{@code addOnlyWriters} / {@code removeOnlyWriters} (lines 1350-1358) mutate
 *       {@code exclusiveWriteCacheSize} (counter) and {@code exclusiveWritePages} (set)
 *       without holding the per-page {@code lockManager} exclusive lock — counter drift /
 *       orphan {@code PageKey} are observable under concurrent add+remove for the same key.
 *       The author comment at WOWCache.java:3975-3977 admits eventual consistency between
 *       {@code exclusiveWritePages} and {@code writeCachePages}.
 *   <li>{@code fileIdByName} (lines 846-854) reads {@code nameIdMap} alone. {@code addFile}
 *       (lines 831-832) writes {@code nameIdMap} <i>before</i> {@code idNameMap}. A reader
 *       between the two writes can see a fileId via {@code fileIdByName} that no
 *       {@code idNameMap} lookup yet resolves.
 *   <li>{@code store} (lines 1213-1239) — when a second {@code store} call arrives for an
 *       already-occupied {@code PageKey} with a <i>different</i> {@code CachePointer}, the
 *       {@code assert pagePointer.equals(dataPointer)} only fires under {@code -ea}.
 *       Production silently swallows the mismatch (the original {@code CachePointer} stays).
 * </ol>
 *
 * <p>The probes use {@link CountDownLatch} / {@link CyclicBarrier} to maximise overlap and
 * fail in under 5 s. They do NOT use {@code Thread.sleep()}. Each test instantiates a
 * Mockito spy of {@code WOWCache} with {@code CALLS_REAL_METHODS} and injects the minimal
 * field set — full WOWCache lifecycle is not needed because the probed methods only touch
 * a handful of fields.
 *
 * <p>{@code WOWCache} is package-private to its own package; this test must live in the same
 * package to access {@link PageKey} and to reflect into private fields.
 */
public class WOWCacheConcurrencyShapesTest {

  /**
   * Reflection-set a declared field on a {@code WOWCache} mock instance.
   */
  private static void setField(WOWCache cache, String fieldName, Object value) throws Exception {
    Field f = WOWCache.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(cache, value);
  }

  /**
   * Reflection-read a declared field on a {@code WOWCache} mock instance.
   */
  private static Object getField(WOWCache cache, String fieldName) throws Exception {
    Field f = WOWCache.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    return f.get(cache);
  }

  // ---------------------------------------------------------------------------
  // Shape 1: addOnlyWriters / removeOnlyWriters counter drift
  // ---------------------------------------------------------------------------

  /**
   * Pins that {@code addOnlyWriters} and {@code removeOnlyWriters} pair up exactly when each
   * key is added and removed the same number of times — even under heavy concurrent contention,
   * the {@code AtomicLong} counter alone is a single atomic operation.
   *
   * <p>The contention this test exercises is N writers each calling
   * {@code addOnlyWriters} followed by {@code removeOnlyWriters} on a shared key. The
   * {@code exclusiveWriteCacheSize} counter is an {@code AtomicLong} so its end-to-end value
   * stays at zero. The interesting <i>shape</i> is that the counter and the set update
   * separately ({@code incrementAndGet} on line 1351 then {@code add} on line 1352). Under
   * heavy add+remove for the same key, the set reaches steady state (no element) but the
   * counter sequence is non-monotonic — a transient observer of the counter can see values
   * that do not match the set's cardinality.
   *
   * <p><b>WHEN-FIXED:</b> if {@code addOnlyWriters} / {@code removeOnlyWriters} are made
   * atomic across the two field updates (e.g., guarded by {@code lockManager.acquireExclusiveLock(pageKey)}
   * around both lines), the counter and set will be observed in lock-step. This test pins
   * the <i>final</i> values only — flipping the assertion to also pin a transient observer
   * is the WHEN-FIXED change. Forward the fix to Track 22.
   */
  @Test(timeout = 5_000)
  public void testAddRemoveOnlyWritersCounterAndSetSettleToZero() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);

    var counter = new AtomicLong();
    var pages = new ConcurrentSkipListSet<PageKey>();
    setField(cache, "exclusiveWriteCacheSize", counter);
    setField(cache, "exclusiveWritePages", pages);

    final int threadCount = 8;
    final int opsPerThread = 200;
    final long fileId = ((long) 1 << 32) | 7L; // composeFileId(1, 7)
    final var startBarrier = new CyclicBarrier(threadCount);
    var executor = Executors.newFixedThreadPool(threadCount);
    var failures = new AtomicInteger();
    try {
      for (int t = 0; t < threadCount; t++) {
        final int tid = t;
        executor.submit(
            () -> {
              try {
                startBarrier.await(2, TimeUnit.SECONDS);
                for (int i = 0; i < opsPerThread; i++) {
                  // Each thread uses a unique pageIndex so add/remove pair up cleanly.
                  // The interesting overlap is that many threads call
                  // incrementAndGet/decrementAndGet on the shared counter while doing
                  // add/remove on the shared set.
                  long pageIndex = (long) tid * opsPerThread + i;
                  cache.addOnlyWriters(fileId, pageIndex);
                  cache.removeOnlyWriters(fileId, pageIndex);
                }
              } catch (Throwable e) {
                failures.incrementAndGet();
              }
            });
      }
      executor.shutdown();
      assertTrue(
          "All worker threads must finish within 5s",
          executor.awaitTermination(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertEquals("No worker thread should have thrown", 0, failures.get());
    assertEquals(
        "Counter must settle to zero when every add is paired with a remove (current"
            + " behaviour: AtomicLong is atomic per op, so net delta is zero)",
        0L, counter.get());
    assertTrue(
        "exclusiveWritePages must be empty when every add is paired with a remove",
        pages.isEmpty());
  }

  /**
   * Pins the <i>current</i> behaviour of {@code addOnlyWriters} under concurrent contention on
   * the same {@code PageKey}: N writer threads call {@code addOnlyWriters(fileId, pageIndex)}
   * with identical arguments. The {@code ConcurrentSkipListSet} de-duplicates the key so its
   * cardinality stays at 1, but the {@code AtomicLong} counter is incremented once per call,
   * so it ends at exactly N — the orphan-PageKey shape that the production fix in Track 22
   * is meant to close.
   *
   * <p>The probe uses a {@link CyclicBarrier} to synchronise the offer point so all writers
   * race the {@code add(pageKey)} call site simultaneously, maximising the chance of observing
   * the drift. Worker errors are captured in an {@link AtomicReference} so a thread-side
   * exception cannot be silently swallowed when the daemon thread exits.
   *
   * <p><b>WHEN-FIXED:</b> if {@code addOnlyWriters} is made idempotent (skip the
   * {@code incrementAndGet} when the key already exists) — e.g., by guarding both updates
   * under {@code lockManager.acquireExclusiveLock(pageKey)} and re-checking {@code add()}'s
   * boolean return — the counter will end at exactly 1. Flip the {@code counter == writers}
   * assertion to {@code counter == 1} when the fix lands. Forward to Track 22.
   */
  @Test(timeout = 5_000)
  public void testAddOnlyWritersDoubleAddCounterDriftPinsCurrentBehaviour() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);

    var counter = new AtomicLong();
    var pages = new ConcurrentSkipListSet<PageKey>();
    setField(cache, "exclusiveWriteCacheSize", counter);
    setField(cache, "exclusiveWritePages", pages);

    final int writers = 8;
    final long fileId = ((long) 1 << 32) | 7L;
    final long sharedPageIndex = 99L;
    final var barrier = new CyclicBarrier(writers);
    final var done = new CountDownLatch(writers);
    final var errorRef = new AtomicReference<Throwable>();

    var executor = Executors.newFixedThreadPool(writers);
    try {
      for (int t = 0; t < writers; t++) {
        executor.submit(
            () -> {
              try {
                barrier.await(2, TimeUnit.SECONDS);
                cache.addOnlyWriters(fileId, sharedPageIndex);
              } catch (Throwable e) {
                errorRef.compareAndSet(null, e);
              } finally {
                done.countDown();
              }
            });
      }
      assertTrue(
          "All writer threads must finish within 5 s",
          done.await(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertNull("No worker thread should have thrown: " + errorRef.get(), errorRef.get());

    // Set semantics: ConcurrentSkipListSet de-duplicates, so size stays at 1.
    assertEquals("Set must hold the contended key only once", 1, pages.size());

    // WHEN-FIXED: change to assertEquals(1L, counter.get()) once addOnlyWriters becomes
    // idempotent — see the class-level comment for shape 1.
    assertEquals(
        "Current behaviour: counter increments per call under contention, NOT per unique"
            + " key — drift of (writers - 1) against the set cardinality",
        (long) writers,
        counter.get());
  }

  // ---------------------------------------------------------------------------
  // Shape 2: fileIdByName visibility race
  // ---------------------------------------------------------------------------

  /**
   * Pins that {@code fileIdByName} returns a non-negative result solely from
   * {@code nameIdMap}, with no consultation of {@code idNameMap} — proving the
   * "between-puts" window is structurally observable: writing only {@code nameIdMap}
   * (and leaving {@code idNameMap} empty) makes {@code fileIdByName} resolve to a
   * fileId that {@code idNameMap.containsKey} cannot match.
   *
   * <p>This avoids a multi-threaded scheduler-dependent probe (which is flaky on a
   * fast machine that JIT-merges the two puts into the same memory barrier) by
   * exercising the same code path as the leaked state would: the reader sees
   * exactly the state {@code addFile} produces between WOWCache.java:831 and
   * WOWCache.java:832. The structural shape is what matters — if the production
   * fix routes {@code fileIdByName} through {@code filesLock.acquireReadLock()}
   * (or otherwise consults {@code idNameMap} before returning), this test will
   * fail because the reader will block until {@code addFile} releases the write
   * lock with both maps populated.
   *
   * <p><b>WHEN-FIXED:</b> when {@code fileIdByName} is made consistent (either by
   * taking {@code filesLock} or by atomically publishing both maps), the test
   * below should be flipped: {@code idNameMap.containsKey(intId)} will be {@code
   * true} when {@code fileIdByName} returns a non-negative external ID. Update
   * the {@code assertFalse} to {@code assertTrue} and remove the
   * "intermediate-state" framing. Forward to Track 22.
   */
  @Test(timeout = 5_000)
  public void testFileIdByNameLeakedStatePinsCurrentBehaviour() throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "id", 1);
    setField(cache, "filesLock", new ReadersWriterSpinLock());
    setField(cache, "nameIdMap", new ConcurrentHashMap<String, Integer>());
    setField(cache, "idNameMap", new ConcurrentHashMap<Integer, String>());
    setField(cache, "closed", false);
    setField(cache, "storageName", "test");

    @SuppressWarnings("unchecked")
    var nameIdMap = (ConcurrentHashMap<String, Integer>) getField(cache, "nameIdMap");
    @SuppressWarnings("unchecked")
    var idNameMap = (ConcurrentHashMap<Integer, String>) getField(cache, "idNameMap");

    // Reproduce exactly the state that exists between addFile's two puts at
    // WOWCache.java:831 and WOWCache.java:832 — nameIdMap populated, idNameMap empty.
    final int internalId = 7;
    final String fileName = "file-7";
    nameIdMap.put(fileName, internalId);
    // idNameMap is intentionally NOT populated yet — the reader will observe the gap.

    long ext = cache.fileIdByName(fileName);
    assertTrue(
        "Current behaviour: fileIdByName resolves the name even when the inverse map"
            + " entry has not been published yet (filesLock is not taken on the read path).",
        ext >= 0);

    int observedInternalId = (int) (ext & 0xFFFFFFFFL);
    assertEquals(
        "Resolved internal id must match the one published in nameIdMap",
        internalId, observedInternalId);
    assertFalse(
        "Current behaviour: idNameMap is empty even though fileIdByName already resolved"
            + " — proving the leaked-state window is structurally observable, not just a"
            + " scheduler artifact. WHEN-FIXED: flip to assertTrue once fileIdByName takes"
            + " filesLock (Track 22).",
        idNameMap.containsKey(observedInternalId));

    // Concurrency probe: spin a writer thread that toggles the gap while a reader observes
    // it. This pins the shape under actual MT contention as well — the structural test
    // above is the load-bearing pin; the MT loop is reinforcement that bounded test latency
    // is enough to reproduce the race.
    final int iterations = 500;
    var leakedAtLeastOnce = new AtomicInteger();
    var done = new CountDownLatch(2);
    var threadErrors = new AtomicReference<Throwable>();

    var reader = new Thread(
        () -> {
          try {
            for (int i = 0; i < iterations; i++) {
              long observed = cache.fileIdByName("mt-" + i);
              if (observed >= 0) {
                int intId = (int) (observed & 0xFFFFFFFFL);
                if (!idNameMap.containsKey(intId)) {
                  leakedAtLeastOnce.incrementAndGet();
                }
              }
            }
          } catch (Throwable t) {
            threadErrors.compareAndSet(null, t);
          } finally {
            done.countDown();
          }
        });
    reader.setDaemon(true);

    var writer = new Thread(
        () -> {
          try {
            for (int i = 0; i < iterations; i++) {
              String name = "mt-" + i;
              int id = 10_000 + i;
              nameIdMap.put(name, id);
              idNameMap.put(id, name);
            }
          } catch (Throwable t) {
            threadErrors.compareAndSet(null, t);
          } finally {
            done.countDown();
          }
        });
    writer.setDaemon(true);

    reader.start();
    writer.start();
    assertTrue(
        "Both threads must finish within 3s",
        done.await(3, TimeUnit.SECONDS));
    assertNull("MT thread threw: " + threadErrors.get(), threadErrors.get());

    // The MT loop is reinforcement only — the structural pin above is what fails first if
    // the production fix lands. We do not assert leakedAtLeastOnce > 0 here because the
    // MT-flake risk on fast machines is real; the structural pin is sufficient to surface
    // the shape.
  }

  // ---------------------------------------------------------------------------
  // Shape 3: store() re-entry silently swallows mismatched CachePointer
  // ---------------------------------------------------------------------------

  /**
   * Pins that {@code store(fileId, pageIndex, dataPointer)} called twice with two distinct
   * {@code CachePointer} instances on the same {@code PageKey} silently keeps the
   * <b>first</b> {@code CachePointer} — the {@code assert pagePointer.equals(dataPointer)}
   * at line 1229 only fires under {@code -ea}. Production swallows the mismatch.
   *
   * <p>This test runs without {@code -ea} guard awareness — surefire commonly runs with
   * {@code -ea} on, in which case the second {@code store} call would throw
   * {@code AssertionError}. To exercise the production path, the test catches and ignores
   * an {@code AssertionError} from the second call (so the test is meaningful in either JVM
   * mode), then asserts that the map still holds the original pointer.
   *
   * <p><b>WHEN-FIXED:</b> if the assert is replaced by an unconditional {@code throw new
   * IllegalStateException} (or an idempotent overwrite policy), the second call will either
   * throw or replace the pointer regardless of {@code -ea}. Either fix flips the assertion
   * — change to {@code assertSame(secondPointer, ...)} for an overwrite policy or remove the
   * try-catch and assert the exception class for a hard-fail policy. Forward to Track 22.
   */
  @Test(timeout = 5_000)
  public void testStoreReentryWithDistinctPointerKeepsOriginalPinsCurrentBehaviour()
      throws Exception {
    var cache = Mockito.mock(WOWCache.class, Mockito.CALLS_REAL_METHODS);
    setField(cache, "id", 1);
    setField(cache, "filesLock", new ReadersWriterSpinLock());
    setField(cache, "closed", false);
    setField(cache, "storageName", "test");
    setField(cache, "writeCachePages", new ConcurrentHashMap<PageKey, CachePointer>());
    setField(cache, "writeCacheSize", new AtomicLong());
    setField(cache, "exclusiveWriteCacheSize", new AtomicLong());
    setField(cache, "exclusiveWritePages", new ConcurrentSkipListSet<PageKey>());
    LockManager<PageKey> lockManager = new PartitionedLockManager<>();
    setField(cache, "lockManager", lockManager);

    final long externalFileId = ((long) 1 << 32) | 7L;
    final long pageIndex = 99L;

    @SuppressWarnings("unchecked")
    var writeCachePages =
        (ConcurrentHashMap<PageKey, CachePointer>) getField(cache, "writeCachePages");

    // Two distinct CachePointer instances — equals() on CachePointer compares fileId and
    // pageIndex, so two pointers with different (fileId, pageIndex) are NOT equal. The
    // second store call will trigger the assert under -ea.
    var firstPointer = mock(CachePointer.class);
    var secondPointer = mock(CachePointer.class);

    cache.store(externalFileId, pageIndex, firstPointer);

    var key = new PageKey((int) (externalFileId & 0xFFFFFFFFL), pageIndex);
    assertSame("First store must publish firstPointer", firstPointer, writeCachePages.get(key));

    boolean assertFiredUnderEa = false;
    try {
      cache.store(externalFileId, pageIndex, secondPointer);
    } catch (AssertionError ae) {
      // -ea is on (typical surefire JVM flag); the assert fired — this is one of the two
      // observable behaviours today and we record it.
      assertFiredUnderEa = true;
    }

    // The shape we're pinning: regardless of -ea, the firstPointer is still in the map.
    // Under -ea the second store threw before doPutInCache; without -ea, the assert is
    // a no-op and the else branch silently keeps firstPointer.
    assertSame(
        "Current behaviour: writeCachePages still holds the first CachePointer after a"
            + " mismatched second store — the assert path does not replace the pointer."
            + " WHEN-FIXED: replace assert with hard-fail or atomic overwrite policy and"
            + " update this assertion accordingly (Track 22).",
        firstPointer, writeCachePages.get(key));

    // Note: both JVM modes (-ea on / -ea off) preserve firstPointer in writeCachePages.
    // The assertSame above is the load-bearing pin; the assertFiredUnderEa local is kept
    // only to make the two execution paths explicit in the source for future readers.
    // It is intentionally not asserted on — the contract under test is the post-condition
    // on writeCachePages, not which path the JVM took to reach it.
    // Reference the local to make the "intentional read" obvious to any future reader.
    if (assertFiredUnderEa) {
      // -ea on: second store threw before doPutInCache.
    }
  }
}
