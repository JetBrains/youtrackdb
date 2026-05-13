package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkTestFixtures.rid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.Test;

/**
 * Direct unit tests for {@link LinkListResultImpl} — a pure delegating {@link
 * com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkList} wrapper around an {@code
 * ArrayList<Identifiable>}. All tests are standalone (no database session).
 */
public class LinkListResultImplTest {

  // ------------------------------------------------------------------------- constructors

  /** Default constructor creates an empty, mutable list. */
  @Test
  public void defaultConstructorCreatesEmptyList() {
    var list = new LinkListResultImpl();
    assertThat(list).isEmpty();
    assertThat(list.size()).isZero();
    list.add(rid(1, 0));
    assertThat(list).hasSize(1);
  }

  /** {@code initialCapacity} constructor creates an empty list (capacity is a hint only). */
  @Test
  public void initialCapacityConstructorIsEmpty() {
    var list = new LinkListResultImpl(64);
    assertThat(list).isEmpty();
    assertThat(list.size()).isZero();
  }

  /** Copy constructor produces a defensive copy — independent of the source list. */
  @Test
  public void copyConstructorCopiesContentsAndIsIndependent() {
    var source = new ArrayList<Identifiable>(List.of(rid(1, 0), rid(1, 1)));
    var copy = new LinkListResultImpl(source);
    assertThat(copy).containsExactly(rid(1, 0), rid(1, 1));

    source.add(rid(1, 2));
    assertThat(copy).containsExactly(rid(1, 0), rid(1, 1));

    copy.add(rid(2, 0));
    assertThat(source).containsExactly(rid(1, 0), rid(1, 1), rid(1, 2));
  }

  // ------------------------------------------------------------------------- query methods

