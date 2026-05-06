package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies that the placeholder {@link DirectMemoryOnlyDiskCache#loadOrAdd} stub throws
 * {@link UnsupportedOperationException} until the in-memory parallel implementation lands
 * in the next step of the read-cache concurrency fix.
 *
 * <p>This guard is intentional: the new {@code WriteCache.loadOrAdd} method is declared
 * abstract on the interface so any divergent mock or implementation is a compile-time
 * error. The real three-branch behaviour (load existing / one-page extend / multi-page
 * gap-fill) is implemented in a follow-up step; until then production code keeps routing
 * through the legacy {@code load} / {@code allocateNewPage} paths and the stub must never
 * be reached.
 */
public class DirectMemoryOnlyDiskCacheLoadOrAddStubTest {

  /**
   * Calling {@code loadOrAdd} on the in-memory engine must throw
   * {@link UnsupportedOperationException} with the documented "not yet wired" message,
   * confirming the stub is in place and unreachable from production paths in this commit.
   */
  @Test
  public void loadOrAddStubThrowsUnsupportedOperationException() {
    var cache = new DirectMemoryOnlyDiskCache(1024, 1, "stubTestStorage");

    var thrown =
        assertThrows(
            UnsupportedOperationException.class,
            () -> cache.loadOrAdd(/* fileId= */ 0L, /* pageIndex= */ 0L,
                /* verifyChecksums= */ false));

    assertTrue(
        "stub message must signal the placeholder is intentional",
        thrown.getMessage() != null && thrown.getMessage().contains("loadOrAdd"));
  }
}
