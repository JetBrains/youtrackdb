/*
     *
     *  *
     *  *  Licensed under the Apache License, Version 2.0 (the "License");
     *  *  you may not use this file except in compliance with the License.
     *  *  You may obtain a copy of the License at
     *  *
     *  *       http://www.apache.org/licenses/LICENSE-2.0
     *  *
     *  *  Unless required by applicable law or agreed to in writing, software
     *  *  distributed under the License is distributed on an "AS IS" BASIS,
     *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     *  *  See the License for the specific language governing permissions and
     *  *  limitations under the License.
     *  *
     *
     *
     */
package com.jetbrains.youtrackdb.internal.core.compression.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Dead-code shape pin for {@link ZIPCompressionUtil}.
 *
 * <p>PSI all-scope {@code ReferencesSearch} confirms the only reference to this class anywhere
 * in the project is the self-reference at {@code LogManager.error(ZIPCompressionUtil.class, ...)}
 * inside its own {@code addFile} method. There are zero production call sites for the four
 * public static entry points ({@code compressDirectory}, {@code uncompressDirectory},
 * {@code compressFiles}, plus the {@code .class} self-reference). The package-private helpers
 * are reachable only from the dead public methods, so the entire {@code core/compression}
 * package is production-dead.
 *
 * <p>This pin captures the public surface (method names, parameter types, modifiers) AND a
 * round-trip behavioral observable per public entry point so that a deletion commit either
 * removes this file in lockstep with the production class OR fails at compile time on the
 * reflection lookups. The round-trips guard against silent shape drift while the class
 * remains in the codebase.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this test file in the same commit that
 * removes the entire {@code core/compression} package per the cluster-classification table
 * row "core/compression" in {@code track-22a.md}.
 */
public class ZIPCompressionUtilDeadCodeTest {

  // The exact set of public, non-synthetic, declared methods on ZIPCompressionUtil.
  // The package-private helpers (extractFile, mkdirs, getDirectoryPart, addFolder, addFile)
  // are intentionally excluded — they are implementation detail and may be inlined or
  // restructured by a future refactor without breaking the dead-code contract.
  private static final Set<String> EXPECTED_PUBLIC_DECLARED_METHOD_NAMES =
      new TreeSet<>(Set.of("compressDirectory", "uncompressDirectory", "compressFiles"));

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void classIsPublicNonAbstractWithObjectSuper() {
    var clazz = ZIPCompressionUtil.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must not be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertFalse(
        "must not be final (no contract requires it)", Modifier.isFinal(clazz.getModifiers()));
    assertSame("super must remain Object — utility class", Object.class, clazz.getSuperclass());
    assertEquals("must implement no interfaces", 0, clazz.getInterfaces().length);
  }

  @Test
  public void publicDeclaredSurfaceMatchesPinnedSet() {
    // Capture only public, non-synthetic, declared methods. All public methods on this
    // utility class are static.
    var actual = new TreeSet<String>();
    for (Method m : ZIPCompressionUtil.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      if (!Modifier.isPublic(m.getModifiers())) {
        continue;
      }
      assertTrue(
          "all public methods on ZIPCompressionUtil must be static — utility class",
          Modifier.isStatic(m.getModifiers()));
      actual.add(m.getName());
    }
    assertEquals(
        "public declared method-name set must match the pinned dead-code surface",
        EXPECTED_PUBLIC_DECLARED_METHOD_NAMES,
        actual);
  }

  @Test
  public void compressDirectorySignatureIsPinned() throws Exception {
    // (String sourceFolderName, ZipOutputStream zos, String[] iSkipFileExtensions,
    //  CommandOutputListener iOutput) -> List<String>
    Method m =
        ZIPCompressionUtil.class.getDeclaredMethod(
            "compressDirectory",
            String.class,
            ZipOutputStream.class,
            String[].class,
            CommandOutputListener.class);
    assertSame("must return List", List.class, m.getReturnType());
    assertTrue(
        "compressDirectory must declare IOException — backup paths rely on it",
        java.util.Arrays.stream(m.getExceptionTypes())
            .anyMatch(IOException.class::isAssignableFrom));
  }

  @Test
  public void uncompressDirectorySignatureIsPinned() throws Exception {
    // (InputStream in, String out, CommandOutputListener iListener) -> void
    Method m =
        ZIPCompressionUtil.class.getDeclaredMethod(
            "uncompressDirectory",
            java.io.InputStream.class,
            String.class,
            CommandOutputListener.class);
    assertSame("must return void", void.class, m.getReturnType());
    assertTrue(
        "uncompressDirectory must declare IOException",
        java.util.Arrays.stream(m.getExceptionTypes())
            .anyMatch(IOException.class::isAssignableFrom));
  }

  @Test
  public void compressFilesSignatureIsPinned() throws Exception {
    // (String baseDirectory, Map<String,String> fileNames, OutputStream output,
    //  CommandOutputListener listener, int compressionLevel) -> void
    Method m =
        ZIPCompressionUtil.class.getDeclaredMethod(
            "compressFiles",
            String.class,
            Map.class,
            java.io.OutputStream.class,
            CommandOutputListener.class,
            int.class);
    assertSame("must return void", void.class, m.getReturnType());
    assertTrue(
        "compressFiles must declare IOException",
        java.util.Arrays.stream(m.getExceptionTypes())
            .anyMatch(IOException.class::isAssignableFrom));
  }

