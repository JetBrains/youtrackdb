package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link CollectionBrowsePage}. The page wraps a non-empty list of
 * {@link CollectionBrowseEntry} and exposes the last entry's {@code collectionPosition} as
 * {@link CollectionBrowsePage#getLastPosition()} — used by the browse loop as the cursor for
 * the next page request. Tests pin the iteration order, the spliterator contract, and the
 * cursor invariant.
 */
public class CollectionBrowsePageTest {

  private static CollectionBrowseEntry entry(long pos) {
    return new CollectionBrowseEntry(
        pos, new RawBuffer(new byte[] {(byte) pos}, pos, (byte) 0));
  }

  /**
   * {@code getLastPosition()} returns the {@code collectionPosition} of the final list element,
   * which the browse iteration uses as the cursor for the next page.
   */
  @Test
  public void testGetLastPositionMatchesLastEntry() {
    var page = new CollectionBrowsePage(List.of(entry(1L), entry(2L), entry(7L)));
    assertThat(page.getLastPosition()).isEqualTo(7L);
  }

  /**
   * A page with a single entry exposes that entry's position as the last position.
   */
  @Test
  public void testGetLastPositionSingleEntry() {
    var page = new CollectionBrowsePage(List.of(entry(123L)));
    assertThat(page.getLastPosition()).isEqualTo(123L);
  }

  /**
   * The constructor calls {@code List.getLast()} and must reject an empty list with a
   * {@link NoSuchElementException}. This pins the precondition that callers never create an
   * empty browse page; the empty case is signalled by returning a fresh empty list, not a
   * page wrapping it.
   */
  @Test
  public void testEmptyListIsRejected() {
    assertThatThrownBy(() -> new CollectionBrowsePage(List.of()))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  /**
   * The page iterator yields entries in the order they were supplied to the constructor.
   */
  @Test
  public void testIteratorPreservesInsertionOrder() {
    var entries = new ArrayList<CollectionBrowseEntry>();
    for (long i = 100; i < 105; i++) {
      entries.add(entry(i));
    }
    var page = new CollectionBrowsePage(List.copyOf(entries));

    var observed = new ArrayList<Long>();
    for (var e : page) {
      observed.add(e.collectionPosition());
    }
    assertThat(observed).containsExactly(100L, 101L, 102L, 103L, 104L);
  }

  /**
   * The page iterator must be re-usable across multiple {@code for-each} iterations because
   * the underlying list is immutable and re-iterable.
   */
  @Test
  public void testIteratorReUsable() {
    var page = new CollectionBrowsePage(List.of(entry(1L), entry(2L)));

    int firstCount = 0;
    for (var ignored : page) {
      firstCount++;
    }
    int secondCount = 0;
    for (var ignored : page) {
      secondCount++;
    }
    assertThat(firstCount).isEqualTo(2);
    assertThat(secondCount).isEqualTo(2);
  }

  /**
   * The page exposes a non-null {@link java.util.Spliterator} that visits every element
   * exactly once. The default {@code Iterable.spliterator()} would also work; pin the
   * explicit override so it never silently regresses.
   */
  @Test
  public void testSpliteratorVisitsEveryEntry() {
    var page = new CollectionBrowsePage(List.of(entry(1L), entry(2L), entry(3L)));
    var spliterator = page.spliterator();
    assertThat(spliterator).isNotNull();

    var seen = new ArrayList<Long>();
    spliterator.forEachRemaining(e -> seen.add(e.collectionPosition()));
    assertThat(seen).containsExactly(1L, 2L, 3L);
  }
}
