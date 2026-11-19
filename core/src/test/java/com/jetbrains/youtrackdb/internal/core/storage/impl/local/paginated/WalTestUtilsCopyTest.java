package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WalTestUtilsCopyTest {

  @Rule
  public TemporaryFolder directory = new TemporaryFolder();

  /** Copies data, WAL, and a present double-write log, but omits both dirty flags and stale files. */
  @Test
  public void copiesStorageBytesWithoutDirtyFlags() throws Exception {
    var source = directory.newFolder("source").toPath();
    var target = directory.newFolder("target").toPath();
    Files.writeString(target.resolve("stale"), "old copy");
    for (var name : new String[] {"data.pcl", "segment.wal", "present.dwl", "dirty.fl",
        "dirty.flb"}) {
      Files.writeString(source.resolve(name), name);
    }

    WalTestUtils.copyDirtyStorage(source, target);

    for (var name : new String[] {"data.pcl", "segment.wal", "present.dwl"}) {
      assertEquals(name, Files.readString(target.resolve(name)));
      assertEquals(name, Files.readString(source.resolve(name)));
    }
    assertFalse(Files.exists(target.resolve("dirty.fl")));
    assertFalse(Files.exists(target.resolve("dirty.flb")));
    assertFalse(Files.exists(target.resolve("stale")));
    assertTrue(Files.exists(source.resolve("dirty.fl")));
    assertTrue(Files.exists(source.resolve("dirty.flb")));
  }

  /** A dangling double-write-log link models a file that disappears after directory listing. */
  @Test
  public void toleratesOnlyMissingDoubleWriteLog() throws Exception {
    var source = directory.newFolder("source").toPath();
    Files.createSymbolicLink(source.resolve("removed.dwl"), source.resolve("absent"));
    var target = directory.getRoot().toPath().resolve("target");

    WalTestUtils.copyDirtyStorage(source, target);

    assertTrue(Files.isDirectory(target));
    assertFalse(Files.exists(target.resolve("removed.dwl")));
  }

  /** Missing data or WAL files must fail the copy instead of hiding an incomplete snapshot. */
  @Test
  public void propagatesMissingNonDoubleWriteLog() throws Exception {
    var source = directory.newFolder("source").toPath();
    Files.createSymbolicLink(source.resolve("removed.wal"), source.resolve("absent"));
    var target = directory.getRoot().toPath().resolve("target");

    assertThrows(NoSuchFileException.class, () -> WalTestUtils.copyDirtyStorage(source, target));
  }

  /** A double-write-log copy error other than disappearance must reach the caller unchanged. */
  @Test
  public void propagatesOtherDoubleWriteLogFailures() throws Exception {
    var source = directory.newFolder("source").toPath();
    var file = Files.writeString(source.resolve("present.dwl"), "bytes");
    var target = directory.getRoot().toPath().resolve("target");
    var denied = new AccessDeniedException(file.toString());
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.copy(file, target.resolve(file.getFileName()))).thenThrow(denied);
      assertEquals(denied,
          assertThrows(AccessDeniedException.class,
              () -> WalTestUtils.copyDirtyStorage(source, target)));
    }
  }

  /** Rejecting the source itself, including a path alias, must preserve the source bytes. */
  @Test
  public void rejectsSourceAsTargetBeforeDeletingAnything() throws Exception {
    var source = directory.newFolder("source").toPath();
    Files.writeString(source.resolve("data.pcl"), "baseline");
    assertThrows(IllegalArgumentException.class,
        () -> WalTestUtils.copyDirtyStorage(source, source.resolve(".")));
    Path alias = directory.getRoot().toPath().resolve("alias");
    Files.createSymbolicLink(alias, source);
    assertThrows(IllegalArgumentException.class,
        () -> WalTestUtils.copyDirtyStorage(source, alias));
    assertEquals("baseline", Files.readString(source.resolve("data.pcl")));
  }
}