  /** Contains dispatches to the backing list — RID equality is used. */
  @Test
  public void containsReturnsTrueForRidEqualElement() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    assertThat(list.contains(rid(1, 0))).isTrue();
    assertThat(list.contains(rid(1, 1))).isFalse();
    assertThat(list.contains(null)).isFalse();
  }

  @Test
  public void indexOfAndLastIndexOfAgreeWithJdkListSemantics() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 0));
    assertThat(list.indexOf(rid(1, 0))).isZero();
    assertThat(list.lastIndexOf(rid(1, 0))).isEqualTo(2);
    assertThat(list.indexOf(rid(9, 9))).isEqualTo(-1);
    assertThat(list.lastIndexOf(rid(9, 9))).isEqualTo(-1);
  }

  @Test
  public void containsAllReturnsTrueOnlyIfEveryElementPresent() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    assertThat(list.containsAll(List.of(rid(1, 0), rid(1, 1)))).isTrue();
    assertThat(list.containsAll(List.of(rid(1, 0), rid(1, 99)))).isFalse();
    assertThat(list.containsAll(Collections.emptyList())).isTrue();
  }

  // ------------------------------------------------------------------------- mutation

  @Test
  public void addAllAtIndexInsertsInOrder() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 5));
    list.addAll(1, List.of(rid(1, 1), rid(1, 2)));
    assertThat(list).containsExactly(rid(1, 0), rid(1, 1), rid(1, 2), rid(1, 5));
  }

  @Test
  public void setReplacesElementAndReturnsPrevious() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var prev = list.set(1, rid(2, 2));
    assertThat(prev).isEqualTo(rid(1, 1));
    assertThat(list).containsExactly(rid(1, 0), rid(2, 2));
  }

  @Test
  public void addAtIndexShiftsRight() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 2));
    list.add(1, rid(1, 1));
    assertThat(list).containsExactly(rid(1, 0), rid(1, 1), rid(1, 2));
  }

  @Test
  public void removeByIndexReturnsRemovedElement() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    assertThat(list.remove(0)).isEqualTo(rid(1, 0));
    assertThat(list).containsExactly(rid(1, 1));
  }

  @Test
  public void removeByObjectReturnsFalseIfAbsent() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    assertThat(list.remove(rid(9, 9))).isFalse();
    assertThat(list).containsExactly(rid(1, 0));
  }

  @Test
  public void retainAllKeepsOnlySpecifiedElements() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 2));
    var changed = list.retainAll(List.of(rid(1, 0), rid(1, 2)));
    assertThat(changed).isTrue();
    assertThat(list).containsExactly(rid(1, 0), rid(1, 2));
  }

  @Test
  public void removeAllRemovesAllMatchingElements() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 0));
    var changed = list.removeAll(List.of(rid(1, 0)));
    assertThat(changed).isTrue();
    assertThat(list).containsExactly(rid(1, 1));
  }

  @Test
  public void clearEmptiesList() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.clear();
    assertThat(list).isEmpty();
  }

  @Test
  public void removeIfKeepsElementsFailingPredicate() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(2, 0));
    list.add(rid(3, 0));
    var changed = list.removeIf(id -> id.getIdentity().getCollectionId() == 2);
    assertThat(changed).isTrue();
    assertThat(list).containsExactly(rid(1, 0), rid(3, 0));
  }

  @Test
  public void replaceAllAppliesOperatorToEveryElement() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.replaceAll(id -> rid(9, id.getIdentity().getCollectionPosition()));
    assertThat(list).containsExactly(rid(9, 0), rid(9, 1));
  }

  // ------------------------------------------------------------------------- ordering

  @Test
  public void sortOrdersElementsAccordingToComparator() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 2));
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.sort(Comparator.comparingLong(id -> id.getIdentity().getCollectionPosition()));
    assertThat(list).containsExactly(rid(1, 0), rid(1, 1), rid(1, 2));
  }

  /** {@code sort(null)} performs a natural-order sort on {@code Comparable} elements. */
  @Test
  public void sortWithNullUsesNaturalOrderingOfIdentifiable() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 2));
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.sort(null);
    assertThat(list).containsExactly(rid(1, 0), rid(1, 1), rid(1, 2));
  }

  @Test
  public void reversedReturnsReverseOrderedView() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 2));
    assertThat(list.reversed()).containsExactly(rid(1, 2), rid(1, 1), rid(1, 0));
  }

  // ------------------------------------------------------------------------- deque methods

  @Test
  public void addFirstAndAddLastAppendToEnds() {
    var list = new LinkListResultImpl();
    list.addLast(rid(1, 1));
    list.addFirst(rid(1, 0));
    list.addLast(rid(1, 2));
    assertThat(list).containsExactly(rid(1, 0), rid(1, 1), rid(1, 2));
  }

  @Test
  public void getFirstAndGetLastReturnEndElements() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 2));
    assertThat(list.getFirst()).isEqualTo(rid(1, 0));
    assertThat(list.getLast()).isEqualTo(rid(1, 2));
  }

  @Test
  public void getFirstOnEmptyListThrows() {
    var list = new LinkListResultImpl();
    assertThatThrownBy(list::getFirst).isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(list::getLast).isInstanceOf(NoSuchElementException.class);
  }

  /** Parity with EmbeddedListResultImplTest.dequeEndpointsOnEmptyListThrow — pin remove paths. */
  @Test
  public void removeFirstAndRemoveLastOnEmptyListThrow() {
    var list = new LinkListResultImpl();
    assertThatThrownBy(list::removeFirst).isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(list::removeLast).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  public void removeFirstAndRemoveLastReturnRemovedElement() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 2));
    assertThat(list.removeFirst()).isEqualTo(rid(1, 0));
    assertThat(list.removeLast()).isEqualTo(rid(1, 2));
    assertThat(list).containsExactly(rid(1, 1));
  }

  // ------------------------------------------------------------------------- iteration

  @Test
  public void iteratorTraversesElementsInOrder() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var it = list.iterator();
    assertThat(it.hasNext()).isTrue();
    assertThat(it.next()).isEqualTo(rid(1, 0));
    assertThat(it.next()).isEqualTo(rid(1, 1));
    assertThat(it.hasNext()).isFalse();
  }

  @Test
  public void listIteratorSupportsBidirectionalTraversal() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var it = list.listIterator();
    assertThat(it.next()).isEqualTo(rid(1, 0));
    assertThat(it.next()).isEqualTo(rid(1, 1));
    assertThat(it.hasPrevious()).isTrue();
    assertThat(it.previous()).isEqualTo(rid(1, 1));
  }

  @Test
  public void listIteratorFromIndexStartsAtOffset() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 2));
    var it = list.listIterator(1);
    assertThat(it.next()).isEqualTo(rid(1, 1));
  }

  @Test
  public void spliteratorPartitionsList() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var sp = list.spliterator();
    assertThat(sp.estimateSize()).isEqualTo(2L);
    assertThat(sp.hasCharacteristics(java.util.Spliterator.SIZED)).isTrue();
  }

  @Test
  public void forEachInvokesActionOnEveryElement() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var seen = new ArrayList<Identifiable>();
    list.forEach(seen::add);
    assertThat(seen).containsExactly(rid(1, 0), rid(1, 1));
  }

  @Test
  public void streamAndParallelStreamExposeElements() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    // stream(): ordered, contents verified — a broken stream() returning fewer or extra elements
    // would be caught (count() alone would not).
    assertThat(list.stream()).containsExactly(rid(1, 0), rid(1, 1));
    // parallelStream may split or not — just assert content
    assertThat(list.parallelStream()).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  // ------------------------------------------------------------------------- toArray

  @Test
  public void toArrayReturnsElementsInOrder() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var arr = list.toArray();
    assertThat(arr).containsExactly(rid(1, 0), rid(1, 1));
  }

  @Test
  public void toArrayWithProvidedArraySizedExactly() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var target = new Identifiable[2];
    var ret = list.toArray(target);
    assertThat(ret).isSameAs(target);
    assertThat(ret).containsExactly(rid(1, 0), rid(1, 1));
  }

  @Test
  public void toArrayWithGeneratorAllocatesArrayOfExactSize() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var arr = list.toArray(Identifiable[]::new);
    assertThat(arr).hasSize(2).containsExactly(rid(1, 0), rid(1, 1));
  }

  // ------------------------------------------------------------------------- subList

  /**
   * {@code subList} exposes a live view — mutations through the sublist reach the original list
   * (verifies the live-backing contract that a defensive-copy implementation would break).
   */
  @Test
  public void subListExposesRangeBackedByOriginal() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 2));
    var sub = list.subList(1, 3);
    assertThat(sub).containsExactly(rid(1, 1), rid(1, 2));
    sub.set(0, rid(9, 9));
    assertThat(list).containsExactly(rid(1, 0), rid(9, 9), rid(1, 2));
  }

  @Test
  public void subListEmptyRangeReturnsEmpty() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    assertThat(list.subList(0, 0)).isEmpty();
    assertThat(list.subList(1, 1)).isEmpty();
  }

  // ------------------------------------------------------------------------- equals/hashCode/toString

  /**
   * Reflexive equality shortcut — a LinkListResultImpl equals itself without invoking the backing
   * list's equals (pin: protects the {@code obj == this} return path).
   */
  @Test
  public void equalsReturnsTrueForSameInstance() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    assertThat(list.equals(list)).isTrue();
  }

  /** Equality delegates to ArrayList.equals — different-list types with same order match. */
  @Test
  public void equalsTrueWithLinkedListOfSameOrder() {
    var link = new LinkListResultImpl();
    link.add(rid(1, 0));
    link.add(rid(1, 1));
    var other = new LinkedList<>(List.of(rid(1, 0), rid(1, 1)));
    assertThat(link).isEqualTo(other);
  }

  @Test
  public void equalsFalseWhenSizesDiffer() {
    var link = new LinkListResultImpl();
    link.add(rid(1, 0));
    assertThat(link.equals(List.of(rid(1, 0), rid(1, 1)))).isFalse();
  }

  /** Equality shortcut — anything that is not a List returns false before delegation. */
  @Test
  public void equalsFalseForNonListObject() {
    var link = new LinkListResultImpl();
    link.add(rid(1, 0));
    assertThat(link.equals("not a list")).isFalse();
    assertThat(link.equals(null)).isFalse();
    // Set-shaped collection with the same element still fails because LinkList is a List.
    assertThat(link.equals(Set.of(rid(1, 0)))).isFalse();
  }

  @Test
  public void hashCodeMatchesBackingArrayListHashCode() {
    var link = new LinkListResultImpl();
    link.add(rid(1, 0));
    link.add(rid(1, 1));
    var arraylist = new ArrayList<>(List.of(rid(1, 0), rid(1, 1)));
    assertThat(link.hashCode()).isEqualTo(arraylist.hashCode());
  }

  @Test
  public void toStringProducesBracketedListFormat() {
    var link = new LinkListResultImpl();
    link.add(rid(1, 0));
    link.add(rid(1, 1));
    assertThat(link.toString()).isEqualTo(Arrays.asList(rid(1, 0), rid(1, 1)).toString());
  }

  // ------------------------------------------------------------------------- misc

  @Test
  public void isEmptyReflectsState() {
    var list = new LinkListResultImpl();
    assertThat(list.isEmpty()).isTrue();
    list.add(rid(1, 0));
    assertThat(list.isEmpty()).isFalse();
  }

  @Test
  public void getAtOutOfRangeThrows() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    assertThatThrownBy(() -> list.get(5)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  public void iteratorForEachRemainingVisitsAllElementsInOrder() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    list.add(rid(1, 2));
    var seen = new ArrayList<Identifiable>();
    list.iterator().forEachRemaining(seen::add);
    assertThat(seen).containsExactly(rid(1, 0), rid(1, 1), rid(1, 2));
  }

  /** Iterator.remove propagates to the backing list (pin the live-backing contract). */
  @Test
  public void iteratorRemovePropagatesToList() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    list.add(rid(1, 1));
    var it = list.iterator();
    it.next();
    it.remove();
    assertThat(list).containsExactly(rid(1, 1));
  }

  /** ListIterator set/add mutate the backing list. */
  @Test
  public void listIteratorSetAndAddMutateList() {
    var list = new LinkListResultImpl();
    list.add(rid(1, 0));
    var li = list.listIterator();
    li.next();
    li.set(rid(2, 0));
    li.add(rid(3, 0));
    assertThat(list).containsExactly(rid(2, 0), rid(3, 0));
  }

  @Test
  public void sortAndReversedOnEmptyListAreNoOps() {
    var list = new LinkListResultImpl();
    list.sort(null);
    assertThat(list).isEmpty();
    assertThat(list.reversed()).isEmpty();
  }

  @Test
  public void copyConstructorAcceptsEmptySource() {
    assertThat(new LinkListResultImpl(new ArrayList<Identifiable>())).isEmpty();
  }
}
