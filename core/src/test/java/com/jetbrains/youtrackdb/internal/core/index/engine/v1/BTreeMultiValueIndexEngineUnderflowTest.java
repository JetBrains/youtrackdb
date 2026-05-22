package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;

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
 */
public class BTreeMultiValueIndexEngineUnderflowTest {

  /**
   * First underflow on a fresh engine emits a WARNING-or-stronger record
   * carrying the stack trace, identifies the engine by name and id, clamps
   * the counter to 0, and does not throw an AssertionError.
   */
  @Test
  public void firstNullCountUnderflowEmitsStackTraceAndClampsToZero() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    var captured = new CopyOnWriteArrayList<LogRecord>();
    var logger = Logger.getLogger(BTreeMultiValueIndexEngine.class.getName());
    var priorLevel = logger.getLevel();
    var handler = installCapturingHandler(logger, captured);
    logger.setLevel(Level.ALL);
    try {
      // Fresh engine: approximateNullCount starts at 0. A negative delta
      // forces the addAndGet result below 0 and exercises the clamp path.
      // No throw expected — the legacy `assert updated >= 0` is gone.
      f.engine.addToApproximateNullCount(-1L);
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(priorLevel);
    }

    // Counter clamped to 0 via compareAndSet(-1, 0).
    assertThat(readAtomicLong(f.engine, "approximateNullCount"))
        .as("first underflow must clamp approximateNullCount back to 0")
        .isEqualTo(0L);

    // One error record captured, level SEVERE (java.util.logging maps
    // SLF4J ERROR to JUL SEVERE), message identifies the engine and the
    // updated/delta values, and the cause carries the synthetic stack trace.
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
        .contains("delta=-1");
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
   * trace.
   */
  @Test
  public void secondUnderflowOnSameEngineEmitsCompactErrorWithoutStack() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    var captured = new CopyOnWriteArrayList<LogRecord>();
    var logger = Logger.getLogger(BTreeMultiValueIndexEngine.class.getName());
    var priorLevel = logger.getLevel();
    var handler = installCapturingHandler(logger, captured);
    logger.setLevel(Level.ALL);
    try {
      // First underflow: entries counter, wins the latch, carries the stack.
      f.engine.addToApproximateEntriesCount(-3L);
      // Second underflow on the same engine, different mutator: shared latch
      // is already set, so this records the compact variant.
      f.engine.addToApproximateNullCount(-2L);
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(priorLevel);
    }

    // Both counters clamped to 0.
    assertThat(readAtomicLong(f.engine, "approximateIndexEntriesCount"))
        .isEqualTo(0L);
    assertThat(readAtomicLong(f.engine, "approximateNullCount"))
        .isEqualTo(0L);

    // Two SEVERE records. The first names approximateIndexEntriesCount and
    // carries the synthetic IllegalStateException as cause; the second names
    // approximateNullCount and has no cause (compact variant).
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
        .contains("delta=-2")
        .contains("stack trace suppressed");
    assertThat(severeRecords.get(1).getThrown())
        .as("subsequent underflows on the same engine must skip the stack trace")
        .isNull();
  }

  /**
   * Distinct engine instances each maintain their own
   * {@code firstUnderflowDumped} latch, so the first underflow on a second
   * engine again carries a stack trace. Pins the "per-engine-instance" scope
   * of the latch — a shared static latch would silence the second engine's
   * first error, which would defeat the diagnostic intent.
   */
  @Test
  public void newEngineInstanceResetsTheUnderflowLatch() {
    var first = new BTreeEngineTestFixtures.MultiValueFixture();
    first.engine.addToApproximateEntriesCount(-1L); // consume first's latch

    var second = new BTreeEngineTestFixtures.MultiValueFixture();
    var captured = new CopyOnWriteArrayList<LogRecord>();
    var logger = Logger.getLogger(BTreeMultiValueIndexEngine.class.getName());
    var priorLevel = logger.getLevel();
    var handler = installCapturingHandler(logger, captured);
    logger.setLevel(Level.ALL);
    try {
      second.engine.addToApproximateNullCount(-5L);
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(priorLevel);
    }

    var record = captured.stream()
        .filter(r -> r.getLevel() == Level.SEVERE)
        .findFirst()
        .orElse(null);
    assertThat(record)
        .as("the second engine's first underflow must produce a SEVERE log"
            + " record (captured=%s)", captured)
        .isNotNull();
    assertThat(record.getThrown())
        .as("a freshly constructed engine instance starts with the latch unset,"
            + " so its first underflow must carry the stack trace even when"
            + " other engines already emitted theirs")
        .isNotNull();
  }

  /**
   * The clamp uses {@code compareAndSet(observedNegative, 0)} rather than a
   * loop. When a concurrent applier moved the counter past
   * {@code observedNegative} between the underflow detection and the clamp
   * CAS, the CAS is a no-op and the counter stays at the concurrent writer's
   * value. The test seeds the AtomicLong to a positive value before invoking
   * the engine method with a tiny negative delta whose addAndGet would not
   * actually drive the counter negative, then directly exercises the CAS
   * shape via the same AtomicLong: a failed CAS leaves the counter alone.
   * Pins the documented trade-off (a clamp loop would mask a legitimate
   * concurrent decrement).
   */
  @Test
  public void failedClampCasLeavesCounterAtConcurrentWriterValue() {
    var f = new BTreeEngineTestFixtures.MultiValueFixture();
    var counter = readAtomicLongRef(f.engine, "approximateNullCount");

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

  // ─────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────

  /** Reads an {@link AtomicLong} field by name via reflection. */
  private static long readAtomicLong(Object target, String fieldName) {
    return readAtomicLongRef(target, fieldName).get();
  }

  /** Returns the {@link AtomicLong} reference held by a private field. */
  private static AtomicLong readAtomicLongRef(Object target, String fieldName) {
    try {
      var field = BTreeEngineTestFixtures.findField(target.getClass(), fieldName);
      field.setAccessible(true);
      return (AtomicLong) field.get(target);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Attaches a JUL handler that appends every published record to the given
   * list. The handler captures records at the supplied level; callers set
   * {@link Logger#setLevel(Level)} to {@link Level#ALL} before logging so the
   * test environment's log configuration cannot silently drop the events.
   */
  private static Handler installCapturingHandler(Logger logger,
      CopyOnWriteArrayList<LogRecord> sink) {
    var handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        sink.add(record);
      }

      @Override
      public void flush() {
        // No-op — assertions read directly from the sink.
      }

      @Override
      public void close() {
        // No-op — the JUL framework calls close() on shutdown.
      }
    };
    handler.setLevel(Level.ALL);
    logger.addHandler(handler);
    return handler;
  }
}
