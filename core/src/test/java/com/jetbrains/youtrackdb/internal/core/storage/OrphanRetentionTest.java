package com.jetbrains.youtrackdb.internal.core.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OrphanRetentionTest {
  private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.006Z");
  private static final Instant EXPIRES = CREATED.plusSeconds(180L * 86400);
  private static final String SUFFIX = "-0123456789abcdef0123456789abcdef";
  private static final String OLD = "20260102T030405006Z" + SUFFIX;

  @Rule
  public TemporaryFolder temporary = new TemporaryFolder();

  private Path storage() {
    return temporary.getRoot().toPath();
  }

  private Path root() throws IOException {
    return Files.createDirectories(storage().resolve(StorageFileNames.ORPHANS_DIRECTORY));
  }

  private Path folder(String name) throws IOException {
    return Files.createDirectory(root().resolve(name));
  }

  private OrphanRetention pass(Instant now) {
    return new OrphanRetention(Clock.fixed(now, ZoneOffset.UTC), () -> 0, () -> false);
  }

  private void requireSecureDirectories() throws IOException {
    try (var entries = Files.newDirectoryStream(storage())) {
      final boolean secure = entries instanceof SecureDirectoryStream<?>;
      if (System.getProperty("os.name").equals("Linux")) {
        assertTrue("Linux CI must exercise secure directory substitution tests", secure);
      }
      org.junit.Assume.assumeTrue(
          "This filesystem provider has no SecureDirectoryStream. Linux CI exercises these races.",
          secure);
    }
  }

  private void assertOpenSubstitutionSafe(Path replaced, Path target, String current)
      throws Exception {
    final var kept = Files.writeString(target.resolve("keep"), "valuable");
    final var parked = storage().resolve("parked");
    final var substituted = new AtomicBoolean();
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        if (path.equals(replaced)) {
          assertTrue(parent != null);
          Files.move(path, parked);
          symlink(path, target);
          substituted.set(true);
        }
        return super.openDirectory(path, parent);
      }
    };
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      retention.run(storage(), current, 180);
      assertTrue(substituted.get());
      assertEquals("valuable", Files.readString(kept));
      assertTrue(Files.isSymbolicLink(replaced));
      assertTrue(logs.warnedWithAll("failed", replaced.toString()));
    }
  }

  /** A nested directory swapped for a link to the protected current folder is never entered. */
  @Test
  public void nestedDirectorySubstitutionDoesNotEnterCurrentFolder() throws Exception {
    requireSecureDirectories();
    final var currentName = OLD.replace("006Z", "007Z");
    final var current = folder(currentName);
    final var nested = Files.createDirectory(folder(OLD).resolve("nested"));
    assertOpenSubstitutionSafe(nested, current, currentName);
  }

  /** A candidate swapped after classification cannot redirect the open outside orphans. */
  @Test
  public void candidateSubstitutionDoesNotEnterOutsideFolder() throws Exception {
    requireSecureDirectories();
    final var expired = folder(OLD);
    final var outside = temporary.newFolder("outside").toPath();
    assertOpenSubstitutionSafe(expired, outside, null);
  }

  /** A root swapped after its metadata check cannot expose an expired folder in its link target. */
  @Test
  public void rootSubstitutionDoesNotEnterOutsideFolder() throws Exception {
    requireSecureDirectories();
    final var root = root();
    final var outside = temporary.newFolder("outside").toPath();
    final var outsideExpired = Files.createDirectory(outside.resolve(OLD));
    final var data = Files.writeString(outsideExpired.resolve("data"), "valuable");
    assertOpenSubstitutionSafe(root, outside, null);
    assertEquals("valuable", Files.readString(data));
  }

  /** Replacing an already-open root cannot redirect child metadata, opens or deletion. */
  @Test
  public void openedRootRemainsAnchoredAfterSubstitution() throws Exception {
    requireSecureDirectories();
    final var expired = folder(OLD);
    Files.writeString(expired.resolve("data"), "quarantine");
    final var outside = temporary.newFolder("outside").toPath();
    final var outsideExpired = Files.createDirectory(outside.resolve(OLD));
    final var kept = Files.writeString(outsideExpired.resolve("data"), "valuable");
    final var parked = storage().resolve("parked");
    final var substituted = new AtomicBoolean();
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        if (path.equals(expired) && substituted.compareAndSet(false, true)) {
          assertTrue(parent != null);
          Files.move(path.getParent(), parked);
          symlink(path.getParent(), outside);
        }
        return super.readAttributes(path, parent);
      }
    };
    retention.run(storage(), null, 180);
    assertTrue(substituted.get());
    assertFalse(Files.exists(parked.resolve(OLD)));
    assertEquals("valuable", Files.readString(kept));
  }

  /** A directory swapped for a link just before unlink cannot delete its target contents. */
  @Test
  public void directorySubstitutionBeforeDeleteKeepsTarget() throws Exception {
    requireSecureDirectories();
    final var expired = folder(OLD);
    final var outside = temporary.newFolder("outside").toPath();
    final var kept = Files.writeString(outside.resolve("keep"), "valuable");
    final var substituted = new AtomicBoolean();
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory)
          throws IOException {
        if (path.equals(expired)) {
          assertTrue(parent != null);
          assertTrue(directory);
          Files.move(path, storage().resolve("parked"));
          symlink(path, outside);
          substituted.set(true);
        }
        super.deleteEntry(path, parent, directory);
      }
    };
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      retention.run(storage(), null, 180);
      assertTrue(substituted.get());
      assertTrue(Files.isSymbolicLink(expired));
      assertEquals("valuable", Files.readString(kept));
      assertTrue(logs.warnedWithAll("failed", expired.toString()));
    }
  }

  /** Providers without secure streams still complete the path-based postorder walk. */
  @Test
  public void ordinaryDirectoryStreamFallback() throws Exception {
    final var expired = folder(OLD);
    Files.writeString(Files.createDirectory(expired.resolve("nested")).resolve("data"), "data");
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      DirectoryStream<Path> openStorage(Path path) throws IOException {
        final var entries = super.openStorage(path);
        return new DirectoryStream<>() {
          @Override
          public Iterator<Path> iterator() {
            return entries.iterator();
          }

          @Override
          public void close() throws IOException {
            entries.close();
          }
        };
      }

      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        assertNull(parent);
        return super.readAttributes(path, parent);
      }
    };
    retention.run(storage(), null, 180);
    assertFalse(Files.exists(expired));
  }

  /** The registry exposes the requested integer setting, key, and default without mutation. */
  @Test
  public void settingMetadata() {
    final var setting = GlobalConfiguration.STORAGE_ORPHANS_RETENTION_DAYS;
    assertEquals("youtrackdb.storage.orphans.retentionDays", setting.getKey());
    assertEquals(Integer.class, setting.getType());
    assertEquals(180, setting.getDefValue());
  }

  /** Exact millisecond expiry deletes, but one millisecond before it preserves the same folder. */
  @Test
  public void expiryUsesNameNotModificationTime() throws Exception {
    final var expired = folder(OLD);
    Files.writeString(expired.resolve("data"), "quarantine");
    Files.setLastModifiedTime(expired, FileTime.from(EXPIRES.plusSeconds(86400)));
    pass(EXPIRES.minusMillis(1)).run(storage(), null, 180);
    assertTrue(Files.exists(expired));
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      pass(EXPIRES).run(storage(), null, 180);
      assertFalse(Files.exists(expired));
      assertTrue(Files.isDirectory(root()));
      assertEquals(1, logs.messages().size());
      assertTrue(logs.warnedWithAll("Deleted", OLD, "1 files"));
    }
  }

  /** Leap day and strict name syntax agree with the shared UTC producer formatter. */
  @Test
  public void strictGrammar() {
    assertEquals(CREATED, OrphanRetention.folderTime(OLD));
    final var leap = Instant.parse("2024-02-29T23:59:59.999Z");
    assertEquals(leap, OrphanRetention.folderTime(
        StorageFileNames.QUARANTINE_TIMESTAMP.format(leap) + SUFFIX));
    final var invalid = List.of(
        OLD.toUpperCase(java.util.Locale.ROOT),
        OLD.substring(0, OLD.length() - 1),
        OLD + "a",
        OLD + ".extra",
        OLD.replace("Z-", "-"),
        "20261302T030405006Z" + SUFFIX,
        "20260230T030405006Z" + SUFFIX,
        "20260229T030405006Z" + SUFFIX,
        "20260102T240000000Z" + SUFFIX,
        "20260102T036005006Z" + SUFFIX,
        "20260102T030460006Z" + SUFFIX,
        OLD.replace('T', 't'),
        "２０２６0102T030405006Z" + SUFFIX);
    for (var name : invalid) {
      assertNull(name, OrphanRetention.folderTime(name));
    }
  }

  /** Malformed directories, validly named plain files, and the exact current folder survive. */
  @Test
  public void candidateProtection() throws Exception {
    final var current = folder(OLD);
    final var nested = Files.createDirectories(root().resolve("unrecognized").resolve(OLD));
    final var file = Files.writeString(root().resolve(OLD.replace("006Z", "007Z")), "not a dir");
    for (var name : List.of(OLD + "a",
        OLD.replace("006Z", "008Z").toUpperCase(java.util.Locale.ROOT),
        "20260230T030405006Z" + SUFFIX, OLD + ".extra")) {
      folder(name);
    }
    pass(EXPIRES.plusSeconds(100)).run(storage(), OLD, 180);
    assertTrue(Files.isDirectory(current));
    assertTrue(Files.isDirectory(nested));
    assertTrue(Files.isRegularFile(file));
    try (var entries = Files.list(root())) {
      assertEquals(7, entries.count());
    }
  }

  /** A future timestamp warns once each run, survives, and eventually expires at its own boundary. */
  @Test
  public void futureFolderWarningAndExpiry() throws Exception {
    final var path = folder(OLD);
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      pass(CREATED.minusMillis(1)).run(storage(), null, 1);
      assertTrue(Files.exists(path));
      assertEquals(1, logs.messages().size());
      assertTrue(logs.warnedWithAll("future timestamp", OLD));
      pass(CREATED.minusMillis(1)).run(storage(), null, 1);
      assertEquals(2, logs.messages().size());
    }
    pass(CREATED.plusSeconds(86400)).run(storage(), null, 1);
    assertFalse(Files.exists(path));
  }

  /** Disabled retention does no I/O, and maximum integer days cannot wrap into an early expiry. */
  @Test
  public void disabledAndHugeRetention() throws Exception {
    final var path = folder(OLD);
    final var ops = new AtomicInteger();
    for (int days : new int[] {0, -1, Integer.MIN_VALUE}) {
      final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
          () -> 0, () -> false) {
        @Override
        BasicFileAttributes readAttributes(Path entry, SecureDirectoryStream<Path> parent)
            throws IOException {
          ops.incrementAndGet();
          return super.readAttributes(entry, parent);
        }
      };
      retention.run(storage(), null, days);
    }
    assertEquals(0, ops.get());
    pass(EXPIRES).run(storage(), null, Integer.MAX_VALUE);
    assertTrue(Files.exists(path));
    pass(CREATED.plus(Integer.MAX_VALUE, java.time.temporal.ChronoUnit.DAYS))
        .run(storage(), null, Integer.MAX_VALUE);
    assertFalse(Files.exists(path));
  }

  /** Validly named root children that are links are not candidate directories. */
  @Test
  public void candidateLinkIsKept() throws Exception {
    final var outside = temporary.newFolder("outside").toPath();
    final var kept = Files.writeString(outside.resolve("keep"), "valuable");
    final var link = root().resolve(OLD);
    symlink(link, outside);
    pass(EXPIRES).run(storage(), null, 180);
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("valuable", Files.readString(kept));
  }

  /** Special/reparse attributes reject the root even when the provider also reports a directory. */
  @Test
  public void specialRootIsRejected() {
    final var special = org.mockito.Mockito.mock(BasicFileAttributes.class);
    org.mockito.Mockito.when(special.isDirectory()).thenReturn(true);
    org.mockito.Mockito.when(special.isOther()).thenReturn(true);
    final var retention = new OrphanRetention(Clock.systemUTC(), () -> 0, () -> false) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent) {
        return special;
      }

      @Override
      DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent) {
        throw new AssertionError("special root must not be listed");
      }
    };
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      retention.run(storage(), null, 180);
      assertTrue(logs.warnedWithAll("rejected", "orphans"));
    }
  }

  /** Expiration of the budget during one delete allows that delete, but no following operation. */
  @Test
  public void deletionDeadline() throws Exception {
    final var expired = folder(OLD);
    Files.writeString(expired.resolve("data"), "data");
    final var nanos = new AtomicLong(Long.MAX_VALUE - 100);
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        nanos::get, () -> false) {
      @Override
      void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory)
          throws IOException {
        super.deleteEntry(path, parent, directory);
        // nanoTime wraparound must preserve elapsed-time arithmetic.
        nanos.addAndGet(OrphanRetention.RUN_LIMIT_NANOS);
      }

      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        assertTrue(nanos.get() > 0);
        return super.readAttributes(path, parent);
      }
    };
    retention.run(storage(), null, 180);
    assertTrue(Files.isDirectory(expired));
    assertFalse(Files.exists(expired.resolve("data")));
  }

  /** A stop discovered after metadata admission prevents the following directory open. */
  @Test
  public void stopAfterRootMetadata() throws Exception {
    root();
    final var stopped = new AtomicBoolean();
    final var retention = new OrphanRetention(Clock.systemUTC(), () -> 0, stopped::get) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        final var attributes = super.readAttributes(path, parent);
        stopped.set(true);
        return attributes;
      }

      @Override
      DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent) {
        throw new AssertionError("directory open after stop");
      }
    };
    retention.run(storage(), null, 180);
    assertTrue(stopped.get());
  }

  /** Root check and root listing failures each emit one warning rather than escaping. */
  @Test
  public void rootFailuresWarn() throws Exception {
    root();
    for (boolean failRead : List.of(true, false)) {
      final var retention = new OrphanRetention(Clock.systemUTC(), () -> 0, () -> false) {
        @Override
        BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
            throws IOException {
          if (failRead) {
            throw new IOException("injected metadata failure");
          }
          return super.readAttributes(path, parent);
        }

        @Override
        DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
            throws IOException {
          throw new IOException("injected listing failure");
        }
      };
      try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
        retention.run(storage(), null, 180);
        assertEquals(1, logs.messages().size());
        assertTrue(logs.warnedWithAll("failed", "orphans"));
      }
    }
  }

  /** A child that disappears before its metadata read is skipped without a failure warning. */
  @Test
  public void candidateDisappears() throws Exception {
    final var expired = folder(OLD);
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        if (path.equals(expired)) {
          Files.delete(path);
        }
        return super.readAttributes(path, parent);
      }
    };
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      retention.run(storage(), null, 180);
      assertFalse(Files.exists(expired));
      assertTrue(logs.messages().isEmpty());
    }
  }

  private static void symlink(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
      if (System.getProperty("os.name").equals("Linux")) {
        throw new AssertionError("Symbolic links must be available on Linux", unavailable);
      }
      assumeNoException("Symbolic links unavailable", unavailable);
    }
  }

  /** Directory, file, and dangling links are removed as entries without reading their targets. */
  @Test
  public void linksInsideExpiredFolderAreLeaves() throws Exception {
    final var outside = temporary.newFolder("outside").toPath();
    final var kept = Files.writeString(outside.resolve("keep"), "valuable");
    final var expired = folder(OLD);
    symlink(expired.resolve("directory-link"), outside);
    symlink(expired.resolve("file-link"), kept);
    symlink(expired.resolve("dangling"), outside.resolve("absent"));
    Files.createDirectories(expired.resolve("nested/deep"));
    Files.writeString(expired.resolve("nested/deep/data"), "quarantine");
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      pass(EXPIRES).run(storage(), null, 180);
      assertFalse(Files.exists(expired));
      assertEquals("valuable", Files.readString(kept));
      assertTrue(logs.warnedWithAll(OLD, "4 files"));
    }
  }

  /** A root link is rejected before listing and cannot delete expired folders in its target. */
  @Test
  public void linkedRootIsRejected() throws Exception {
    final var outside = temporary.newFolder("outside").toPath();
    final var kept = Files.createDirectory(outside.resolve(OLD));
    symlink(storage().resolve("orphans"), outside);
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      pass(EXPIRES).run(storage(), null, 180);
      assertTrue(Files.isDirectory(kept));
      assertEquals(1, logs.messages().size());
      assertTrue(logs.warnedWithAll("rejected", "orphans"));
    }
  }

  /** A missing root is normal, while a regular file at that path is rejected with a warning. */
  @Test
  public void missingAndRegularFileRoot() throws Exception {
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      pass(EXPIRES).run(storage(), null, 180);
      assertTrue(logs.messages().isEmpty());
      final var path = Files.writeString(storage().resolve("orphans"), "keep");
      pass(EXPIRES).run(storage(), null, 180);
      assertEquals("keep", Files.readString(path));
      assertTrue(logs.warnedWithAll("rejected", "orphans"));
    }
  }

  /** A simulated process crash leaves a partial folder that a later pass finishes normally. */
  @Test
  public void crashThenRetry() throws Exception {
    final var expired = folder(OLD);
    Files.writeString(expired.resolve("one"), "one");
    Files.writeString(expired.resolve("two"), "two");
    final var crashed = new AtomicBoolean();
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      void deleteEntry(Path entry, SecureDirectoryStream<Path> parent, boolean directory)
          throws IOException {
        super.deleteEntry(entry, parent, directory);
        crashed.set(true);
        throw new AssertionError("simulated crash");
      }
    };
    assertThrows(AssertionError.class, () -> retention.run(storage(), null, 180));
    assertTrue(crashed.get());
    try (var entries = Files.list(expired)) {
      assertEquals(1, entries.count());
    }
    pass(EXPIRES).run(storage(), null, 180);
    assertFalse(Files.exists(expired));
  }

  /** Stop raised by one deletion forbids every subsequent entry operation, then retry completes. */
  @Test
  public void stopBetweenOperations() throws Exception {
    final var expired = folder(OLD);
    Files.writeString(expired.resolve("one"), "one");
    Files.writeString(expired.resolve("two"), "two");
    final var stopped = new AtomicBoolean();
    final var deleted = new AtomicInteger();
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, stopped::get) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        assertFalse(stopped.get());
        return super.readAttributes(path, parent);
      }

      @Override
      DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        assertFalse(stopped.get());
        return super.openDirectory(path, parent);
      }

      @Override
      void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory)
          throws IOException {
        assertFalse(stopped.get());
        super.deleteEntry(path, parent, directory);
        deleted.incrementAndGet();
        stopped.set(true);
      }
    };
    retention.run(storage(), null, 180);
    assertEquals(1, deleted.get());
    assertTrue(Files.isDirectory(expired));
    pass(EXPIRES).run(storage(), null, 180);
    assertFalse(Files.exists(expired));
  }

  /** A run already stopped or already out of time must not even inspect the root. */
  @Test
  public void budgetIncludesRootCheck() {
    final var calls = new AtomicInteger();
    final var retention = new OrphanRetention(Clock.systemUTC(),
        () -> calls.getAndIncrement() == 0 ? 0 : OrphanRetention.RUN_LIMIT_NANOS,
        () -> false) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent) {
        throw new AssertionError("root operation after deadline");
      }
    };
    retention.run(storage(), null, 180);
    assertEquals(2, calls.get());
    new OrphanRetention(Clock.systemUTC(), () -> 0, () -> true) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent) {
        throw new AssertionError("root operation after stop");
      }
    }.run(storage(), null, 180);
  }

  /** Even an all-unexpired listing is bounded, including iterator reads and classification. */
  @Test
  public void largeUnexpiredListingTimesOut() throws Exception {
    final var root = root();
    final var names = new ArrayList<Path>();
    for (int i = 0; i < 10_000; i++) {
      names.add(root.resolve("20260102T030405006Z-" + String.format("%032x", i)));
    }
    final var ticks = new AtomicLong();
    final var inspected = new AtomicInteger();
    final var listed = new AtomicInteger();
    final var retention = new OrphanRetention(Clock.fixed(CREATED, ZoneOffset.UTC),
        () -> ticks.getAndAdd(1_000_000_000L), () -> false) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        assertTrue(ticks.get() <= OrphanRetention.RUN_LIMIT_NANOS);
        inspected.incrementAndGet();
        return super.readAttributes(root, parent);
      }

      @Override
      DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent) {
        return new DirectoryStream<>() {
          @Override
          public Iterator<Path> iterator() {
            final var iterator = names.iterator();
            return new Iterator<>() {
              @Override
              public boolean hasNext() {
                assertTrue(ticks.get() <= OrphanRetention.RUN_LIMIT_NANOS);
                return iterator.hasNext();
              }

              @Override
              public Path next() {
                assertTrue(ticks.get() <= OrphanRetention.RUN_LIMIT_NANOS);
                listed.incrementAndGet();
                return iterator.next();
              }
            };
          }

          @Override
          public void close() {
          }
        };
      }
    };
    retention.run(storage(), null, 180);
    assertTrue(inspected.get() > 1);
    assertTrue(listed.get() < names.size());
    assertEquals(OrphanRetention.RUN_LIMIT_NANOS + 1_000_000_000L, ticks.get());
  }

  /** Missing children during classification or deletion are quiet and do not block siblings. */
  @Test
  public void concurrentMissingEntries() throws Exception {
    final var expired = folder(OLD);
    final var atRead = Files.writeString(expired.resolve("at-read"), "data");
    final var atDelete = Files.writeString(expired.resolve("at-delete"), "data");
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        if (path.equals(atRead)) {
          Files.delete(path);
        }
        return super.readAttributes(path, parent);
      }

      @Override
      void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory)
          throws IOException {
        if (path.equals(atDelete)) {
          Files.delete(path);
        }
        super.deleteEntry(path, parent, directory);
      }
    };
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      retention.run(storage(), null, 180);
      assertFalse(Files.exists(expired));
      assertEquals(1, logs.messages().size());
      assertTrue(logs.warnedWithAll("Deleted", OLD));
    }
  }

  /** Failure to inspect or open one candidate does not prevent deletion of another candidate. */
  @Test
  public void candidateFailuresDoNotBlockSiblings() throws Exception {
    final var badRead = folder(OLD);
    final var badOpen = folder(OLD.replace("006Z", "007Z"));
    final var good = folder(OLD.replace("006Z", "008Z"));
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES.plusSeconds(1), ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        if (path.equals(badRead)) {
          throw new IOException("injected classification failure");
        }
        return super.readAttributes(path, parent);
      }

      @Override
      DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
          throws IOException {
        if (path.equals(badOpen)) {
          throw new IOException("injected candidate listing failure");
        }
        return super.openDirectory(path, parent);
      }
    };
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      retention.run(storage(), null, 180);
      assertTrue(Files.isDirectory(badRead));
      assertTrue(Files.isDirectory(badOpen));
      assertFalse(Files.exists(good));
      assertEquals(3, logs.messages().size());
      assertTrue(logs.warnedWithAll("failed", badRead.toString()));
      assertTrue(logs.warnedWithAll("failed", badOpen.toString()));
      assertTrue(logs.warnedWithAll("Deleted", good.toString()));
    }
  }

  /** Iterator I/O errors warn once for either the root or an expired subfolder and release handles. */
  @Test
  public void iteratorFailuresWarnAndClose() throws Exception {
    final var expired = folder(OLD);
    for (var failedPath : List.of(root(), expired)) {
      final var closed = new AtomicBoolean();
      final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
          () -> 0, () -> false) {
        @Override
        DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
            throws IOException {
          if (!path.equals(failedPath)) {
            return super.openDirectory(path, parent);
          }
          return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
              return new Iterator<>() {
                @Override
                public boolean hasNext() {
                  throw new java.nio.file.DirectoryIteratorException(
                      new IOException("injected iterator failure"));
                }

                @Override
                public Path next() {
                  throw new AssertionError("next after failed hasNext");
                }
              };
            }

            @Override
            public void close() {
              closed.set(true);
            }
          };
        }
      };
      try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
        retention.run(storage(), null, 180);
        assertTrue(closed.get());
        assertTrue(Files.isDirectory(expired));
        assertEquals(1, logs.messages().size());
        assertTrue(logs.warnedWithAll("failed", failedPath.toString()));
      }
    }
  }

  /** An entry I/O failure warns, leaves that entry, and still deletes independent siblings. */
  @Test
  public void entryFailureContinuesAndRetryCompletes() throws Exception {
    final var expired = folder(OLD);
    final var bad = Files.writeString(expired.resolve("bad"), "data");
    final var good = Files.writeString(expired.resolve("good"), "data");
    final var retention = new OrphanRetention(Clock.fixed(EXPIRES, ZoneOffset.UTC),
        () -> 0, () -> false) {
      @Override
      void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory)
          throws IOException {
        if (path.equals(bad)) {
          throw new IOException("injected entry failure");
        }
        super.deleteEntry(path, parent, directory);
      }
    };
    try (var logs = LogRecordCollector.attachTo(OrphanRetention.class)) {
      retention.run(storage(), null, 180);
      assertTrue(Files.exists(bad));
      assertFalse(Files.exists(good));
      assertEquals(2, logs.messages().size());
      assertTrue(logs.warnedWithAll("failed", "bad"));
      assertTrue(logs.warnedWithAll("failed", OLD));
    }
    pass(EXPIRES).run(storage(), null, 180);
    assertFalse(Files.exists(expired));
  }
}
