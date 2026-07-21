package com.jetbrains.youtrackdb.benchmarks.epoch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Smoke test for the YTDB-1203 epoch ceiling benchmark harness: builds the benchmark
 * state with a tiny record count and executes every benchmark method body once,
 * verifying that reads return data, writes commit, and no anomalies are recorded on a
 * sound (baseline) build. This guards the harness against bit-rot without running JMH.
 */
public class EpochReadWriteBenchmarkSmokeTest {

  private static final int RECORD_COUNT = 200;

  private static Path tempDir;
  private static EpochReadWriteState state;
  private static final EpochReadWriteBenchmark benchmark = new EpochReadWriteBenchmark();

  @BeforeClass
  public static void setUpClass() throws IOException {
    tempDir = Files.createTempDirectory("epoch-bench-smoke");
    System.setProperty("epoch.db.path", tempDir.toString());

    state = new EpochReadWriteState();
    state.recordCount = RECORD_COUNT;
    state.setup();
  }

  @AfterClass
  public static void tearDownClass() throws IOException {
    System.clearProperty("epoch.db.path");
    if (state != null) {
      state.tearDown();
    }
    if (tempDir != null && Files.exists(tempDir)) {
      try (var paths = Files.walk(tempDir)) {
        paths.sorted(Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.delete(p);
          } catch (IOException e) {
            // best-effort cleanup of the temp DB directory
          }
        });
      }
    }
  }

  @Test
  public void testReadByRidReturnsPayload() {
    // A RID point read on a sound build must return the record's payload and must not
    // record any swallowed anomaly.
    var counters = new EpochReadWriteBenchmark.ReaderCounters();
    Object payload = benchmark.readOnlyByRid(state, counters);
    assertNotNull("RID point read must return a payload", payload);
    assertEquals("no anomalies may be swallowed on a sound build",
        0, counters.readAnomalies);
  }

  @Test
  public void testReadByIndexReturnsRow() {
    // An index point get on a sound build must return exactly one row (keys are unique
    // and all keys 0..N-1 exist) and must not record any swallowed anomaly.
    var counters = new EpochReadWriteBenchmark.ReaderCounters();
    Object result = benchmark.readOnlyByIndex(state, counters);
    assertNotNull("index point read must return a result list", result);
    assertEquals("index point read must find exactly one record",
        1, ((List<?>) result).size());
    assertEquals("no anomalies may be swallowed on a sound build",
        0, counters.readAnomalies);
  }

  @Test
  public void testWriteCommitSucceeds() {
    // A single-threaded writer commit must succeed without conflicts.
    var counters = new EpochReadWriteBenchmark.WriterCounters();
    benchmark.writeOnlyCommit(state, counters);
    assertEquals("single-threaded write must not conflict", 0, counters.writeConflicts);
  }

  @Test
  public void testMixedGroupMethodBodies() {
    // The mixed-group methods share the same operation bodies as the plain benchmarks;
    // execute each once to make sure the group entry points are wired correctly.
    var readerCounters = new EpochReadWriteBenchmark.ReaderCounters();
    var writerCounters = new EpochReadWriteBenchmark.WriterCounters();

    assertNotNull(benchmark.mixedRid_read(state, readerCounters));
    benchmark.mixedRid_write(state, writerCounters);
    assertNotNull(benchmark.mixedIndex_read(state, readerCounters));
    benchmark.mixedIndex_write(state, writerCounters);

    assertEquals(0, readerCounters.readAnomalies);
    assertEquals(0, writerCounters.writeConflicts);
    assertEquals("state-level anomaly counter must stay zero on a sound build",
        0, state.swallowedReadAnomalies.sum());
  }

  @Test
  public void testIterationCounterHooksDoNotThrow() {
    // The iteration-level snapshot/print hooks must be callable in any order without
    // touching the database — they only read diagnostic counters.
    state.snapshotCounters();
    state.printCounterDeltas();
  }
}
