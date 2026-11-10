package com.jetbrains.youtrackdb.benchmarks.pagelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class NearLimitWorkloadTest {

  private static final WriterSettings SETTINGS = new WriterSettings(80, 20, 8, 17, 1);

  @BeforeClass
  public static void settingsPrecedeEngineStartup() {
    SETTINGS.apply(4, 16);
  }

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  // The preparation CLI creates a one-writer seed before any other test starts the engine.
  @Test(timeout = 300000)
  public void aPreparationCliCreatesReusableOneWriterSeed() throws Exception {
    var root = folder.getRoot().toPath();
    NearLimitWorkload.main(new String[] {root.toString(), "1", "80", "20", "8", "17", "1"});
    var seed = NearLimitWorkload.prepare(root, 1, SETTINGS);
    var copy = seed.freshCopy(root);
    try (var db = (YouTrackDBImpl) YourTracks.instance(copy.toString());
        var session = db.open(NearLimitWorkload.DATABASE, "admin", "admin")) {
      var writer = new NearLimitWorkload(session.getStorage(), 0, SETTINGS);
      NearLimitWorkload.checkOwnership(new NearLimitWorkload[] {writer});
      assertEquals(16, writer.commit().length);
    } finally {
      SeedDatabase.deleteTree(copy);
    }
  }

  // Shapes include page size and writer count. Invalid capacity creates no seed directory.
  @Test
  public void seedIdentityAndCapacityPreconditions() throws Exception {
    var root = folder.getRoot().toPath();
    var path = NearLimitWorkload.seedPath(root, 4, SETTINGS);
    assertNotEquals(path, NearLimitWorkload.seedPath(root, 1, SETTINGS));
    assertNotEquals(path, NearLimitWorkload.seedPath(root, 4,
        new WriterSettings(80, 20, 16, 17, 1)));
    assertNotEquals(path, NearLimitWorkload.seedPath(root, 4,
        new WriterSettings(80, 20, 8, 18, 1)));
    var missing = root.resolve("must-not-exist");
    assertThrows(IllegalArgumentException.class, () -> NearLimitWorkload.prepare(missing, 4,
        new WriterSettings(80, 20, 8, 100, 0.1)));
    assertThrows(IllegalArgumentException.class,
        () -> NearLimitWorkload.prepare(missing, 2, SETTINGS));
    assertTrue(Files.notExists(missing));
    assertThrows(IllegalArgumentException.class, () -> NearLimitWorkload.main(new String[0]));
  }

  // Three disk lifecycles, with a five-minute deadline for slow Windows file I/O. Both sides
  // validate exact writes during setup. Readback asserts every coordinate, value and file owner.
  @Test(timeout = 300000)
  public void fourWritersChangeExactlyLimitMinusOnePagesOnIndependentCopies() throws Exception {
    var root = folder.getRoot().toPath();
    var seed = NearLimitWorkload.prepare(root, 4, SETTINGS);
    // Reusing the same closed seed must not regenerate or mutate it.
    NearLimitWorkload.prepare(root, 4, SETTINGS);
    boolean unavailable = Boolean.getBoolean("pagelock.expectUnavailable");
    for (int run = 0; run < 2; run++) {
      var copy = seed.freshCopy(root);
      try (var db = (YouTrackDBImpl) YourTracks.instance(copy.toString());
          var session = db.open(NearLimitWorkload.DATABASE, "admin", "admin")) {
        var storage = session.getStorage();
        var writers = new ArrayList<NearLimitWorkload>();
        for (int writer = 0; writer < 4; writer++) {
          writers.add(new NearLimitWorkload(storage, writer, SETTINGS));
        }
        var array = writers.toArray(NearLimitWorkload[]::new);
        NearLimitWorkload.checkOwnership(array);
        assertThrows(IllegalStateException.class, () -> NearLimitWorkload.checkOwnership(
            new NearLimitWorkload[] {array[0], array[0]}));
        var counters = new IterationCounters(new CounterProbes(storage), unavailable);
        counters.start(); // Preparation and validation commits are outside this boundary.
        var files = new java.util.HashSet<Long>();
        for (var writer : writers) {
          var samples = writer.commit();
          assertEquals(16, samples.length);
          assertTrue("each writer must change a different file", files.add(samples[0].fileId()));
          for (int i = 0; i < samples.length; i++) {
            assertEquals(i, samples[i].pageIndex());
            assertEquals(samples[0].fileId(), samples[i].fileId());
            assertEquals(2, samples[i].value());
          }
          writer.validate(); // The next changing value and exact touched set also validate.
        }
        var delta = counters.finish(true);
        assertEquals(unavailable ? "not available" : "8", delta.get("commit.PAGE_SET"));
        assertEquals(unavailable ? "not available" : "1.0",
            new WriterRunValidity().finish(counters));
        // A later bad measured iteration must not be hidden by earlier or later good ones.
        var aggregate = new IterationCounters(new CounterProbes(storage), unavailable);
        if (!unavailable) {
          assertThrows(IllegalStateException.class,
              () -> new WriterRunValidity().finish(aggregate));
          aggregate.add(Map.of("commit.PAGE_SET", "99", "commit.FORCED", "1",
              "commit.ZERO_CHANGE", "10000"));
          assertEquals("0.99", new WriterRunValidity().finish(aggregate));
          aggregate.add(Map.of("commit.FORCED", "1"));
          assertThrows(IllegalStateException.class,
              () -> new WriterRunValidity().finish(aggregate));
          aggregate.add(Map.of("commit.PAGE_SET", "1"));
          assertThrows(IllegalStateException.class,
              () -> new WriterRunValidity().finish(aggregate));
          // Branch read-back must reject a requested limit different from the real cache.
          assertThrows(IllegalStateException.class, () -> new NearLimitWorkload(storage, 0,
              new WriterSettings(80, 20, 8, 18, 1)));
        }
      } finally {
        SeedDatabase.deleteTree(copy);
      }
    }
  }

  // No retry, even for an Error. A later successful commit and a second failure cannot clear
  // or replace the original cause. This latch is shared by the trial, not by an iteration.
  @Test
  public void firstExceptionOrErrorStaysInvalidAfterSuccess() throws Exception {
    for (Throwable original : new Throwable[] {
        new IllegalStateException("commit failed"), new AssertionError("unexpected failure")}) {
      var validity = new WriterRunValidity();
      var attempts = new AtomicInteger();
      var escaped = assertThrows(Throwable.class, () -> validity.execute(() -> {
        attempts.incrementAndGet();
        if (original instanceof Error error) {
          throw error;
        }
        throw (Exception) original;
      }));
      assertEquals(original, escaped);
      assertEquals(1, attempts.get());
      validity.execute(attempts::incrementAndGet);
      validity.record(new IllegalArgumentException("later"));
      assertEquals(2, attempts.get());
      assertEquals(original, assertThrows(IllegalStateException.class,
          () -> validity.finish(null)).getCause());
      assertThrows(NullPointerException.class, () -> validity.record(null));
    }
  }

  // Aggregate measured mutating commits determine validity. ZERO_CHANGE never dilutes the share.
  // Boundary sampling is exercised against real storage above. Here exact threshold edges matter.
  @Test
  public void protocolShareRejectsEmptyOrBelowThresholdAndIgnoresZeroChange() {
    assertEquals("0.99", IterationCounters.iterationShare(Map.of(
        "commit.PAGE_SET", "99", "commit.FORCED", "1", "commit.ZERO_CHANGE", "10000")));
    assertEquals("not available", IterationCounters.iterationShare(Map.of(
        "commit.PAGE_SET", "0", "commit.ZERO_CHANGE", "10000")));
  }
}
