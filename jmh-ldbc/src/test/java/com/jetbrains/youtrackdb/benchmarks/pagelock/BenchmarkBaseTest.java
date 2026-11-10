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
import java.io.IOException;
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

  // Force Java copying on every OS. Nested files must be separate files in both runs,
  // and changing or deleting a run file must leave the closed seed and the other run intact.
  @Test(timeout = 30000)
  public void javaSeedCopiesHaveIndependentFilesWithoutHardLinks() throws Exception {
    Path root = folder.getRoot().toPath();
    Path seedPath = root.resolve("java-seed");
    var seed = SeedDatabase.prepare(seedPath, path -> {
      Files.createDirectories(path.resolve("nested/empty"));
      Files.writeString(path.resolve("nested/data"), "closed");
      Files.writeString(path.resolve("top"), "original");
    });
    Path first = seed.freshCopy(root, false);
    Path second = seed.freshCopy(root, false);
    assertTrue(Files.isDirectory(first.resolve("nested/empty")));
    for (String name : new String[] {"nested/data", "top"}) {
      assertFalse("run file must not be a seed hard link",
          Files.isSameFile(seedPath.resolve(name), first.resolve(name)));
      assertFalse("second run file must not be a seed hard link",
          Files.isSameFile(seedPath.resolve(name), second.resolve(name)));
      assertFalse("runs must not share hard links",
          Files.isSameFile(first.resolve(name), second.resolve(name)));
      assertEquals(Files.readString(seedPath.resolve(name)),
          Files.readString(second.resolve(name)));
    }
    Files.writeString(first.resolve("nested/data"), "changed");
    Files.delete(first.resolve("top"));
    assertEquals("closed", Files.readString(seedPath.resolve("nested/data")));
    assertEquals("closed", Files.readString(second.resolve("nested/data")));
    assertEquals("original", Files.readString(seedPath.resolve("top")));
    assertEquals("original", Files.readString(second.resolve("top")));
    SeedDatabase.deleteTree(first);
    SeedDatabase.deleteTree(second);
  }

  // A real child JVM exits with an error or waits beyond the injected deadline. Both paths
  // must fail setup, reap the child, and remove its partial destination. Interruption does too.
  @Test(timeout = 30000)
  public void externalCopyFailuresStopChildrenAndRemovePartialCopies() throws Exception {
    Path root = folder.getRoot().toPath();
    var seed = SeedDatabase.prepare(root.resolve("seed"),
        path -> Files.writeString(path.resolve("data"), "closed"));
    var child = new Process[1];
    for (String mode : new String[] {"error", "wait", "interrupt"}) {
      Class<? extends Exception> expected = mode.equals("interrupt")
          ? InterruptedException.class : IOException.class;
      var failure = assertThrows(expected, () -> seed.freshCopy(root, copy -> {
        Files.writeString(copy.resolve("partial"), "incomplete");
        child[0] = child(mode.equals("error") ? "error" : "wait").start();
        if (mode.equals("interrupt")) {
          Thread.currentThread().interrupt();
        }
        return child[0];
      }, mode.equals("wait") ? 0 : 10));
      assertTrue(failure.getMessage(), mode.equals("interrupt")
          || failure.getMessage().contains(mode.equals("wait") ? "timed out" : "failed"));
      assertFalse("copier must be reaped before destination cleanup", child[0].isAlive());
      assertFalse("interruption is delivered to the caller", Thread.interrupted());
      try (var files = Files.list(root)) {
        assertEquals("no partial run directory survives", 1, files.count());
      }
    }
  }

  // Detection must skip non-Linux hosts, reject missing/failed probes and preserve interruption.
  @Test(timeout = 30000)
  public void reflinkProbeFallsBackWhenUnavailableOrInterrupted() throws Exception {
    var missing = new ProcessBuilder(folder.getRoot().toPath().resolve("no-cp").toString());
    assertFalse(SeedDatabase.reflinkCopyAvailable("Windows", missing));
    assertFalse(SeedDatabase.reflinkCopyAvailable("Linux", missing));
    assertFalse(SeedDatabase.reflinkCopyAvailable("Linux", child("error")));
    Thread.currentThread().interrupt();
    try {
      assertFalse(SeedDatabase.reflinkCopyAvailable("Linux", child("wait")));
      assertTrue("probe preserves interruption", Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static ProcessBuilder child(String mode) {
    return new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-cp", System.getProperty("java.class.path"), CopyProcess.class.getName(), mode);
  }

  public static class CopyProcess {

    public static void main(String[] args) throws Exception {
      if (args[0].equals("wait")) {
        Thread.sleep(60000);
      }
      System.exit(7);
    }
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
