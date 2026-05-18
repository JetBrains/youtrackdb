/*
 *
 *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins the recovery-orchestrator wiring inside {@code DiskStorage.postProcessIncrementalRestore}.
 *
 * <p>The orchestrator entry point {@code truncateOrphansAfterRecovery} is wired at two sites
 * in the production code: {@code AbstractStorage.open()} and
 * {@code DiskStorage.postProcessIncrementalRestore}. The {@code open()} wiring is exercised
 * end-to-end by {@code TruncateOrphansAfterRecoveryIT}, but the incremental-restore wiring
 * has no integration coverage on this branch (the multi-storage incremental-backup setup
 * is too heavy to drive from a unit test, and the existing IT suite does not exercise the
 * incremental-restore path). A regression that drops or re-orders the second wiring would
 * therefore slip through every existing test.
 *
 * <p>{@code postProcessIncrementalRestore} is a {@code private} method on
 * {@link DiskStorage}; a Mockito spy with {@code CALLS_REAL_METHODS} cannot intercept
 * private-method invocation without instrumentation, and constructing a real
 * {@code DiskStorage} purely for this assertion would re-create the very integration
 * scaffolding the test is trying to avoid. The pragmatic alternative this test adopts
 * is a source-text test: read {@code DiskStorage.java} from the test
 * classpath, locate the {@code postProcessIncrementalRestore} method body, and assert
 * the call to {@code flushAllData()} appears strictly BEFORE the call to
 * {@code truncateOrphansAfterRecovery}. The ordering rationale (an earlier
 * truncate-then-flush would let a subsequent flush re-extend the file past the truncate
 * target) is documented inline in the method body and in the design note.
 *
 * <p>This is a coarse but cheap regression sentinel: any code reorganization that moves
 * the orchestrator call to a different position relative to the flush — including
 * accidental deletion of either line — fails this test.
 */
public class DiskStorageRestoreOrchestratorWiringTest {

  // Path within the source tree to DiskStorage.java relative to the repo root. The test
  // walks up from the working directory until it finds a directory containing
  // .git/.mvn/core to be robust against the surefire/IDE/module test runner cwd
  // differences. As a final fallback the test reads the source through the test
  // classpath via DiskStorage.class.getResource so it works inside CI module-isolated
  // runs where the source tree is not at the test working directory.
  private static final String DISK_STORAGE_SOURCE_RELATIVE_PATH =
      "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/disk/DiskStorage.java";

  /**
   * Asserts the source-text invariant: in {@code postProcessIncrementalRestore},
   * {@code flushAllData()} is invoked strictly BEFORE the orchestrator
   * {@code truncateOrphansAfterRecovery} (via
   * {@code atomicOperationsManager.executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)}).
   * A regression that swaps these or drops the orchestrator entirely fails this test.
   */
  @Test
  public void truncateOrphansFollowsFlushAllDataInPostProcessIncrementalRestore()
      throws IOException {
    var source = loadDiskStorageSource();

    // Restrict the search to the postProcessIncrementalRestore body so an unrelated
    // flushAllData()/truncateOrphansAfterRecovery elsewhere in the file does not satisfy
    // the ordering check.
    var methodStartMarker = "private void postProcessIncrementalRestore(";
    int methodStart = source.indexOf(methodStartMarker);
    assertThat(methodStart)
        .as("DiskStorage source must declare postProcessIncrementalRestore")
        .isGreaterThanOrEqualTo(0);

    // Find the opening brace of the method body and walk a brace counter forward to the
    // matching close — this is the well-formed substring the assertion operates on.
    int bodyOpen = source.indexOf('{', methodStart);
    assertThat(bodyOpen).isGreaterThan(methodStart);
    int bodyEnd = matchingCloseBrace(source, bodyOpen);
    assertThat(bodyEnd).isGreaterThan(bodyOpen);
    var methodBody = source.substring(bodyOpen, bodyEnd);

    int flushPos = methodBody.indexOf("flushAllData()");
    assertThat(flushPos)
        .as("postProcessIncrementalRestore must invoke flushAllData()")
        .isGreaterThanOrEqualTo(0);

    int truncatePos = methodBody.indexOf("this::truncateOrphansAfterRecovery");
    assertThat(truncatePos)
        .as("postProcessIncrementalRestore must dispatch truncateOrphansAfterRecovery"
            + " via executeInsideAtomicOperation")
        .isGreaterThanOrEqualTo(0);

    assertThat(truncatePos)
        .as("the orchestrator must run AFTER flushAllData() so the subsequent flush"
            + " cannot re-extend a file past the truncate target")
        .isGreaterThan(flushPos);
  }

  private static int matchingCloseBrace(String text, int openIndex) {
    int depth = 0;
    for (int i = openIndex; i < text.length(); i++) {
      var c = text.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private static String loadDiskStorageSource() throws IOException {
    // Strategy 1: walk up from cwd until a directory contains the source. Surefire runs
    // from the module root by default, so the relative path usually resolves on the
    // first iteration.
    var cwd = Paths.get("").toAbsolutePath();
    for (var dir = cwd; dir != null; dir = dir.getParent()) {
      var candidate = dir.resolve(DISK_STORAGE_SOURCE_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return Files.readString(candidate, StandardCharsets.UTF_8);
      }
    }

    // Strategy 2: locate the source through the class loader. Some IDE and CI
    // configurations run tests from the project root; the source path on disk is then
    // not directly under cwd but can be located by resolving the class file location to
    // its module's src tree.
    URL classLocation = DiskStorage.class.getProtectionDomain().getCodeSource().getLocation();
    var classFile = Paths.get(classLocation.getPath());
    for (var dir = classFile; dir != null; dir = dir.getParent()) {
      var candidate = dir.resolve(DISK_STORAGE_SOURCE_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return Files.readString(candidate, StandardCharsets.UTF_8);
      }
    }

    throw new IOException(
        "DiskStorage.java not found relative to cwd or class location."
            + " cwd=" + cwd + " classLocation=" + classLocation);
  }
}
