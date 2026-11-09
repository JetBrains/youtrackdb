package com.jetbrains.youtrackdb.benchmarks.pagelock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BenchmarkBaseTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private static AbstractStorage storage(YouTrackDBImpl db) {
    try (var session = db.open("pages", "admin", "admin")) {
      return session.getStorage();
    }
  }

  private static PageWorkloadComponent component(AbstractStorage storage, String name) {
    return new PageWorkloadComponent(storage, name, 5, true);
  }

  // A prepared seed is generated once. Every run opens a distinct copy with the original values.
  @Test
  public void closedSeedCopiesKeepRunsIndependent() throws Exception {
    Path root = folder.getRoot().toPath();
    AtomicInteger generated = new AtomicInteger();
    SeedDatabase.Generator generator = path -> {
      generated.incrementAndGet();
      try (var db = (YouTrackDBImpl) YourTracks.instance(path.toString())) {
        db.create("pages", DatabaseType.DISK, "admin", "admin", "admin");
        var storage = storage(db);
        component(storage, "left").create();
        component(storage, "right").create();
      }
    };
    var seed = SeedDatabase.prepare(root.resolve("seed"), generator);
    SeedDatabase.prepare(root.resolve("seed"), generator);
    assertEquals(1, generated.get());
    Path first = seed.freshCopy(root);
    Path second;
    try (var db = (YouTrackDBImpl) YourTracks.instance(first.toString())) {
      var storage = storage(db);
      var left = component(storage, "left");
      var right = component(storage, "right");
      left.open();
      right.open();
      var probes = new CounterProbes(storage);
      var before = probes.snapshot();
      assertEquals(17, before.size());
      boolean available = !Boolean.getBoolean("pagelock.expectUnavailable");
      before.forEach((name, value) -> {
        assertEquals(name, available, value.isPresent());
        System.out.println(name + "=" + (value.isPresent() ? "available" : "not available"));
      });

      int[] all = {0, 1, 2, 3, 4};
      // The first pass may warm the cache. A warm pass must succeed optimistically.
      left.read(all);
      var read = left.read(new int[] {4, 1});
      assertTrue("warm reads use the optimistic path", read.optimistic());
      assertPages(read.pages(), new int[] {4, 1}, new long[] {0, 0});
      long leftFile = read.pages()[0].fileId();
      var written = left.commit(new int[] {3, 1}, 42);
      assertPages(written, new int[] {3, 1}, new long[] {42, 42});
      assertEquals(leftFile, written[0].fileId());
      assertPages(left.read(all).pages(), all, new long[] {0, 42, 0, 42, 0});
      right.read(all);
      var other = right.read(all);
      assertTrue(other.optimistic());
      assertNotEquals(leftFile, other.pages()[0].fileId());
      assertPages(other.pages(), all, new long[] {0, 0, 0, 0, 0});
      assertThrows(IllegalArgumentException.class, () -> left.commit(new int[] {5}, 99));
      assertThrows(IllegalArgumentException.class, () -> left.read(new int[] {-1}));
      var after = probes.snapshot();
      var iteration = CounterProbes.iteration(before, after);
      if (available) {
        assertEquals("1", iteration.get("commit.PAGE_SET"));
      } else {
        iteration.values().forEach(value -> assertEquals("not available", value));
      }
      probes.enums("absent.", "missing.Counter", "Cause", null, "CAUSE");
      probes.scalar("absent.method", "", "missingMethod");
      probes.scalar("absent.target", null, "count");
      assertTrue(probes.snapshot().get("absent.CAUSE").isEmpty());
      assertTrue(probes.snapshot().get("absent.method").isEmpty());
      assertTrue(probes.snapshot().get("absent.target").isEmpty());
      assertThrows(IllegalArgumentException.class,
          () -> new PageWorkloadComponent(storage, "empty", 0, true));
      assertThrows(com.jetbrains.youtrackdb.internal.core.exception.StorageException.class,
          () -> new PageWorkloadComponent(storage, "left", 6, true).open());
    }
    second = seed.freshCopy(root);
    try (var files = Files.walk(second)) {
      var regularFiles = files.filter(Files::isRegularFile).toList();
      assertFalse(regularFiles.isEmpty());
      for (Path file : regularFiles) {
        assertFalse("run files must not be hard links",
            Files.isSameFile(root.resolve("seed").resolve(second.relativize(file)), file));
      }
    }
    try (var db = (YouTrackDBImpl) YourTracks.instance(second.toString())) {
      var left = component(storage(db), "left");
      left.open();
      assertPages(left.read(new int[] {1, 3}).pages(), new int[] {1, 3}, new long[] {0, 0});
    }
    SeedDatabase.deleteTree(first);
    SeedDatabase.deleteTree(second);
    assertFalse(Files.exists(first));
    assertFalse(Files.exists(second));
  }

  // A lifetime high-water mark must not be subtracted like a monotonic event counter.
  @Test
  public void iterationKeepsGaugeAndMissingValuesDistinct() {
    var before = Map.of("cache.largestExcess", OptionalLong.of(8),
        "events", OptionalLong.of(10), "missing", OptionalLong.empty());
    var after = Map.of("cache.largestExcess", OptionalLong.of(9),
        "events", OptionalLong.of(13), "missing", OptionalLong.empty());
    assertEquals(Map.of("cache.largestExcess", "9", "events", "3",
        "missing", "not available"), CounterProbes.iteration(before, after));
  }

  // A probe that resolves but fails when called must stop the run, not report zero or absence.
  @Test
  public void invocationFailureIsFatal() throws Exception {
    var parse = Long.class.getMethod("parseLong", String.class);
    assertThrows(IllegalStateException.class,
        () -> CounterProbes.invoke(parse, null, new Object[] {"not-a-number"}));
  }

  // A failed generator must leave no published seed. Retrying runs the generator again.
  @Test
  public void incompleteSeedIsNotPublished() throws Exception {
    Path seed = folder.getRoot().toPath().resolve("failed-seed");
    assertThrows(java.io.IOException.class, () -> SeedDatabase.prepare(seed, path -> {
      Files.writeString(path.resolve("partial"), "incomplete");
      throw new java.io.IOException("generator failed");
    }));
    assertFalse(Files.exists(seed));
    try (var files = Files.list(seed.getParent())) {
      assertEquals(0, files.count());
    }
    var ready =
        SeedDatabase.prepare(seed, path -> Files.writeString(path.resolve("data"), "closed"));
    Path copy = ready.freshCopy(seed.getParent());
    assertEquals("closed", Files.readString(copy.resolve("data")));
    SeedDatabase.deleteTree(copy);
  }

  private static void assertPages(PageWorkloadComponent.Sample[] samples, int[] pages,
      long[] values) {
    assertArrayEquals(pages, Arrays.stream(samples).mapToInt(
        PageWorkloadComponent.Sample::pageIndex).toArray());
    assertArrayEquals(values, Arrays.stream(samples).mapToLong(
        PageWorkloadComponent.Sample::value).toArray());
    assertEquals(1, Arrays.stream(samples).mapToLong(
        PageWorkloadComponent.Sample::fileId).distinct().count());
  }
}
