/*
 *
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
package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

/**
 * Smoke tests for {@link PageEntryFixture} verifying its contract:
 *
 * <ul>
 *   <li>Reader-style acquisitions allocate a real direct-memory page and return an entry
 *       whose coordinates match the requested ({@code fileId, pageIndex}).
 *   <li>Exclusive-style acquisitions hold the page-frame exclusive lock so subsequent
 *       {@code releaseExclusiveLock()} succeeds with no stamp-tracking error.
 *   <li>Multiple acquisitions in either mode are tracked individually and tear down cleanly.
 *   <li>{@link PageEntryFixture#close()} is idempotent so try-with-resources combined with an
 *       {@code @After}-driven {@code close()} does not double-release.
 *   <li>After {@code close()} the fixture rejects further acquisitions.
 *   <li>{@link PageEntryFixture#close()} runs the direct-memory leak detector
 *       ({@link com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator#checkMemoryLeaks()})
 *       — if any acquired page were left without a matching decrement, the call would fail
 *       loudly and these tests would not be green.
 * </ul>
 */
public class PageEntryFixtureSmokeTest {

  /**
   * Acquires a single reader-style entry, asserts its coordinates and that the underlying
   * allocator is non-null, then closes the fixture and verifies idempotent close. Failing
   * this test means the fixture cannot replace the inline boilerplate previously duplicated
   * across cache-policy and read-buffer tests.
   */
  @Test
  public void readerAcquisitionMatchesCoordinatesAndCloseIsIdempotent() {
    final var pages = new PageEntryFixture();
    try {
      final var entry = pages.acquireReader(7L, 3);
      assertThat(entry).isNotNull();
      assertThat(entry.getFileId()).isEqualTo(7L);
      assertThat(entry.getPageIndex()).isEqualTo(3);
      assertThat(entry.getCachePointer()).isNotNull();
      assertThat(entry.getCachePointer().getBuffer()).isNotNull();
      assertThat(pages.acquisitionCount()).isEqualTo(1);
      assertThat(pages.allocator()).isNotNull();
      assertThat(pages.bufferPool()).isNotNull();
    } finally {
      pages.close();
      // Idempotent close — second invocation is a no-op.
      pages.close();
    }
  }

  /**
   * Acquires an exclusive-style entry, verifies that the page-frame exclusive lock is held
   * (a redundant {@code acquireExclusiveLock} on the same frame would deadlock; instead we
   * assert that the entry pointer is non-null and the buffer is writable). The fixture's
   * {@code close()} releases the lock and the leak detector confirms the symmetric
   * decrementReferrer cleanup.
   */
  @Test
  public void exclusiveAcquisitionExposesEntryAndCloseReleasesLock() {
    try (var pages = new PageEntryFixture()) {
      final var entry = pages.acquireExclusive(11L, 5);
      assertThat(entry.getFileId()).isEqualTo(11L);
      assertThat(entry.getPageIndex()).isEqualTo(5);
      assertThat(entry.getCachePointer().getBuffer()).isNotNull();
      assertThat(pages.acquisitionCount()).isEqualTo(1);
      // The buffer is writable while the exclusive lock is held — proves the fixture
      // matches the pattern that bucket round-trip tests rely on.
      entry.getCachePointer().getBuffer().putInt(0, 0xDEADBEEF);
      assertThat(entry.getCachePointer().getBuffer().getInt(0)).isEqualTo(0xDEADBEEF);
    }
  }

  /**
   * Mixes both modes inside a single fixture lifetime, drives multiple acquisitions, and
   * verifies the count tracking. {@code close()} must release every acquisition; the
   * built-in leak detector confirms there is no surviving direct-memory pointer.
   */
  @Test
  public void mixedAcquisitionsAllReleasedOnClose() {
    final var pages = new PageEntryFixture();
    try {
      pages.acquireReader(0L, 0);
      pages.acquireReader(0L, 1);
      pages.acquireExclusive(1L, 0);
      pages.acquireExclusive(1L, 1);
      assertThat(pages.acquisitionCount()).isEqualTo(4);
    } finally {
      pages.close();
    }
  }

  /**
   * After {@link PageEntryFixture#close()} the fixture must reject further acquisitions to
   * surface use-after-close bugs in tests rather than allow a silent leak past the
   * leak-detector window.
   */
  @Test
  public void acquireAfterCloseThrows() {
    final var pages = new PageEntryFixture();
    pages.close();
    assertThatThrownBy(() -> pages.acquireReader(0L, 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    assertThatThrownBy(() -> pages.acquireExclusive(0L, 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  /**
   * Constructing the fixture with an invalid page size or pool size must fail fast — these
   * are common copy-paste mistakes that would otherwise produce a confusing failure deep
   * inside the {@code ByteBufferPool} initialisation.
   */
  @Test
  public void invalidConstructorArgumentsAreRejected() {
    assertThatThrownBy(() -> new PageEntryFixture(0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageSize");
    assertThatThrownBy(() -> new PageEntryFixture(-1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageSize");
    assertThatThrownBy(() -> new PageEntryFixture(4096, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("poolSize");
  }
}
