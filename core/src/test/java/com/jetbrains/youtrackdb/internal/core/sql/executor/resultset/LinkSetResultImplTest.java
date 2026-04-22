package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkTestFixtures.rid;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Direct unit tests for {@link LinkSetResultImpl} — a pure delegating {@link
 * com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkSet} wrapper around a {@code
 * HashSet<Identifiable>}. All tests are standalone.
 */
public class LinkSetResultImplTest {

  // ------------------------------------------------------------------------- constructors

  /** Default constructor creates an empty, mutable set. */
  @Test
  public void defaultConstructorCreatesEmptySet() {
    var set = new LinkSetResultImpl();
    assertThat(set).isEmpty();
    set.add(rid(1, 0));
    assertThat(set).hasSize(1);
  }

  @Test
  public void initialCapacityConstructorIsEmpty() {
    var set = new LinkSetResultImpl(16);
    assertThat(set).isEmpty();
  }

  @Test
  public void copyConstructorAcceptsEmptySource() {
    assertThat(new LinkSetResultImpl(Collections.emptyList())).isEmpty();
  }

  /** Copy constructor is defensive — source mutation does not reach the copy. */
  @Test
  public void copyConstructorIsDefensive() {
    var source = new ArrayList<Identifiable>(List.of(rid(1, 0), rid(1, 1)));
    var copy = new LinkSetResultImpl(source);
    assertThat(copy).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
    source.add(rid(1, 2));
    assertThat(copy).hasSize(2);
  }

  // ------------------------------------------------------------------------- set semantics

  @Test
  public void addReturnsFalseForDuplicate() {
    var set = new LinkSetResultImpl();
    assertThat(set.add(rid(1, 0))).isTrue();
    assertThat(set.add(rid(1, 0))).isFalse();
    assertThat(set).hasSize(1);
  }