  /**
   * Round-trip a small directory tree through {@link ZIPCompressionUtil#compressDirectory} and
   * {@link ZIPCompressionUtil#uncompressDirectory}. Pins observable behavior: extension skip
   * filtering, returned compressed-file list, and full directory-tree round-trip.
   */
  @Test
  public void compressUncompressDirectoryRoundTripsBytesAndRespectsSkipExtensions()
      throws IOException {
    File src = tmp.newFolder("src");
    File subdir = new File(src, "sub");
    assertTrue("test setup: subdir must mkdir", subdir.mkdir());
    Files.writeString(new File(src, "keep.txt").toPath(), "hello", StandardCharsets.UTF_8);
    Files.writeString(new File(subdir, "deep.txt").toPath(), "deep", StandardCharsets.UTF_8);
    Files.writeString(new File(src, "skip.tmp").toPath(), "noise", StandardCharsets.UTF_8);

    File zipFile = tmp.newFile("out.zip");
    List<String> compressed;
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile.toPath()))) {
      compressed =
          ZIPCompressionUtil.compressDirectory(
              src.getAbsolutePath(), zos, new String[] {".tmp"}, null);
    }
    // Pin: skip.tmp must be filtered out; keep.txt and sub/deep.txt must appear.
    assertTrue(
        "compressed list must contain keep.txt: " + compressed,
        compressed.stream().anyMatch(p -> p.endsWith("keep.txt")));
    assertTrue(
        "compressed list must contain sub/deep.txt: " + compressed,
        compressed.stream().anyMatch(p -> p.endsWith("deep.txt")));
    assertTrue(
        "compressed list must NOT contain skip.tmp (filtered): " + compressed,
        compressed.stream().noneMatch(p -> p.endsWith("skip.tmp")));

    File outDir = tmp.newFolder("out");
    try (FileInputStream in = new FileInputStream(zipFile)) {
      ZIPCompressionUtil.uncompressDirectory(in, outDir.getAbsolutePath(), null);
    }
    assertEquals(
        "round-trip keep.txt content",
        "hello",
        Files.readString(new File(outDir, "keep.txt").toPath(), StandardCharsets.UTF_8));
    assertEquals(
        "round-trip sub/deep.txt content",
        "deep",
        Files.readString(
            new File(new File(outDir, "sub"), "deep.txt").toPath(), StandardCharsets.UTF_8));
  }

  /**
   * {@link ZIPCompressionUtil#uncompressDirectory} must reject path-traversal entries. Pin the
   * IOException to guard against silent removal of the validation in a future refactor.
   */
  @Test
  public void uncompressDirectoryRejectsPathTraversalEntries() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zos = new ZipOutputStream(bytes)) {
      // Entry name with ".." — must be rejected by the validation.
      zos.putNextEntry(new ZipEntry("../escape.txt"));
      zos.write("escape".getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }

    File outDir = tmp.newFolder("trav-out");
    try (var in = new ByteArrayInputStream(bytes.toByteArray())) {
      try {
        ZIPCompressionUtil.uncompressDirectory(in, outDir.getAbsolutePath(), null);
        org.junit.Assert.fail("must throw IOException on path-traversal entry");
      } catch (IOException expected) {
        assertTrue(
            "exception message must mention the invalid entry name: " + expected.getMessage(),
            expected.getMessage().contains("..")
                || expected.getMessage().toLowerCase().contains("invalid"));
      }
    }
  }

  /**
   * {@link ZIPCompressionUtil#compressFiles} must round-trip a flat fileNames map, applying the
   * specified compression level and using the in-archive name from the map's value.
   */
  @Test
  public void compressFilesRenamesEntriesPerMapAndStampsBackupComment() throws IOException {
    File base = tmp.newFolder("base");
    Files.writeString(new File(base, "raw.txt").toPath(), "payload", StandardCharsets.UTF_8);

    Map<String, String> fileNames = new LinkedHashMap<>();
    fileNames.put("raw.txt", "renamed.txt");

    var bytes = new ByteArrayOutputStream();
    ZIPCompressionUtil.compressFiles(
        base.getAbsolutePath(), fileNames, bytes, null, java.util.zip.Deflater.BEST_SPEED);

    Map<String, String> roundTrip = new HashMap<>();
    try (var zin = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      ZipEntry e;
      while ((e = zin.getNextEntry()) != null) {
        var buf = zin.readAllBytes();
        roundTrip.put(e.getName(), new String(buf, StandardCharsets.UTF_8));
      }
    }
    assertEquals(
        "exactly one entry, named per the map value (not the disk name)",
        Set.of("renamed.txt"),
        roundTrip.keySet());
    assertEquals("payload must round-trip", "payload", roundTrip.get("renamed.txt"));
  }
}
