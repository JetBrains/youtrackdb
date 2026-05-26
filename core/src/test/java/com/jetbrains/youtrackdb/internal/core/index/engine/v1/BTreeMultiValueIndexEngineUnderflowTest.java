package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests the in-memory approximate-count underflow handling on
 * {@link BTreeMultiValueIndexEngine#addToApproximateEntriesCount(long)} and
 * {@link BTreeMultiValueIndexEngine#addToApproximateNullCount(long)}.
 *
 * <p>The two mutators replace the legacy {@code assert updated >= 0} trap with
 * a clamp+error path that (1) logs at error level with the engine's
 * {@code name} and {@code id}, (2) emits a stack trace on the first underflow
 * per engine instance via a shared {@link java.util.concurrent.atomic.AtomicBoolean}
 * latch, and (3) clamps the counter back to 0 via
 * {@link AtomicLong#compareAndSet(long, long)} without throwing. The pre-fix
 * cascade observed in {@code Pre_Tests_Test_REST_2026.2.51599.log} fired at
 * the assert; pinning the new contract here is what stops the cascade at its
 * source.
 *
 * <p>Capture mechanism: the project's test runtime SLF4J binding is
 * slf4j-jdk14, so {@code SLF4JLogManager.log(...)} routes through
 * {@code java.util.logging}. The tests attach a JUL {@link Handler} on the
 * engine class's logger (the name LogManager uses when the requester is an
 * engine instance), capture the records, and assert level + message anchors
 * without locking the exact phrasing.
 *
 * <p>Marked {@link SequentialTest} so the JUL handler attached to the
 * process-global engine-class logger does not see records emitted by
 * concurrent test classes under surefire's parallel-classes mode. The
 * cardinality-sensitive assertions in this class (for example the
 * 16-thread contention test) would flake on foreign records from
 * sibling underflow tests in the same JVM.
 */
@Category(SequentialTest.class)
public class BTreeMultiValueIndexEngineUnderflowTest {

  /**
   * First underflow on a fresh engine emits a SEVERE record carrying the
   * stack trace, identifies the engine by name and id, clamps the counter to
   * 0, and does not throw an AssertionError. Also pins the stack-trace
   * variant marker in the message body ("see stack trace") and asserts the
   * compact-variant marker ("suppressed") is absent — a regression that
   * swapped the two branches' message bodies while still passing the right
   * Throwable would otherwise slip past.
   */
  @Test
  public void firstNullCountUnderflowEmitsStackTraceAndClampsToZero() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    // Fresh engine: approximateNullCount starts at 0. A negative delta
    // forces the addAndGet result below 0 and exercises the clamp path.
    // No throw expected — the legacy `assert updated >= 0` is gone.
    List<LogRecord> captured = BTreeEngineTestFixtures.captureSevereOn(
        BTreeMultiValueIndexEngine.class,
        () -> f.engine.addToApproximateNullCount(-1L));

    // Counter clamped to 0 via compareAndSet(-1, 0).
    assertThat(BTreeEngineTestFixtures.readAtomicLong(f.engine, "approximateNullCount"))
        .as("first underflow must clamp approximateNullCount back to 0")
        .isEqualTo(0L);

    // One error record captured, level SEVERE (java.util.logging maps
    // SLF4J ERROR to JUL SEVERE), message identifies the engine and the
    // updated/delta values, carries the stack-trace-variant marker, and
    // does not carry the compact-variant marker.
    var first = captured.stream()
        .filter(r -> r.getLevel() == Level.SEVERE)
        .findFirst()
        .orElse(null);
    assertThat(first)
        .as("first underflow must emit a SEVERE log record (captured=%s)", captured)
        .isNotNull();
    assertThat(first.getMessage())
        .contains("approximateNullCount")
        .contains("test-mv")
        .contains("id=0")
        .contains("updated=-1")
        .contains("delta=-1")
        .contains("see stack trace")
        .doesNotContain("suppressed");
    assertThat(first.getThrown())
        .as("first-occurrence record must carry a stack trace so the next"
            + " regression is pin-pointable")
        .isNotNull();
    assertThat(first.getThrown())
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * The {@code firstUnderflowDumped} latch is shared by both mutators on the
   * same engine instance: the first underflow on
   * {@link BTreeMultiValueIndexEngine#addToApproximateEntriesCount(long)}
   * wins the CAS and emits the stack trace, so a subsequent
   * {@link BTreeMultiValueIndexEngine#addToApproximateNullCount(long)}
   * underflow on the same engine logs the compact variant without a stack
   * trace. The two variants are discriminated by {@code getThrown() == null}
   * on the captured record — the most durable assertion shape because
   * message-text refinement does not invalidate it.
   */
  @Test
  public void secondUnderflowOnSameEngineEmitsCompactErrorWithoutStack() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    List<LogRecord> captured = BTreeEngineTestFixtures.captureSevereOn(
        BTreeMultiValueIndexEngine.class,
        () -> {
          // First underflow: entries counter, wins the latch, carries the stack.
          f.engine.addToApproximateEntriesCount(-3L);
          // Second underflow on the same engine, different mutator: shared
          // latch is already set, so this records the compact variant.
          f.engine.addToApproximateNullCount(-2L);
        });

    // Both counters clamped to 0.
    assertThat(BTreeEngineTestFixtures.readAtomicLong(f.engine, "approximateIndexEntriesCount"))
        .isEqualTo(0L);
    assertThat(BTreeEngineTestFixtures.readAtomicLong(f.engine, "approximateNullCount"))
        .isEqualTo(0L);

    // Two SEVERE records. The first names approximateIndexEntriesCount and
    // carries the synthetic IllegalStateException as cause; the second names
    // approximateNullCount and has no cause (compact variant — discriminated
    // by getThrown() == null rather than a brittle substring of the message
    // body).
    var severeRecords = captured.stream()
        .filter(r -> r.getLevel() == Level.SEVERE)
        .toList();
    assertThat(severeRecords)
        .as("each underflow on the same engine must produce one SEVERE record"
            + " (captured=%s)", captured)
        .hasSize(2);
    assertThat(severeRecords.get(0).getMessage())
        .contains("approximateIndexEntriesCount")
        .contains("delta=-3");
    assertThat(severeRecords.get(0).getThrown())
        .as("the first underflow on this engine must carry the stack trace")
        .isNotNull();
    assertThat(severeRecords.get(1).getMessage())
        .contains("approximateNullCount")
        .contains("delta=-2");
    assertThat(severeRecords.get(1).getThrown())
        .as("subsequent underflows on the same engine must skip the stack trace")
        .isNull();
  }

  /**
   * Mirror of {@link #secondUnderflowOnSameEngineEmitsCompactErrorWithoutStack}
   * with the call order reversed: null first, entries second. Pins that the
   * shared latch is genuinely shared across mutators in both directions — a
   * subtle copy-paste regression that split {@code firstUnderflowDumped} into
   * per-mutator fields "for clarity" would still pass the entries-first test
   * because entries fires first, but would fail this null-first variant.
   */
  @Test
  public void firstNullUnderflowSilencesSubsequentEntriesUnderflowOnSameEngine() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    List<LogRecord> captured = BTreeEngineTestFixtures.captureSevereOn(
        BTreeMultiValueIndexEngine.class,
        () -> {
          // First underflow: null counter, wins the latch, carries the stack.
          f.engine.addToApproximateNullCount(-7L);
          // Second underflow on the same engine, different mutator: shared
          // latch is already set, so this records the compact variant.
          f.engine.addToApproximateEntriesCount(-4L);
        });

    // Both counters clamped to 0.
    assertThat(BTreeEngineTestFixtures.readAtomicLong(f.engine, "approximateNullCount"))
        .isEqualTo(0L);
    assertThat(BTreeEngineTestFixtures.readAtomicLong(f.engine, "approximateIndexEntriesCount"))
        .isEqualTo(0L);

    var severeRecords = captured.stream()
        .filter(r -> r.getLevel() == Level.SEVERE)
        .toList();
    assertThat(severeRecords)
        .as("each underflow on the same engine must produce one SEVERE record"
            + " (captured=%s)", captured)
        .hasSize(2);
    assertThat(severeRecords.get(0).getMessage())
        .contains("approximateNullCount")
        .contains("delta=-7");
    assertThat(severeRecords.get(0).getThrown())
        .as("the first underflow on this engine must carry the stack trace")
        .isNotNull();
    assertThat(severeRecords.get(1).getMessage())
        .contains("approximateIndexEntriesCount")
        .contains("delta=-4");
    assertThat(severeRecords.get(1).getThrown())
        .as("subsequent underflows on the same engine must skip the stack trace")
        .isNull();
  }

  /**
   * Distinct engine instances each maintain their own
   * {@code firstUnderflowDumped} latch, so the first underflow on each engine
   * carries a stack trace. The test captures both engines' first underflow
   * records under the same handler so a regression where the latch is
   * conditionally not set on a fresh engine would manifest as a missing
   * stack-trace on the captured record — the older shape (handler installed
   * after the first engine's call) made the first engine's behavior
   * unobservable and could not distinguish "latch armed independently per
   * instance" from "latch never set for either engine".
   */
  @Test
  public void freshEngineInstanceHasArmedLatchIndependentOfOtherEngines() {
    List<LogRecord> captured = BTreeEngineTestFixtures.captureSevereOn(
        BTreeMultiValueIndexEngine.class,
        () -> {
          var first = new BTreeEngineTestFixtures.MultiValueFixture();
          first.engine.addToApproximateEntriesCount(-1L); // consume first's latch
          var second = new BTreeEngineTestFixtures.MultiValueFixture();
          second.engine.addToApproximateNullCount(-5L); // second's fresh latch
        });

    // Two SEVERE records, one per engine. Each must carry a non-null Throwable
    // because each engine has its own armed latch on construction.
    var severeRecords = captured.stream()
        .filter(r -> r.getLevel() == Level.SEVERE)
        .toList();
    assertThat(severeRecords)
        .as("each engine's first underflow must produce one SEVERE record"
            + " (captured=%s)", captured)
        .hasSize(2);
    // Discriminate the records by message content rather than position — the
    // calls were sequential on one thread but the assertion stays robust
    // against any future reordering of the fixture construction.
    var firstEngineRecord = severeRecords.stream()
        .filter(r -> r.getMessage().contains("delta=-1"))
        .findFirst()
        .orElse(null);
    var secondEngineRecord = severeRecords.stream()
        .filter(r -> r.getMessage().contains("delta=-5"))
        .findFirst()
        .orElse(null);
    assertThat(firstEngineRecord)
        .as("first engine's underflow record must be present (captured=%s)",
            captured)
        .isNotNull();
    assertThat(secondEngineRecord)
        .as("second engine's underflow record must be present (captured=%s)",
            captured)
        .isNotNull();
    assertThat(firstEngineRecord.getThrown())
        .as("first engine starts with its own armed latch; its first underflow"
            + " must carry a stack trace")
        .isNotNull();
    assertThat(secondEngineRecord.getThrown())
        .as("a freshly constructed engine instance starts with the latch unset"
            + " independent of other engines; its first underflow must carry"
            + " a stack trace even when an earlier engine already emitted one")
        .isNotNull();
    // The integration-level "latch resets across storage close + reopen"
    // claim is exercised by the storage cascade-containment test in a later
    // step of this track; here we cover only the in-process per-instance
    // independence contract.
  }

  /**
   * Drives the engine's {@code reportAndClampUnderflow} directly with a
   * counter whose live value differs from the {@code observedNegative}
   * argument, simulating a concurrent applier that already moved the
   * counter past the engine's stale observation between the
   * {@code addAndGet} that triggered the underflow detection and the
   * subsequent clamp CAS. The CAS {@code compareAndSet(observedNegative, 0)}
   * fails, and the counter must stay at the concurrent-writer value.
   *
   * <p>This pins the documented no-loop trade-off on the actual engine
   * method, not on a standalone {@link AtomicLong#compareAndSet(long, long)}
   * call: a refactor that introduced a clamp loop, replaced the CAS with
   * {@code counter.set(0)}, or changed the second argument away from 0
   * would all fail this assertion. {@code AtomicLong}'s arithmetic methods
   * are {@code final} (no subclass override possible); the engine method's
   * visibility was relaxed from {@code private} to package-private so the
   * test in this package can drive the failed-CAS branch deterministically.
   */
  @Test
  public void failedClampCasLeavesCounterAtConcurrentWriterValueThroughEnginePath() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    var counter = BTreeEngineTestFixtures.readAtomicLongRef(f.engine, "approximateNullCount");
    // Simulate a concurrent applier having advanced the counter to 7 between
    // the (hypothetical) addAndGet that returned -1 and the clamp CAS that
    // production code is about to execute. The engine's
    // reportAndClampUnderflow will call compareAndSet(-1, 0); with the live
    // value at 7 the CAS is a no-op.
    counter.set(7L);

    // Invoke the production method with a stale observed-negative value of
    // -1 while the live counter holds 7. reportAndClampUnderflow logs the
    // error (latch-armed first-time path) and then calls
    // compareAndSet(-1, 0), which fails because the live value is 7.
    List<LogRecord> captured = BTreeEngineTestFixtures.captureSevereOn(
        BTreeMultiValueIndexEngine.class,
        () -> f.engine.reportAndClampUnderflow(
            "approximateNullCount", counter, -1L, -1L));

    // Counter remains at the concurrent writer's value — the clamp CAS was
    // a no-op. A clamp loop would have forced this to 0 and masked the
    // legitimate concurrent decrement (here simulated by the seed to 7).
    assertThat(counter.get())
        .as("the counter must remain at the concurrent writer's value after a"
            + " failed clamp CAS inside reportAndClampUnderflow")
        .isEqualTo(7L);
    // The underflow was still observed by the engine — a SEVERE record fired,
    // confirming reportAndClampUnderflow ran and the failed CAS branch was
    // reached.
    assertThat(captured.stream().anyMatch(r -> r.getLevel() == Level.SEVERE))
        .as("the engine must still emit the underflow log even when the"
            + " clamp CAS fails (captured=%s)", captured)
        .isTrue();
  }

  /**
   * Variant of
   * {@link #failedClampCasLeavesCounterAtConcurrentWriterValueThroughEnginePath}
   * where the simulated concurrent writer's live value is itself negative
   * (-3, distinct from the engine's stale observed-negative -1). Pins the
   * "may stay negative under contention" sentence in the production Javadoc:
   * after a failed clamp CAS the counter is left alone, even if the live
   * value happens to also be negative.
   */
  @Test
  public void failedClampCasMayLeaveCounterNegativeUnderContention() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    var counter = BTreeEngineTestFixtures.readAtomicLongRef(f.engine, "approximateNullCount");
    counter.set(-3L);

    BTreeEngineTestFixtures.captureSevereOn(
        BTreeMultiValueIndexEngine.class,
        () -> f.engine.reportAndClampUnderflow(
            "approximateNullCount", counter, -1L, -1L));

    assertThat(counter.get())
        .as("under heavy contention the counter may stay negative after a"
            + " failed clamp CAS — the no-loop trade-off is intentional")
        .isEqualTo(-3L);
  }

  /**
   * Sanity check on the no-loop clamp CAS primitive in isolation: a failed
   * {@link AtomicLong#compareAndSet(long, long)} is a no-op that leaves the
   * counter at its live value. This pins the choice of CAS-not-set semantics
   * for the clamp; the
   * {@link #failedClampCasLeavesCounterAtConcurrentWriterValueThroughEnginePath}
   * test above pins the same contract through the actual engine method.
   */
  @Test
  public void clampCasContractIsCompareAndSetNotSetZero() {
    var counter = new AtomicLong();
    // Simulate a concurrent applier having moved the counter to 7 after the
    // (hypothetical) underflow observation. Production calls
    // compareAndSet(observedNegative, 0); here we test that the CAS branch
    // is a no-op when the field no longer matches the observed value.
    counter.set(7L);
    boolean swapped = counter.compareAndSet(-10L, 0L);
    assertThat(swapped)
        .as("the clamp CAS must be a no-op when the counter has moved away"
            + " from the observed-negative value")
        .isFalse();
    assertThat(counter.get())
        .as("the counter must remain at the concurrent writer's value after a"
            + " failed clamp CAS")
        .isEqualTo(7L);
  }

  /**
   * Drives both mutators concurrently across many threads coordinated by a
   * {@link CyclicBarrier} so all calls reach
   * {@code reportAndClampUnderflow} near-simultaneously. The shared
   * {@code firstUnderflowDumped} latch must let exactly one thread win and
   * emit the stack-trace variant; all other threads must emit the compact
   * variant. A regression that silently replaced the CAS with check-then-act
   * ({@code if (!firstUnderflowDumped.get()) { firstUnderflowDumped.set(true); ... }})
   * would allow more than one thread to log the stack trace under contention
   * and would fail this assertion. The serial cross-mutator test above
   * cannot catch that regression because its latch is uncontended.
   */
  @Test
  public void crossMutatorLatchUnderContentionEmitsExactlyOneStackTrace() throws Exception {
    final int threads = 16;
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    var captured = new CopyOnWriteArrayList<LogRecord>();
    var logger = Logger.getLogger(BTreeMultiValueIndexEngine.class.getName());
    var priorLevel = logger.getLevel();
    var handler = BTreeEngineTestFixtures.installCapturingHandler(logger, captured);
    logger.setLevel(Level.ALL);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      // Pre-warm the pool so the barrier budget below covers CAS contention
      // only and is not consumed by lazy thread creation. newFixedThreadPool
      // creates threads on first submit; on a loaded CI host with 4 parallel
      // surefire forks, that creation can exceed the per-thread barrier
      // budget and produce BrokenBarrierException on slow workers.
      CountDownLatch warmup = new CountDownLatch(threads);
      for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
          warmup.countDown();
          try {
            warmup.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }
      assertThat(warmup.await(10, TimeUnit.SECONDS))
          .as("thread pool must finish warm-up before the contention phase")
          .isTrue();

      var barrier = new CyclicBarrier(threads);
      var done = new CountDownLatch(threads);
      for (int i = 0; i < threads; i++) {
        // Half the threads hit addToApproximateEntriesCount, half hit
        // addToApproximateNullCount — both mutators share the same latch.
        final boolean entries = (i % 2) == 0;
        pool.submit(() -> {
          try {
            barrier.await(30, TimeUnit.SECONDS);
            if (entries) {
              f.engine.addToApproximateEntriesCount(-1L);
            } else {
              f.engine.addToApproximateNullCount(-1L);
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            done.countDown();
          }
        });
      }
      assertThat(done.await(15, TimeUnit.SECONDS))
          .as("all underflow threads must complete inside the budget")
          .isTrue();
    } finally {
      // Graceful shutdown so worker threads are confirmed dead before the
      // next test class runs in the same JVM. shutdownNow alone only sends
      // interrupts; a leaked worker holding a reference to the fixture's
      // engine would survive into a sibling test class.
      pool.shutdown();
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
        pool.shutdownNow();
        pool.awaitTermination(2, TimeUnit.SECONDS);
      }
      logger.removeHandler(handler);
      logger.setLevel(priorLevel);
    }

    var severeRecords = captured.stream()
        .filter(r -> r.getLevel() == Level.SEVERE)
        .toList();
    assertThat(severeRecords)
        .as("every contending underflow caller must produce a SEVERE record"
            + " (captured=%s)", captured)
        .hasSize(threads);
    long withStack = severeRecords.stream()
        .filter(r -> r.getThrown() != null)
        .count();
    long withoutStack = severeRecords.stream()
        .filter(r -> r.getThrown() == null)
        .count();
    assertThat(withStack)
        .as("the shared latch must let exactly one thread win the CAS and"
            + " emit the stack-trace variant — check-then-act would let"
            + " multiple threads through (withStack=%d withoutStack=%d)",
            withStack, withoutStack)
        .isEqualTo(1L);
    assertThat(withoutStack)
        .as("every other contending caller must record the compact variant"
            + " (withStack=%d withoutStack=%d)", withStack, withoutStack)
        .isEqualTo((long) threads - 1L);
  }

  /**
   * A {@code delta == 0} call is the steady-state quiescent commit shape on
   * engines that were not touched in the surrounding transaction. The
   * {@code if (updated < 0)} predicate must skip this case so the no-op
   * does not consume the shared latch — a future maintainer who refactored
   * through a wrapper that captured {@code delta} before the predicate
   * could silently drain the diagnostic latch on the first quiescent commit,
   * exhausting it before any real regression arrived. The test calls each
   * mutator with delta 0 on a fresh fixture, asserts no SEVERE records,
   * asserts both counters remain at 0, then forces a real underflow and
   * confirms the latch was still armed.
   */
  @Test
  public void zeroDeltaIsNoOpAndDoesNotConsumeLatch() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    var captured = new CopyOnWriteArrayList<LogRecord>();
    var logger = Logger.getLogger(BTreeMultiValueIndexEngine.class.getName());
    var priorLevel = logger.getLevel();
    var handler = BTreeEngineTestFixtures.installCapturingHandler(logger, captured);
    logger.setLevel(Level.ALL);
    try {
      f.engine.addToApproximateEntriesCount(0L);
      f.engine.addToApproximateNullCount(0L);

      // No log records for the no-op calls, and counters untouched.
      var preSevere = captured.stream()
          .filter(r -> r.getLevel() == Level.SEVERE)
          .toList();
      assertThat(preSevere)
          .as("zero-delta calls must not produce any SEVERE log record"
              + " (captured=%s)", captured)
          .isEmpty();
      assertThat(BTreeEngineTestFixtures.readAtomicLong(f.engine, "approximateIndexEntriesCount"))
          .isEqualTo(0L);
      assertThat(BTreeEngineTestFixtures.readAtomicLong(f.engine, "approximateNullCount"))
          .isEqualTo(0L);

      // Now force a real underflow; the latch must still be armed.
      f.engine.addToApproximateEntriesCount(-1L);
      var real = captured.stream()
          .filter(r -> r.getLevel() == Level.SEVERE)
          .findFirst()
          .orElse(null);
      assertThat(real)
          .as("the real underflow must still fire a SEVERE record after the"
              + " preceding no-op calls (captured=%s)", captured)
          .isNotNull();
      assertThat(real.getThrown())
          .as("the latch must remain armed across zero-delta no-ops, so the"
              + " first real underflow still carries the stack trace")
          .isNotNull();
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(priorLevel);
    }
  }

}
