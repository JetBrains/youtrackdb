package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Test;

/**
 * Direct unit tests for {@link EmbeddedListResultImpl} — a pure delegating {@link
 * com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedList} wrapper around
 * an {@code ArrayList<T>}. Unlike the Link variants this one is parameterized over arbitrary
 * element types. All tests are standalone.
 */
public class EmbeddedListResultImplTest {

  @Test
  public void defaultConstructorCreatesEmptyList() {
    var list = new EmbeddedListResultImpl<String>();
    assertThat(list).isEmpty();
    list.add("x");
    assertThat(list).containsExactly("x");
  }

  @Test
  public void initialCapacityConstructorIsEmpty() {
    var list = new EmbeddedListResultImpl<Integer>(32);
    assertThat(list).isEmpty();
  }

  @Test
  public void copyConstructorIsDefensive() {
    var source = new ArrayList<>(List.of("a", "b"));
    var copy = new EmbeddedListResultImpl<>(source);
    assertThat(copy).containsExactly("a", "b");
    source.add("c");
    assertThat(copy).doesNotContain("c");
  }

  @Test
  public void containsReturnsTrueForPresentElement() {
    var list = new EmbeddedListResultImpl<String>();
    list.add("a");
    assertThat(list.contains("a")).isTrue();
    assertThat(list.contains("b")).isFalse();
    assertThat(list.contains(null)).isFalse();
  }

