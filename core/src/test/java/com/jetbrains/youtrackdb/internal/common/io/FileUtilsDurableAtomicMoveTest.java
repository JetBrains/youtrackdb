package com.jetbrains.youtrackdb.internal.common.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.jna.Native;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the CS40 recipe in {@link FileUtils#durableAtomicMove}.
 * The source atomically replaces an existing target and then disappears.
 * A fresh target follows the same recipe.
 * Each supported platform runs its production durability mechanism in these tests.
 */
public class FileUtilsDurableAtomicMoveTest {

  private Path directory;

  @Before
  public void setUp() throws IOException {
    directory = Files.createTempDirectory("durable-move-test");
  }

  @After
  public void tearDown() throws IOException {
    if (Files.notExists(directory)) {
      return;
    }
    try (var files = Files.walk(directory)) {
      try {
        files.sorted(Comparator.reverseOrder()).forEach(file -> {
          try {
            Files.deleteIfExists(file);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
  }

  @Test
  public void moveReplacesExistingTargetAndRemovesSource() throws IOException {
    var source = directory.resolve("source.tmp");
    var target = directory.resolve("target.gz");
    var payload = "NEW-DUMP".getBytes(StandardCharsets.UTF_8);
    Files.write(source, payload);
    Files.write(target, "OLD-DUMP".getBytes(StandardCharsets.UTF_8));

    FileUtils.durableAtomicMove(source, target, this);

    assertArrayEquals("the target must carry the source's content", payload,
        Files.readAllBytes(target));
    assertTrue("the source must be gone after the move", Files.notExists(source));
  }

  /** A required Unix directory barrier failure is reported after the atomic replacement. */
  @Test
  public void directoryBarrierFailureIsReported() throws IOException {
    var source = directory.resolve("barrier-source.tmp");
    var target = directory.resolve("barrier-target.gz");
    Files.write(source, "PAYLOAD".getBytes(StandardCharsets.UTF_8));

    var failure =
        assertThrows(
            IOException.class,
            () -> FileUtils.durableAtomicMove(
                source,
                target,
                false,
                ignored -> {
                  throw new IOException("injected directory barrier failure");
                }));

    assertTrue(failure.getMessage().contains("injected directory barrier failure"));
  }

  /**
   * An unavailable native helper uses the portable move and records one warning. The target must
   * contain the source payload after the fallback.
   */
  @Test
  public void unavailableWindowsNativeHelperUsesPortableMove() throws IOException {
    var source = directory.resolve("fallback-source.tmp");
    var target = directory.resolve("fallback-target.bin");
    var payload = "FALLBACK-PAYLOAD".getBytes(StandardCharsets.UTF_8);
    Files.write(source, payload);
    Files.write(target, "OLD".getBytes(StandardCharsets.UTF_8));
    var loadFailure = new UnsatisfiedLinkError("missing native helper");
    var warningCount = new AtomicInteger();

    FileUtils.durableAtomicMove(
        source,
        target,
        this,
        true,
        ignored -> {
          throw new AssertionError("the Unix directory barrier must not run");
        },
        FileUtils.WindowsMoveBinding.unavailable(loadFailure),
        FileUtils::portableWindowsMove,
        (ignored, message, failure) -> {
          assertTrue(message.contains("native helper"));
          assertTrue(message.contains("crash-atomic guarantee is not available"));
          assertSame(loadFailure, failure);
          warningCount.incrementAndGet();
        },
        new AtomicBoolean());

    assertArrayEquals(payload, Files.readAllBytes(target));
    assertTrue(Files.notExists(source));
    assertEquals(1, warningCount.get());
  }

  /** Both native loading and portable movement causes remain visible when fallback also fails. */
  @Test
  public void unavailableWindowsNativeHelperAndPortableFailureNameBothCauses()
      throws IOException {
    var source = directory.resolve("failed-fallback-source.tmp");
    var target = directory.resolve("failed-fallback-target.bin");
    Files.write(source, "PAYLOAD".getBytes(StandardCharsets.UTF_8));
    var loadFailure = new UnsatisfiedLinkError("missing native helper cause");
    var portableFailure = new IOException("portable move cause");

    var failure =
        assertThrows(
            IOException.class,
            () -> FileUtils.durableAtomicMove(
                source,
                target,
                this,
                true,
                ignored -> {
                  throw new AssertionError("the Unix directory barrier must not run");
                },
                FileUtils.WindowsMoveBinding.unavailable(loadFailure),
                (ignoredSource, ignoredTarget, ignoredRequester) -> {
                  throw portableFailure;
                },
                (ignoredRequester, ignoredMessage, ignoredFailure) -> {
                },
                new AtomicBoolean()));

    assertTrue(failure.getMessage().contains("missing native helper cause"));
    assertTrue(failure.getMessage().contains("portable move cause"));
    assertSame(portableFailure, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertSame(loadFailure, failure.getSuppressed()[0]);
  }

  /** A plain error from native-helper loading becomes the memorized fallback result. */
  @Test
  public void plainNativeLoadErrorCannotEscape() {
    var loadFailure = new Error("incompatible native helper version");

    var binding =
        FileUtils.loadWindowsMoveBinding(() -> {
          throw loadFailure;
        });

    assertSame(loadFailure, binding.loadFailure());
    assertEquals(null, binding.nativeMove());
  }

  /** One process-level warning guard suppresses duplicate warnings across fallback moves. */
  @Test
  public void unavailableWindowsNativeHelperWarnsOnceAcrossSeveralCalls() throws IOException {
    var loadFailure = new NoClassDefFoundError("missing native helper");
    var warningRecorded = new AtomicBoolean();
    var warningCount = new AtomicInteger();

    for (var index = 0; index < 3; index++) {
      var source = directory.resolve("repeated-source-" + index + ".tmp");
      var target = directory.resolve("repeated-target-" + index + ".bin");
      Files.write(source, ("payload-" + index).getBytes(StandardCharsets.UTF_8));
      FileUtils.durableAtomicMove(
          source,
          target,
          this,
          true,
          ignored -> {
            throw new AssertionError("the Unix directory barrier must not run");
          },
          FileUtils.WindowsMoveBinding.unavailable(loadFailure),
          FileUtils::portableWindowsMove,
          (ignored, message, failure) -> warningCount.incrementAndGet(),
          warningRecorded);
      assertTrue(Files.exists(target));
    }

    assertEquals(1, warningCount.get());
  }

  /**
   * Windows path conversion preserves relative and device paths. It extends drive and UNC paths
   * so native replacement does not depend on the legacy path-length setting.
   */
  @Test
  public void windowsNativePathsPreserveMeaningAndEnableLongPaths() {
    assertEquals("relative\\file.tmp", FileUtils.toWindowsNativePath("relative\\file.tmp"));
    assertEquals("C:relative\\file.tmp", FileUtils.toWindowsNativePath("C:relative\\file.tmp"));
    assertEquals("\\\\?\\C:\\data\\file.tmp",
        FileUtils.toWindowsNativePath("C:\\data\\file.tmp"));
    assertEquals("\\\\?\\UNC\\server\\share\\file.tmp",
        FileUtils.toWindowsNativePath("\\\\server\\share\\file.tmp"));
    assertEquals("\\\\?\\C:\\data\\file.tmp",
        FileUtils.toWindowsNativePath("\\\\?\\C:\\data\\file.tmp"));
    assertEquals("\\\\?\\UNC\\server\\share\\file.tmp",
        FileUtils.toWindowsNativePath("\\\\?\\UNC\\server\\share\\file.tmp"));
    assertEquals("\\\\.\\PhysicalDrive0",
        FileUtils.toWindowsNativePath("\\\\.\\PhysicalDrive0"));
  }

  /** Ordinary drive and UNC paths resolve dot segments before entering the extended namespace. */
  @Test
  public void windowsNativePathsNormalizeDotSegmentsBeforePrefixing() {
    assertEquals("\\\\?\\C:\\data\\final.tmp",
        FileUtils.toWindowsNativePath("C:\\data\\work\\..\\.\\final.tmp"));
    assertEquals("\\\\?\\C:\\final.tmp",
        FileUtils.toWindowsNativePath("C:\\..\\final.tmp"));
    assertEquals("\\\\?\\UNC\\server\\share\\final.tmp",
        FileUtils.toWindowsNativePath("\\\\server\\share\\work\\..\\.\\final.tmp"));
    assertEquals("\\\\?\\C:\\data\\..\\literal.tmp",
        FileUtils.toWindowsNativePath("\\\\?\\C:\\data\\..\\literal.tmp"));
  }

  /** The selected JNA artifact contains native dispatch support for Windows ARM64. */
  @Test
  public void jnaContainsWindowsArm64Dispatcher() {
    assertNotNull(
        "JNA must contain the Windows ARM64 native dispatcher",
        Native.class.getResource("/com/sun/jna/win32-aarch64/jnidispatch.dll"));
  }

  /**
   * On Windows, an available long-path file system performs the real write-through replacement.
   * This checks API behavior only. It does not simulate power loss or certify physical durability.
   */
  @Test
  public void windowsLongPathMoveReplacesExistingTarget() throws IOException {
    Assume.assumeTrue("Windows-only native replacement test", IOUtils.isOsWindows());
    var deepDirectory = directory;
    while (deepDirectory.toAbsolutePath().toString().length() < 280) {
      deepDirectory = deepDirectory.resolve("long-path-segment");
    }
    try {
      Files.createDirectories(deepDirectory);
    } catch (IOException | UnsupportedOperationException e) {
      Assume.assumeNoException("The test environment does not support long paths", e);
    }

    var source = deepDirectory.resolve("source.tmp");
    var target = deepDirectory.resolve("target.bin");
    var payload = "LONG-PATH-PAYLOAD".getBytes(StandardCharsets.UTF_8);
    Files.write(source, payload);
    Files.write(target, "OLD".getBytes(StandardCharsets.UTF_8));

    FileUtils.durableAtomicMove(source, target, this);

    assertArrayEquals(payload, Files.readAllBytes(target));
    assertTrue(Files.notExists(source));
  }

  /**
   * On Windows, ordinary dot-segment paths keep their meaning during native replacement. This
   * checks functional API behavior only. It does not simulate power loss.
   */
  @Test
  public void windowsDotSegmentMoveReplacesIntendedTarget() throws IOException {
    Assume.assumeTrue("Windows-only native replacement test", IOUtils.isOsWindows());
    var sourceIntermediate = Files.createDirectory(directory.resolve("source-intermediate"));
    var targetIntermediate = Files.createDirectory(directory.resolve("target-intermediate"));
    var source = sourceIntermediate.resolve("..").resolve("source.tmp");
    var target = targetIntermediate.resolve("..").resolve("target.bin");
    var payload = "DOT-SEGMENT-PAYLOAD".getBytes(StandardCharsets.UTF_8);
    Files.write(directory.resolve("source.tmp"), payload);
    Files.write(directory.resolve("target.bin"), "OLD".getBytes(StandardCharsets.UTF_8));

    FileUtils.durableAtomicMove(source, target, this);

    assertArrayEquals(payload, Files.readAllBytes(directory.resolve("target.bin")));
    assertTrue(Files.notExists(directory.resolve("source.tmp")));
  }

  /** A supported platform creates and durably publishes a previously absent target. */
  @Test
  public void moveCreatesAbsentTarget() throws IOException {
    var source = directory.resolve("source.tmp");
    var target = directory.resolve("fresh.gz");
    var payload = "FRESH-DUMP".getBytes(StandardCharsets.UTF_8);
    Files.write(source, payload);

    FileUtils.durableAtomicMove(source, target, this);

    assertArrayEquals(payload, Files.readAllBytes(target));
    assertTrue(Files.notExists(source));
  }
}
