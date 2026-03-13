package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link YTDBGraphFactory#resolvePath(String)} which resolves database paths to
 * canonical form for use as map keys. Covers the three resolution strategies: direct toRealPath,
 * create-then-resolve, and fallback to normalize.
 */
public class YTDBGraphFactoryResolvePathTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  /**
   * When the path already exists on disk, resolvePath should return the real (canonical) path
   * via {@link Path#toRealPath()}.
   */
  @Test
  public void testResolvePathExistingDirectory() throws IOException {
    var existing = tempFolder.newFolder("existingDb");
    var resolved = YTDBGraphFactory.resolvePath(existing.getAbsolutePath());

    // Should return the real path, which equals the canonical path of the existing directory
    assertThat(resolved).isEqualTo(existing.toPath().toRealPath());
  }

  /**
   * When the path does not exist yet, resolvePath should create the directory and then resolve
   * via {@link Path#toRealPath()}, ensuring symlinks/junctions are resolved.
   */
  @Test
  public void testResolvePathNonExistentDirectoryCreatesAndResolves() throws IOException {
    var nonExistent = tempFolder.getRoot().toPath().resolve("newDb");
    assertThat(nonExistent).doesNotExist();

    var resolved = YTDBGraphFactory.resolvePath(nonExistent.toString());

    // Directory should have been created
    assertThat(nonExistent).exists().isDirectory();
    // Resolved path should match the real path (toRealPath resolves 8.3 names on Windows)
    assertThat(resolved).isEqualTo(nonExistent.toRealPath());
  }

  /**
   * When the path does not exist and cannot be created (e.g., under a regular file rather than
   * a directory), resolvePath should fall back to {@link Path#toAbsolutePath()} +
   * {@link Path#normalize()}.
   */
  @Test
  public void testResolvePathFallsBackToNormalizeWhenCreationFails() {
    // Use a filename component exceeding the 255-char filesystem limit,
    // which makes Files.createDirectories fail on all platforms.
    var longName = "x".repeat(300);
    var impossiblePath = tempFolder.getRoot().toPath()
        .resolve(longName);

    var resolved = YTDBGraphFactory.resolvePath(
        impossiblePath.toString());

    // Should fall back to absolute + normalize since both
    // toRealPath and createDirectories fail
    assertThat(resolved)
        .isEqualTo(impossiblePath.toAbsolutePath().normalize());
    // Verify the path was not created (Files.exists returns false
    // for paths with invalid names)
    assertThat(Files.exists(impossiblePath)).isFalse();
  }

  /**
   * Verifies that resolvePath produces consistent results for the same physical path expressed
   * with redundant ".." segments, ensuring normalization works correctly.
   */
  @Test
  public void testResolvePathNormalizesRedundantSegments() throws IOException {
    var dir = tempFolder.newFolder("baseDir");
    var normalPath = dir.getAbsolutePath();
    var redundantPath = dir.getAbsolutePath() + "/../baseDir";

    var resolved1 = YTDBGraphFactory.resolvePath(normalPath);
    var resolved2 = YTDBGraphFactory.resolvePath(redundantPath);

    // Both should resolve to the same canonical path
    assertThat(resolved1).isEqualTo(resolved2);
  }

  /**
   * When a symlink points to a real directory, resolvePath should resolve through the symlink
   * to the actual target path, ensuring map key consistency.
   */
  @Test
  public void testResolvePathFollowsSymlinks() throws IOException {
    var realDir = tempFolder.newFolder("realDir").toPath();
    var symlink = tempFolder.getRoot().toPath().resolve("symlinkDir");
    Files.createSymbolicLink(symlink, realDir);

    var resolvedReal = YTDBGraphFactory.resolvePath(realDir.toString());
    var resolvedSymlink = YTDBGraphFactory.resolvePath(symlink.toString());

    // Both should resolve to the same real path
    assertThat(resolvedReal).isEqualTo(resolvedSymlink);
  }
}
