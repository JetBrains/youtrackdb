package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Direct unit tests for {@link EmbeddedSetResultImpl} — a pure delegating {@link
 * com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedSet} wrapper around a
 * {@code HashSet<T>}. All tests are standalone.
 */
public class EmbeddedSetResultImplTest {

  @Test
  public void defaultConstructorCreatesEmptySet() {
    var set = new EmbeddedSetResultImpl<String>();
    assertThat(set).isEmpty();
    set.add("a");
    assertThat(set).containsExactly("a");
  }

  @Test
  public void initialCapacityConstructorIsEmpty() {
    var set = new EmbeddedSetResultImpl<String>(16);
    assertThat(set).isEmpty();
  }

  @Test
  public void copyConstructorIsDefensive() {
    var source = new ArrayList<>(List.of("a", "b"));
    var copy = new EmbeddedSetResultImpl<>(source);
    assertThat(copy).containsExactlyInAnyOrder("a", "b");
    source.add("c");
    assertThat(copy).doesNotContain("c");
  }

  @Test
  public void addReturnsFalseForDuplicate() {
    var set = new EmbeddedSetResultImpl<String>();
    assertThat(set.add("a")).isTrue();
    assertThat(set.add("a")).isFalse();
  }

  @Test
  public void containsReflectsMembership() {
    var set = new EmbeddedSetResultImpl<String>();
    set.add("a");
    assertThat(set.contains("a")).isTrue();
    assertThat(set.contains("b")).isFalse();
    assertThat(set.contains(null)).isFalse();
  }

  @Test
  public void containsAllAllRequired() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b", "c"));
    assertThat(set.containsAll(List.of("a", "c"))).isTrue();
    assertThat(set.containsAll(List.of("a", "z"))).isFalse();
  }

  @Test
  public void removeReturnsFalseIfAbsent() {
    var set = new EmbeddedSetResultImpl<String>();
    set.add("a");
    assertThat(set.remove("z")).isFalse();
    assertThat(set.remove("a")).isTrue();
  }

  @Test
  public void addAllMergesDistinct() {
    var set = new EmbeddedSetResultImpl<String>();
    set.add("a");
    assertThat(set.addAll(List.of("a", "b"))).isTrue();
    assertThat(set).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void removeAllRemovesMembers() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b", "c"));
    assertThat(set.removeAll(List.of("a", "c"))).isTrue();
    assertThat(set).containsExactly("b");
  }

  @Test
  public void retainAllKeepsIntersection() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b", "c"));
    set.retainAll(List.of("a", "c"));
    assertThat(set).containsExactlyInAnyOrder("a", "c");
  }

  @Test
  public void clearEmptiesSet() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    set.clear();
    assertThat(set).isEmpty();
  }

  @Test
  public void removeIfFiltersByPredicate() {
    var set = new EmbeddedSetResultImpl<Integer>();
    set.addAll(List.of(1, 2, 3, 4));
    assertThat(set.removeIf(i -> i % 2 == 0)).isTrue();
    assertThat(set).containsExactlyInAnyOrder(1, 3);
  }

  @Test
  public void iteratorTraversesAllElements() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    var it = set.iterator();
    var seen = new HashSet<String>();
    while (it.hasNext()) {
      seen.add(it.next());
    }
    assertThat(seen).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void spliteratorIsSized() {
    var set = new EmbeddedSetResultImpl<String>();
    set.add("a");
    assertThat(set.spliterator().hasCharacteristics(java.util.Spliterator.SIZED)).isTrue();
  }

  @Test
  public void streamAndParallelStreamExposeElements() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    assertThat(set.stream()).containsExactlyInAnyOrder("a", "b");
    assertThat(set.parallelStream()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void forEachVisitsAllElements() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    var seen = new HashSet<String>();
    set.forEach(seen::add);
    assertThat(seen).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void toArrayReturnsElements() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    assertThat(set.toArray()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void toArrayWithProvidedArrayReusesIt() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    var target = new String[2];
    var ret = set.toArray(target);
    assertThat(ret).isSameAs(target).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void toArrayGeneratorProducesTypedArray() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    assertThat(set.toArray(String[]::new)).containsExactlyInAnyOrder("a", "b");
  }

  // ------------------------------------------------------------------------- equals/hashCode

  @Test
  public void equalsReturnsTrueForSameInstance() {
    var set = new EmbeddedSetResultImpl<String>();
    set.add("a");
    assertThat(set.equals(set)).isTrue();
  }

  @Test
  public void equalsReturnsFalseForNonSet() {
    var set = new EmbeddedSetResultImpl<String>();
    set.add("a");
    assertThat(set.equals("not a set")).isFalse();
    assertThat(set.equals(null)).isFalse();
    assertThat(set.equals(List.of("a"))).isFalse();
  }

  /**
   * WHEN-FIXED: Track 22 — {@code EmbeddedSetResultImpl.equals(Object)} delegates to {@code
   * super.equals(obj)} ({@code Object.equals}) instead of {@code set.equals(obj)}. Two distinct
   * instances with identical contents are reported as unequal even though both pass the {@code
   * instanceof Set} check. Flip the final-line delegation to {@code set.equals(obj)} — that line
   * is currently the only one that prevents every Set equality test from working correctly.
   */
  @Test
  public void equalsReturnsFalseBetweenDistinctInstancesBecauseSuperEqualsIsBuggy() {
    var a = new EmbeddedSetResultImpl<String>();
    a.addAll(List.of("a", "b"));
    var b = new EmbeddedSetResultImpl<String>();
    b.addAll(List.of("a", "b"));
    // Current buggy behavior: super.equals(b) -> false.
    // WHEN-FIXED: assertEquals(a, b).
    assertThat(a).isNotEqualTo(b);
  }

  /**
   * WHEN-FIXED: Track 22 — same defect; comparison against a foreign {@link Set} (immutable
   * {@code Set.of(...)} or a plain {@code HashSet}) also returns false.
   */
  @Test
  public void equalsAgainstForeignSetReturnsFalseBecauseSuperEqualsIsBuggy() {
    var emb = new EmbeddedSetResultImpl<String>();
    emb.addAll(List.of("a", "b"));
    assertThat(emb.equals(Set.of("a", "b"))).isFalse();
    assertThat(emb.equals(new HashSet<>(List.of("a", "b")))).isFalse();
  }

  @Test
  public void hashCodeMatchesBackingHashSet() {
    var set = new EmbeddedSetResultImpl<String>();
    set.addAll(List.of("a", "b"));
    var other = new HashSet<>(List.of("a", "b"));
    assertThat(set.hashCode()).isEqualTo(other.hashCode());
  }

  @Test
  public void toStringIsBracketedForm() {
    var set = new EmbeddedSetResultImpl<String>();
    set.add("a");
    assertThat(set.toString()).isEqualTo(new HashSet<>(List.of("a")).toString());
  }

  // ------------------------------------------------------------------------- misc

  @Test
  public void sizeAndIsEmptyReflectState() {
    var set = new EmbeddedSetResultImpl<String>();
    assertThat(set.isEmpty()).isTrue();
    set.add("a");
    assertThat(set.isEmpty()).isFalse();
    assertThat(set.size()).isEqualTo(1);
  }
}