  @Test
  public void containsReflectsMembership() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    assertThat(set.contains(rid(1, 0))).isTrue();
    assertThat(set.contains(rid(1, 1))).isFalse();
    assertThat(set.contains(null)).isFalse();
  }

  @Test
  public void containsAllReturnsTrueOnlyIfAllPresent() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    assertThat(set.containsAll(List.of(rid(1, 0), rid(1, 1)))).isTrue();
    assertThat(set.containsAll(List.of(rid(1, 0), rid(9, 9)))).isFalse();
  }

  @Test
  public void removeByObjectReturnsTrueIfPresent() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    assertThat(set.remove(rid(1, 0))).isTrue();
    assertThat(set.remove(rid(1, 0))).isFalse();
  }

  @Test
  public void addAllMergesDistinct() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    var changed = set.addAll(List.of(rid(1, 0), rid(1, 1)));
    assertThat(changed).isTrue();
    assertThat(set).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  @Test
  public void removeAllRemovesSpecifiedElements() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    set.add(rid(1, 2));
    var changed = set.removeAll(List.of(rid(1, 0), rid(1, 2)));
    assertThat(changed).isTrue();
    assertThat(set).containsExactly(rid(1, 1));
  }

  @Test
  public void retainAllRestrictsToIntersection() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    set.add(rid(1, 2));
    var changed = set.retainAll(List.of(rid(1, 1), rid(2, 2)));
    assertThat(changed).isTrue();
    assertThat(set).containsExactly(rid(1, 1));
  }

  @Test
  public void removeIfFiltersByPredicate() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(2, 0));
    var changed = set.removeIf(id -> id.getIdentity().getCollectionId() == 1);
    assertThat(changed).isTrue();
    assertThat(set).containsExactly(rid(2, 0));
  }

  @Test
  public void clearEmptiesSet() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    set.clear();
    assertThat(set).isEmpty();
  }

  // ------------------------------------------------------------------------- iteration + exports

  @Test
  public void iteratorExposesAllElements() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    var it = set.iterator();
    var seen = new HashSet<Identifiable>();
    while (it.hasNext()) {
      seen.add(it.next());
    }
    assertThat(seen).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  @Test
  public void spliteratorIsSized() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    assertThat(set.spliterator().hasCharacteristics(java.util.Spliterator.SIZED)).isTrue();
  }

  @Test
  public void streamContainsAllElements() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    assertThat(set.stream()).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  @Test
  public void parallelStreamContainsAllElements() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    assertThat(set.parallelStream()).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  @Test
  public void forEachVisitsAllElements() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    var seen = new HashSet<Identifiable>();
    set.forEach(seen::add);
    assertThat(seen).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  @Test
  public void toArrayReturnsElements() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    // Single-element result: HashSet iteration order is unspecified, but here only one element.
    assertThat(set.toArray()).containsExactlyInAnyOrder((Object) rid(1, 0));
  }

  @Test
  public void toArrayWithProvidedArrayMatchesSize() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    var target = new Identifiable[2];
    var ret = set.toArray(target);
    assertThat(ret).isSameAs(target);
    assertThat(ret).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  @Test
  public void toArrayGeneratorProducesCorrectSize() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    set.add(rid(1, 1));
    var arr = set.toArray(Identifiable[]::new);
    assertThat(arr).hasSize(2).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  // ------------------------------------------------------------------------- equals/hashCode/toString

  /** Reflexive self-equality shortcut — returns true without delegation to the backing set. */
  @Test
  public void equalsReturnsTrueForSameInstance() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    assertThat(set.equals(set)).isTrue();
  }

  /** Non-Set object returns false before delegation (equals shortcut). */
  @Test
  public void equalsReturnsFalseForNonSetObject() {
    var set = new LinkSetResultImpl();
    set.add(rid(1, 0));
    assertThat(set.equals("not a set")).isFalse();
    assertThat(set.equals(null)).isFalse();
    assertThat(set.equals(List.of(rid(1, 0)))).isFalse();
  }

  /**
   * WHEN-FIXED: Track 22 — {@code LinkSetResultImpl.equals(Object)} delegates to {@code
   * super.equals(obj)} ({@code Object.equals}) instead of {@code set.equals(obj)}. As a result,
   * two distinct instances with identical contents are never equal, even though both pass the
   * {@code instanceof Set} check. Flip the final-line delegation to {@code set.equals(obj)}.
   * Test pins the buggy contract so the fix falsifies it.
   */
  @Test
  public void equalsReturnsFalseBetweenDistinctInstancesBecauseSuperEqualsIsBuggy() {
    var a = new LinkSetResultImpl();
    a.add(rid(1, 0));
    a.add(rid(1, 1));
    var b = new LinkSetResultImpl();
    b.add(rid(1, 0));
    b.add(rid(1, 1));
    // Current buggy behavior: super.equals(obj) -> false for distinct instances.
    // WHEN-FIXED: change to set.equals(obj) and flip this assertion to isEqualTo(b).
    assertThat(a).isNotEqualTo(b);
  }

  /**
   * WHEN-FIXED: Track 22 — same defect as above; equality against a plain {@link Set} (here an
   * immutable {@code Set.of(...)}) with identical contents also returns false. A fixed
   * implementation would return true (Set equality is order-independent).
   */
  @Test
  public void equalsAgainstForeignSetReturnsFalseBecauseSuperEqualsIsBuggy() {
    var link = new LinkSetResultImpl();
    link.add(rid(1, 0));
    var other = Set.of(rid(1, 0));
    // Current buggy behavior: super.equals(other) -> false.
    // WHEN-FIXED: equals must return true for a Set with the same elements.
    assertThat(link.equals(other)).isFalse();
    // HashSet path — identical result today, same fix flips both.
    assertThat(link.equals(new HashSet<>(List.of(rid(1, 0))))).isFalse();
  }

  @Test
  public void hashCodeMatchesBackingHashSet() {
    var link = new LinkSetResultImpl();
    link.add(rid(1, 0));
    link.add(rid(1, 1));
    var other = new HashSet<>(List.of(rid(1, 0), rid(1, 1)));
    assertThat(link.hashCode()).isEqualTo(other.hashCode());
  }

  @Test
  public void toStringMatchesBackingHashSet() {
    var link = new LinkSetResultImpl();
    link.add(rid(1, 0));
    var expected = new HashSet<>(List.of(rid(1, 0))).toString();
    assertThat(link.toString()).isEqualTo(expected);
  }

  // ------------------------------------------------------------------------- misc

  @Test
  public void isEmptyReflectsState() {
    var set = new LinkSetResultImpl();
    assertThat(set.isEmpty()).isTrue();
    set.add(rid(1, 0));
    assertThat(set.isEmpty()).isFalse();
  }

  @Test
  public void sizeTracksMutations() {
    var set = new LinkSetResultImpl();
    assertThat(set.size()).isZero();
    set.add(rid(1, 0));
    assertThat(set.size()).isOne();
    set.add(rid(1, 0));
    assertThat(set.size()).isOne();
  }
}