  @Test
  public void indexOfAndLastIndexOfAgreeWithJdkSemantics() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b", "a"));
    assertThat(list.indexOf("a")).isZero();
    assertThat(list.lastIndexOf("a")).isEqualTo(2);
    assertThat(list.indexOf("z")).isEqualTo(-1);
  }

  @Test
  public void containsAllReturnsTrueOnlyIfAllPresent() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b", "c"));
    assertThat(list.containsAll(List.of("a", "c"))).isTrue();
    assertThat(list.containsAll(List.of("a", "z"))).isFalse();
  }

  @Test
  public void addAllInsertsAtEndByDefault() {
    var list = new EmbeddedListResultImpl<String>();
    list.add("x");
    list.addAll(List.of("a", "b"));
    assertThat(list).containsExactly("x", "a", "b");
  }

  @Test
  public void addAllAtIndexInsertsInOrder() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "z"));
    list.addAll(1, List.of("b", "c"));
    assertThat(list).containsExactly("a", "b", "c", "z");
  }

  @Test
  public void setReplacesElementAndReturnsPrevious() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    assertThat(list.set(1, "B")).isEqualTo("b");
    assertThat(list).containsExactly("a", "B");
  }

  @Test
  public void addAtIndexShiftsRight() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "c"));
    list.add(1, "b");
    assertThat(list).containsExactly("a", "b", "c");
  }

  @Test
  public void removeByIndexReturnsElement() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b", "c"));
    assertThat(list.remove(1)).isEqualTo("b");
    assertThat(list).containsExactly("a", "c");
  }

  @Test
  public void removeByObjectReturnsFalseWhenAbsent() {
    var list = new EmbeddedListResultImpl<String>();
    list.add("a");
    assertThat(list.remove("z")).isFalse();
    assertThat(list.remove("a")).isTrue();
  }

  @Test
  public void removeAllRetainsNonMatchingElements() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b", "a", "c"));
    assertThat(list.removeAll(List.of("a"))).isTrue();
    assertThat(list).containsExactly("b", "c");
  }

  @Test
  public void retainAllRestrictsToIntersection() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b", "c"));
    list.retainAll(List.of("a", "c"));
    assertThat(list).containsExactly("a", "c");
  }

  @Test
  public void clearEmptiesList() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    list.clear();
    assertThat(list).isEmpty();
  }

  @Test
  public void removeIfFiltersByPredicate() {
    var list = new EmbeddedListResultImpl<Integer>();
    list.addAll(List.of(1, 2, 3, 4));
    list.removeIf(i -> i % 2 == 0);
    assertThat(list).containsExactly(1, 3);
  }

  @Test
  public void replaceAllAppliesOperator() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    list.replaceAll(String::toUpperCase);
    assertThat(list).containsExactly("A", "B");
  }

  @Test
  public void sortOrdersWithCustomComparator() {
    var list = new EmbeddedListResultImpl<Integer>();
    list.addAll(List.of(3, 1, 2));
    list.sort(Comparator.reverseOrder());
    assertThat(list).containsExactly(3, 2, 1);
  }

  @Test
  public void sortWithNullUsesNaturalOrder() {
    var list = new EmbeddedListResultImpl<Integer>();
    list.addAll(List.of(3, 1, 2));
    list.sort(null);
    assertThat(list).containsExactly(1, 2, 3);
  }

  @Test
  public void reversedReturnsReverseOrder() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b", "c"));
    assertThat(list.reversed()).containsExactly("c", "b", "a");
  }

  @Test
  public void dequeEndpointsAccessFirstAndLast() {
    var list = new EmbeddedListResultImpl<String>();
    list.addLast("b");
    list.addFirst("a");
    list.addLast("c");
    assertThat(list.getFirst()).isEqualTo("a");
    assertThat(list.getLast()).isEqualTo("c");
    assertThat(list.removeFirst()).isEqualTo("a");
    assertThat(list.removeLast()).isEqualTo("c");
    assertThat(list).containsExactly("b");
  }

  @Test
  public void dequeEndpointsOnEmptyListThrow() {
    var list = new EmbeddedListResultImpl<String>();
    assertThatThrownBy(list::getFirst).isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(list::getLast).isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(list::removeFirst).isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(list::removeLast).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  public void iteratorAndListIteratorTraverseInOrder() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    var it = list.iterator();
    assertThat(it.next()).isEqualTo("a");
    assertThat(it.next()).isEqualTo("b");
    var li = list.listIterator(1);
    assertThat(li.next()).isEqualTo("b");
    assertThat(li.previous()).isEqualTo("b");
  }

  @Test
  public void subListReturnsRange() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b", "c", "d"));
    assertThat(list.subList(1, 3)).containsExactly("b", "c");
  }

  @Test
  public void forEachVisitsAllElements() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    var seen = new ArrayList<String>();
    list.forEach(seen::add);
    assertThat(seen).containsExactly("a", "b");
  }

  @Test
  public void streamAndParallelStreamExposeElements() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    assertThat(list.stream().count()).isEqualTo(2L);
    assertThat(list.parallelStream()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void spliteratorIsSized() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    var sp = list.spliterator();
    assertThat(sp.estimateSize()).isEqualTo(2L);
    assertThat(sp.hasCharacteristics(java.util.Spliterator.SIZED)).isTrue();
  }

  @Test
  public void toArrayFormsReturnElements() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    assertThat(list.toArray()).containsExactly("a", "b");
    var target = new String[2];
    assertThat(list.toArray(target)).isSameAs(target).containsExactly("a", "b");
    assertThat(list.toArray(String[]::new)).containsExactly("a", "b");
  }

  // ------------------------------------------------------------------------- equals/hashCode

  /** Reflexive equality shortcut. */
  @Test
  public void equalsReturnsTrueForSameInstance() {
    var list = new EmbeddedListResultImpl<String>();
    list.add("a");
    assertThat(list.equals(list)).isTrue();
  }

  /** Unlike the Link variants, Embedded list delegates properly — cross-instance equality works. */
  @Test
  public void equalsDelegatesToBackingListAcrossInstances() {
    var a = new EmbeddedListResultImpl<String>();
    a.addAll(List.of("a", "b"));
    var b = new EmbeddedListResultImpl<String>();
    b.addAll(List.of("a", "b"));
    assertThat(a).isEqualTo(b);
  }

  @Test
  public void equalsAgainstLinkedListWithSameOrder() {
    var a = new EmbeddedListResultImpl<String>();
    a.addAll(List.of("a", "b"));
    var other = new LinkedList<>(List.of("a", "b"));
    assertThat(a).isEqualTo(other);
  }

  @Test
  public void equalsReturnsFalseForNonList() {
    var list = new EmbeddedListResultImpl<String>();
    list.add("a");
    assertThat(list.equals("not a list")).isFalse();
    assertThat(list.equals(null)).isFalse();
    assertThat(list.equals(java.util.Set.of("a"))).isFalse();
  }

  @Test
  public void hashCodeMatchesBackingArrayList() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    assertThat(list.hashCode()).isEqualTo(new ArrayList<>(List.of("a", "b")).hashCode());
  }

  @Test
  public void toStringMatchesListFormat() {
    var list = new EmbeddedListResultImpl<String>();
    list.addAll(List.of("a", "b"));
    assertThat(list.toString()).isEqualTo(List.of("a", "b").toString());
  }

  // ------------------------------------------------------------------------- misc

  @Test
  public void sizeAndIsEmptyTrackState() {
    var list = new EmbeddedListResultImpl<String>();
    assertThat(list.isEmpty()).isTrue();
    list.add("a");
    assertThat(list.isEmpty()).isFalse();
    assertThat(list.size()).isEqualTo(1);
  }

  @Test
  public void getOutOfRangeThrows() {
    var list = new EmbeddedListResultImpl<String>();
    list.add("a");
    assertThatThrownBy(() -> list.get(5)).isInstanceOf(IndexOutOfBoundsException.class);
  }
}
